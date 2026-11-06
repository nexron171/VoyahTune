package main

import (
	"crypto/sha256"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
)

var navigationAudioPackageName = regexp.MustCompile(`^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$`)

func navigationAudioAgent(target string) (nativeAgentConfig, bool) {
	packageName, suffix, child := strings.Cut(target, ":")
	if !navigationAudioPackageName.MatchString(packageName) || strings.HasPrefix(packageName, "ru.big.town.") || (child && suffix == "") {
		return nativeAgentConfig{}, false
	}
	module := "navigation-audio." + packageName
	id := module
	if child {
		digest := sha256.Sum256([]byte(target))
		id += fmt.Sprintf(".process.%x", digest[:8])
	}
	return nativeAgentConfig{
		ID: id, Target: target, Modules: []string{module}, Dynamic: true,
		MaxAttempts: 1, TimeoutSeconds: 35, CrashLimit: 2,
	}, true
}

func navigationAudioAgents(packagesCSV string) []nativeAgentConfig {
	packages := map[string]nativeAgentConfig{}
	for _, entry := range strings.Split(packagesCSV, ",") {
		name := strings.TrimSpace(entry)
		if agent, valid := navigationAudioAgent(name); valid && !strings.Contains(name, ":") {
			packages[name] = agent
		}
	}
	names := make([]string, 0, len(packages))
	for name := range packages {
		names = append(names, name)
	}
	sort.Strings(names)
	agents := make([]nativeAgentConfig, 0, len(names))
	for _, name := range names {
		agents = append(agents, packages[name])
	}
	return agents
}

func navigationAudioProcesses(procRoot string, agents []nativeAgentConfig) []nativeAgentConfig {
	selected := make(map[string]bool, len(agents))
	for _, agent := range agents {
		selected[agent.Target] = true
	}
	if len(selected) == 0 {
		return agents
	}
	entries, err := os.ReadDir(procRoot)
	if err != nil {
		return agents
	}
	seen := map[string]bool{}
	for _, entry := range entries {
		pid, err := strconv.Atoi(entry.Name())
		if err != nil || pid <= 0 {
			continue
		}
		command, err := os.ReadFile(filepath.Join(procRoot, entry.Name(), "cmdline"))
		if err != nil {
			continue
		}
		name, _, _ := strings.Cut(string(command), "\x00")
		packageName, _, child := strings.Cut(name, ":")
		if !child || !selected[packageName] || seen[name] {
			continue
		}
		agent, valid := navigationAudioAgent(name)
		if !valid {
			continue
		}
		seen[name] = true
		agents = append(agents, agent)
	}
	return agents
}
