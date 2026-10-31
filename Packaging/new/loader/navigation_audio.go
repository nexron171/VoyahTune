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

func navigationAudioAgents(packagesCSV string) []nativeAgentConfig {
	packages := map[string]bool{}
	for _, entry := range strings.Split(packagesCSV, ",") {
		name := strings.TrimSpace(entry)
		if navigationAudioPackageName.MatchString(name) && !strings.HasPrefix(name, "ru.big.town.") {
			packages[name] = true
		}
	}
	names := make([]string, 0, len(packages))
	for name := range packages {
		names = append(names, name)
	}
	sort.Strings(names)
	agents := make([]nativeAgentConfig, 0, len(names))
	for _, name := range names {
		id := "navigation-audio." + name
		agents = append(agents, nativeAgentConfig{
			ID: id, Target: name, Modules: []string{id}, Dynamic: true,
			MaxAttempts: 1, TimeoutSeconds: 35, CrashLimit: 2,
		})
	}
	return agents
}

func navigationAudioProcesses(procRoot string, agents []nativeAgentConfig) []nativeAgentConfig {
	selected := make(map[string]nativeAgentConfig, len(agents))
	for _, agent := range agents {
		selected[agent.Target] = agent
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
		packageName, suffix, child := strings.Cut(name, ":")
		agent, wanted := selected[packageName]
		if !child || suffix == "" || !wanted || seen[name] {
			continue
		}
		seen[name] = true
		digest := sha256.Sum256([]byte(name))
		agent.ID += fmt.Sprintf(".process.%x", digest[:8])
		agent.Target = name
		agents = append(agents, agent)
	}
	return agents
}
