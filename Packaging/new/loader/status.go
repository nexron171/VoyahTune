package main

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
)

type agentStatus struct {
	Identity processIdentity `json:"identity"`
	Target   string          `json:"target"`
	State    string          `json:"state"`
	Reason   string          `json:"reason,omitempty"`
}

type loaderStatus struct {
	Schema        int                    `json:"schema"`
	Boot          string                 `json:"boot"`
	Loader        processIdentity        `json:"loader"`
	UpdatedUptime int64                  `json:"updatedUptime"`
	State         string                 `json:"state"`
	Agents        map[string]agentStatus `json:"agents"`
}

type statusStore struct {
	mutex  sync.Mutex
	agents map[string]agentStatus
}

func (store *statusStore) set(agent agentConfig, identity processIdentity, state, reason string) {
	store.mutex.Lock()
	defer store.mutex.Unlock()
	store.agents[agent.ID] = agentStatus{identity, agent.Target, state, reason}
}

func (store *statusStore) snapshot(procRoot string) map[string]agentStatus {
	store.mutex.Lock()
	defer store.mutex.Unlock()
	result := make(map[string]agentStatus, len(store.agents))
	for id, status := range store.agents {
		if status.Identity.PID > 0 {
			current, err := readIdentity(procRoot, status.Identity.PID, status.Target)
			if err != nil || current != status.Identity {
				status.Identity = processIdentity{}
				status.State = "waiting"
				status.Reason = "process ended"
			}
		}
		result[id] = status
	}
	return result
}

func publicStatus(snapshot loaderStatus) string {
	pid := snapshot.Loader.PID
	if snapshot.State == "stopped" {
		pid = 0
	}
	payload := fmt.Sprintf("v=1;loader=%s;pid=%d", snapshot.State, pid)
	for _, mapping := range [][2]string{{"vd-bypass", "windows"}, {"steering-wheel", "steering"}, {"launcher-dock", "launcher"}, {"multi-display", "multidisplay"}, {"apollo-tech", "apollo"}, {"keyboard-en", "keyboard-en"}, {"keyboard-ru", "keyboard-ru"}} {
		status, present := snapshot.Agents[mapping[1]]
		if !present {
			status.State = "waiting"
		}
		switch status.State {
		case "unsupported", "restart-required":
			status.State = "failed"
		}
		payload += fmt.Sprintf(";%s=%s:%d", mapping[0], status.State, status.Identity.PID)
	}
	return payload
}

type statusPublisher struct {
	path        string
	publicPath  string
	lastPayload string
	delivered   string
	attempts    int
}

func (publisher *statusPublisher) publish(ctx context.Context, snapshot loaderStatus) error {
	if err := writeAtomic(publisher.path, snapshot); err != nil {
		return err
	}
	payload := publicStatus(snapshot)
	if payload != publisher.lastPayload {
		if err := writeAtomicBytes(publisher.publicPath, []byte(payload+"\n"), 0644); err != nil {
			return err
		}
		publisher.lastPayload = payload
		publisher.attempts = 0
	}
	if payload == publisher.delivered || publisher.attempts >= 3 || androidCommand(ctx, "getprop", "sys.boot_completed") != "1" {
		return nil
	}
	publisher.attempts++
	result := androidCommand(ctx, "content", "call", "--user", "0", "--uri", "content://ru.big.town.restoremode.restoremodecontentprovider", "--method", "publishHookStatusV1", "--arg", payload)
	if strings.Contains(result, "stored=true") {
		publisher.delivered = payload
	}
	return nil
}

func checkHealth(statusPath, procRoot string) error {
	var snapshot loaderStatus
	if err := readJSON(statusPath, &snapshot); err != nil {
		return err
	}
	boot, err := bootID(procRoot)
	if err != nil {
		return err
	}
	now, err := uptimeSeconds(procRoot)
	if err != nil {
		return err
	}
	if snapshot.Schema != 1 || snapshot.State != "running" || snapshot.Boot != boot || snapshot.UpdatedUptime > now || now-snapshot.UpdatedUptime > 15 {
		return fmt.Errorf("stale or invalid supervisor heartbeat")
	}
	command, err := os.ReadFile(filepath.Join(procRoot, fmt.Sprint(snapshot.Loader.PID), "cmdline"))
	if err != nil {
		return err
	}
	argument, _, _ := strings.Cut(string(command), "\x00")
	if filepath.Base(argument) != "voyahtune-loader" {
		return fmt.Errorf("heartbeat belongs to another executable")
	}
	identity, err := readIdentity(procRoot, snapshot.Loader.PID, argument)
	if err != nil || identity != snapshot.Loader {
		return fmt.Errorf("supervisor PID was reused")
	}
	return nil
}
