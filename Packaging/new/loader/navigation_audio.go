package main

import (
	"crypto/sha256"
	"fmt"
	"regexp"
	"sort"
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
	if len(agents) == 0 {
		return agents
	}
	processes, err := scanProcesses(procRoot)
	if err != nil {
		return agents
	}
	return navigationAudioFromProcesses(processes, agents)
}

func navigationAudioFromProcesses(processes map[string][]int, agents []nativeAgentConfig) []nativeAgentConfig {
	selected := make(map[string]bool, len(agents))
	for _, agent := range agents {
		selected[agent.Target] = true
	}
	names := make([]string, 0, len(processes))
	for name := range processes {
		names = append(names, name)
	}
	sort.Strings(names)
	for _, name := range names {
		packageName, _, child := strings.Cut(name, ":")
		if !child || !selected[packageName] {
			continue
		}
		agent, valid := navigationAudioAgent(name)
		if valid {
			agents = append(agents, agent)
		}
	}
	return agents
}
