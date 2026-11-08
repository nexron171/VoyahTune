package main

import (
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Snapshots are immutable; slow agent operations never hold the catalog lock.
type processCatalog struct {
	mutex     sync.Mutex
	procRoot  string
	now       func() time.Time
	scan      func(string) (map[string][]int, error)
	refreshed time.Time
	processes map[string][]int
	scanError error
}

func scanProcesses(procRoot string) (map[string][]int, error) {
	entries, err := os.ReadDir(procRoot)
	if err != nil {
		return nil, err
	}
	processes := make(map[string][]int)
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
		if name != "" {
			processes[name] = append(processes[name], pid)
		}
	}
	for _, pids := range processes {
		sort.Ints(pids)
	}
	return processes, nil
}

func (catalog *processCatalog) snapshot() (map[string][]int, error) {
	catalog.mutex.Lock()
	defer catalog.mutex.Unlock()
	now := time.Now()
	if catalog.now != nil {
		now = catalog.now()
	}
	if catalog.refreshed.IsZero() || now.Sub(catalog.refreshed) >= time.Second {
		scan := catalog.scan
		if scan == nil {
			scan = scanProcesses
		}
		catalog.processes, catalog.scanError = scan(catalog.procRoot)
		catalog.refreshed = now
	}
	return catalog.processes, catalog.scanError
}

func (catalog *processCatalog) find(name string) (processIdentity, error) {
	processes, err := catalog.snapshot()
	if err != nil {
		return processIdentity{}, err
	}
	var result processIdentity
	for command, pids := range processes {
		if command != name && !(strings.IndexByte(name, '.') < 0 && filepath.Base(command) == name) {
			continue
		}
		for _, pid := range pids {
			identity, err := readIdentity(catalog.procRoot, pid, name)
			if err == nil && (result.PID == 0 || pid < result.PID) {
				result = identity
			}
		}
	}
	return result, nil
}
