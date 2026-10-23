package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
)

type processIdentity struct {
	PID        int    `json:"pid"`
	StartTicks string `json:"startTicks"`
}

func readIdentity(procRoot string, pid int, name string) (processIdentity, error) {
	directory := filepath.Join(procRoot, strconv.Itoa(pid))
	command, err := os.ReadFile(filepath.Join(directory, "cmdline"))
	if err != nil {
		return processIdentity{}, err
	}
	argument, _, _ := strings.Cut(string(command), "\x00")
	if argument != name && !(strings.IndexByte(name, '.') < 0 && filepath.Base(argument) == name) {
		return processIdentity{}, errors.New("process name changed")
	}
	data, err := os.ReadFile(filepath.Join(directory, "stat"))
	if err != nil {
		return processIdentity{}, err
	}
	end := strings.LastIndexByte(string(data), ')')
	if end < 0 {
		return processIdentity{}, errors.New("invalid process stat")
	}
	fields := strings.Fields(string(data[end+1:]))
	if len(fields) <= 19 {
		return processIdentity{}, errors.New("short process stat")
	}
	if _, err := strconv.ParseUint(fields[19], 10, 64); err != nil {
		return processIdentity{}, err
	}
	return processIdentity{pid, fields[19]}, nil
}

func findProcess(procRoot, name string) (processIdentity, error) {
	entries, err := os.ReadDir(procRoot)
	if err != nil {
		return processIdentity{}, err
	}
	var matches []processIdentity
	for _, entry := range entries {
		pid, err := strconv.Atoi(entry.Name())
		if err != nil || pid <= 0 {
			continue
		}
		identity, err := readIdentity(procRoot, pid, name)
		if err == nil {
			matches = append(matches, identity)
		}
	}
	if len(matches) == 0 {
		return processIdentity{}, nil
	}
	sort.Slice(matches, func(i, j int) bool { return matches[i].PID < matches[j].PID })
	return matches[0], nil
}

func uptimeSeconds(procRoot string) (int64, error) {
	data, err := os.ReadFile(filepath.Join(procRoot, "uptime"))
	if err != nil {
		return 0, err
	}
	fields := strings.Fields(string(data))
	if len(fields) == 0 {
		return 0, errors.New("missing uptime")
	}
	value, err := strconv.ParseFloat(fields[0], 64)
	if err != nil || value < 0 {
		return 0, errors.New("invalid uptime")
	}
	return int64(value), nil
}

func bootID(procRoot string) (string, error) {
	data, err := os.ReadFile(filepath.Join(procRoot, "sys/kernel/random/boot_id"))
	if err != nil {
		return "", err
	}
	value := strings.TrimSpace(string(data))
	if value == "" {
		return "", errors.New("missing boot identity")
	}
	return value, nil
}

func writeAtomic(path string, value any) error {
	data, err := json.Marshal(value)
	if err != nil {
		return err
	}
	return writeAtomicBytes(path, append(data, '\n'), 0600)
}

func writeAtomicBytes(path string, data []byte, mode os.FileMode) error {
	file, err := os.CreateTemp(filepath.Dir(path), ".unified-")
	if err != nil {
		return err
	}
	defer os.Remove(file.Name())
	if _, err := file.Write(data); err != nil {
		file.Close()
		return err
	}
	if err := file.Chmod(mode); err != nil {
		file.Close()
		return err
	}
	if err := file.Close(); err != nil {
		return err
	}
	return os.Rename(file.Name(), path)
}

func readJSON(path string, value any) error {
	data, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	if len(data) > 1024*1024 {
		return fmt.Errorf("record too large: %s", path)
	}
	return json.Unmarshal(data, value)
}
