package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

type agentConfig struct {
	ID             string   `json:"id"`
	Target         string   `json:"target"`
	Script         string   `json:"script"`
	Ready          []string `json:"ready"`
	Lifetime       string   `json:"lifetime"`
	Priority       bool     `json:"priority,omitempty"`
	Condition      string   `json:"condition,omitempty"`
	Health         string   `json:"health,omitempty"`
	StatusFile     string   `json:"statusFile,omitempty"`
	MaxAttempts    int      `json:"maxAttempts"`
	TimeoutSeconds int      `json:"timeoutSeconds"`
	CrashLimit     int      `json:"crashLimit,omitempty"`
	Allow32Bit     bool     `json:"allow32Bit,omitempty"`
	Dynamic        bool     `json:"-"`
}

type loaderConfig struct {
	Schema          int           `json:"schema"`
	PrioritySeconds int           `json:"prioritySeconds"`
	Agents          []agentConfig `json:"agents"`
}

func safeName(value string) bool {
	if value == "" || strings.HasPrefix(value, ".") || strings.Contains(value, "..") {
		return false
	}
	for _, character := range value {
		if !(character >= 'a' && character <= 'z' || character >= 'A' && character <= 'Z' || character >= '0' && character <= '9' || character == '.' || character == '_' || character == '-') {
			return false
		}
	}
	return true
}

func loadConfig(runtimeDirectory string) (loaderConfig, error) {
	var config loaderConfig
	data, err := os.ReadFile(filepath.Join(runtimeDirectory, "agents.json"))
	if err != nil {
		return config, err
	}
	decoder := json.NewDecoder(strings.NewReader(string(data)))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&config); err != nil {
		return config, err
	}
	if config.Schema != 1 || config.PrioritySeconds <= 0 || config.PrioritySeconds > 60 || len(config.Agents) == 0 {
		return config, errors.New("invalid agent manifest")
	}
	seen := map[string]bool{}
	priorityCount := 0
	for _, agent := range config.Agents {
		if !safeName(agent.ID) || !safeName(agent.Target) || !safeName(agent.Script) || !strings.HasSuffix(agent.Script, ".js") || seen[agent.ID] {
			return config, fmt.Errorf("invalid agent: %s", agent.ID)
		}
		seen[agent.ID] = true
		if agent.Priority {
			priorityCount++
		}
		if agent.Lifetime != "eternalized" && agent.Lifetime != "attached" {
			return config, fmt.Errorf("invalid lifetime: %s", agent.ID)
		}
		if agent.Target == "system_server" && agent.Lifetime != "eternalized" {
			return config, errors.New("system_server requires eternalized injection")
		}
		if len(agent.Ready) == 0 || agent.MaxAttempts < 1 || agent.MaxAttempts > 10 || agent.TimeoutSeconds < 1 || agent.TimeoutSeconds > 60 {
			return config, fmt.Errorf("missing lifecycle bounds: %s", agent.ID)
		}
		if agent.Health != "" && !safeName(agent.Health) {
			return config, errors.New("invalid health path")
		}
		if agent.StatusFile != "" && agent.StatusFile != "vd" {
			return config, errors.New("unknown agent status format")
		}
		if agent.Condition != "" && agent.Condition != "apollo" && agent.Condition != "keyboard-en" && agent.Condition != "keyboard-ru" {
			return config, errors.New("unknown agent condition")
		}
		info, err := os.Stat(filepath.Join(runtimeDirectory, agent.Script))
		if err != nil || !info.Mode().IsRegular() || info.Size() == 0 {
			return config, fmt.Errorf("missing agent %s", agent.Script)
		}
	}
	if priorityCount != 1 {
		return config, errors.New("exactly one ACC priority agent is required")
	}
	for _, name := range []string{"frida-inject", "frida-inject-arm"} {
		info, err := os.Stat(filepath.Join(runtimeDirectory, name))
		if err != nil || !info.Mode().IsRegular() || info.Mode().Perm()&0111 == 0 {
			return config, fmt.Errorf("missing executable %s", name)
		}
	}
	return config, nil
}
