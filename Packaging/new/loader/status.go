package main

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"
)

const maxHeartbeatAgeSeconds = 15

type agentStatus struct {
	UpdatedUptime int64           `json:"updatedUptime"`
	Architecture  string          `json:"architecture,omitempty"`
	Identity      processIdentity `json:"identity"`
	Target        string          `json:"target"`
	State         string          `json:"state"`
	Reason        string          `json:"reason,omitempty"`
	UID           uint32          `json:"uid,omitempty"`
	NativeID      string          `json:"nativeId,omitempty"`
	RuntimeID     string          `json:"runtimeId,omitempty"`
	Revision      int64           `json:"revision,omitempty"`
	Modules       []string        `json:"modules,omitempty"`
}

type loaderStatus struct {
	Schema          int                    `json:"schema"`
	Boot            string                 `json:"boot"`
	Loader          processIdentity        `json:"loader"`
	UpdatedUptime   int64                  `json:"updatedUptime"`
	RequiredChanges uint64                 `json:"requiredChanges"`
	State           string                 `json:"state"`
	Agents          map[string]agentStatus `json:"agents"`
}

type statusStore struct {
	mutex           sync.Mutex
	agents          map[string]agentStatus
	dynamic         map[string]bool
	requiredChanges uint64
}

func (status agentStatus) sameReadiness(other agentStatus) bool {
	if status.Identity != other.Identity || status.Target != other.Target || status.State != other.State {
		return false
	}
	if status.State != "active" {
		return true
	}
	return (status.Reason == "") == (other.Reason == "") && status.UID == other.UID &&
		status.NativeID == other.NativeID && status.RuntimeID == other.RuntimeID &&
		status.Revision == other.Revision && slices.Equal(status.Modules, other.Modules)
}

func (store *statusStore) report(agent nativeAgentConfig, status agentStatus) {
	store.mutex.Lock()
	defer store.mutex.Unlock()
	previous, present := store.agents[agent.ID]
	if !agent.Dynamic && (!present || store.dynamic[agent.ID] || !previous.sameReadiness(status)) {
		store.requiredChanges++
	}
	if store.dynamic == nil {
		store.dynamic = map[string]bool{}
	}
	store.dynamic[agent.ID] = agent.Dynamic
	store.agents[agent.ID] = status
}

func (store *statusStore) snapshot(procRoot string) (map[string]agentStatus, uint64) {
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
				if !store.dynamic[id] {
					store.requiredChanges++
				}
				store.agents[id] = status
			}
		}
		result[id] = status
	}
	return result, store.requiredChanges
}

func publicStatus(snapshot loaderStatus) string {
	pid := snapshot.Loader.PID
	state := snapshot.State
	if state != "running" {
		state = "stopped"
		pid = 0
	}
	payload := fmt.Sprintf("v=1;loader=%s;pid=%d", state, pid)
	for _, mapping := range [][2]string{{"vd-bypass", "windows"}, {"steering-wheel", "steering"}, {"launcher-dock", "launcher"}, {"multi-display", "multidisplay"}, {"apollo-tech", "apollo"}, {"keyboard-en", "keyboard-en"}, {"keyboard-ru", "keyboard-ru"}} {
		status, present := snapshot.Agents[mapping[1]]
		if !present {
			status.State = "waiting"
		}
		if snapshot.State == "quiesced" {
			status.State = "disabled"
		} else if snapshot.State != "running" {
			status.State = "unknown"
		} else {
			switch status.State {
			case "active", "waiting", "injecting", "failed", "disabled":
			case "unsupported", "restart-required":
				status.State = "failed"
			default:
				status.State = "unknown"
			}
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
	_, err := healthyStatus(statusPath, procRoot)
	return err
}

func healthyStatus(statusPath, procRoot string) (loaderStatus, error) {
	var snapshot loaderStatus
	if err := readJSON(statusPath, &snapshot); err != nil {
		return snapshot, err
	}
	boot, err := bootID(procRoot)
	if err != nil {
		return snapshot, err
	}
	now, err := uptimeSeconds(procRoot)
	if err != nil {
		return snapshot, err
	}
	if snapshot.Schema != 1 || snapshot.State != "running" || snapshot.Boot != boot || snapshot.UpdatedUptime > now || now-snapshot.UpdatedUptime > maxHeartbeatAgeSeconds {
		return snapshot, fmt.Errorf("stale or invalid supervisor heartbeat")
	}
	command, err := os.ReadFile(filepath.Join(procRoot, fmt.Sprint(snapshot.Loader.PID), "cmdline"))
	if err != nil {
		return snapshot, err
	}
	argument, _, _ := strings.Cut(string(command), "\x00")
	if filepath.Base(argument) != "voyahtune-loader" {
		return snapshot, fmt.Errorf("heartbeat belongs to another executable")
	}
	identity, err := readIdentity(procRoot, snapshot.Loader.PID, argument)
	if err != nil || identity != snapshot.Loader {
		return snapshot, fmt.Errorf("supervisor PID was reused")
	}
	return snapshot, nil
}

func checkAgentStatus(procRoot string, agent nativeAgentConfig, status agentStatus) error {
	identity, err := findProcess(procRoot, agent.Target)
	if err != nil {
		return fmt.Errorf("hook %s: %w", agent.ID, err)
	}
	if err := checkAgentIdentity(agent, status, identity); err != nil {
		return fmt.Errorf("hook %s: %w", agent.ID, err)
	}
	return nil
}

func checkAgentIdentity(agent nativeAgentConfig, status agentStatus, identity processIdentity) error {
	switch status.State {
	case "active":
		if identity.PID > 0 && status.Identity == identity {
			return nil
		}
	case "disabled":
		if (agent.Condition != "" || agent.Dynamic) && identity.PID > 0 && status.Identity == identity {
			return nil
		}
	case "waiting":
		if identity.PID == 0 && status.Identity == (processIdentity{}) {
			return nil
		}
	}
	return fmt.Errorf("%s (PID %d, start %s; %s)", status.State, status.Identity.PID, status.Identity.StartTicks, status.Reason)
}
