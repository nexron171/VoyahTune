package main

import (
	"context"
	"fmt"
	"path/filepath"
	"slices"
	"time"

	"voyahtune/unified-loader/agentwire"
	"voyahtune/unified-loader/remoteattach"
)

type nativeStatusInspector func(context.Context, agentwire.Target) (agentwire.Status, error)

func inspectNativeStatus(ctx context.Context, target agentwire.Target) (agentwire.Status, error) {
	client, err := agentwire.NewClient(target)
	if err != nil {
		return agentwire.Status{}, err
	}
	return client.Status(ctx)
}

type hookReadiness struct {
	Schema          int                        `json:"schema"`
	Boot            string                     `json:"boot"`
	Loader          processIdentity            `json:"loader"`
	NativeID        string                     `json:"nativeId"`
	RuntimeID       string                     `json:"runtimeId"`
	RequiredChanges uint64                     `json:"requiredChanges"`
	Targets         map[string]processIdentity `json:"targets"`
}

func checkNativeHooks(statusPath, procRoot string, config nativeLoaderConfig) (hookReadiness, error) {
	snapshot, err := healthyStatus(statusPath, procRoot)
	if err != nil {
		return hookReadiness{}, err
	}
	if _, err := registeredNativeAgents(config, snapshot); err != nil {
		return hookReadiness{}, err
	}
	now, err := uptimeSeconds(procRoot)
	if err != nil {
		return hookReadiness{}, err
	}
	processes, err := scanProcesses(procRoot)
	if err != nil {
		return hookReadiness{}, err
	}
	ready := hookReadiness{
		Schema: 1, Boot: snapshot.Boot, Loader: snapshot.Loader,
		NativeID: config.Code.NativeID, RuntimeID: config.Code.RuntimeID,
		Targets: map[string]processIdentity{}, RequiredChanges: snapshot.RequiredChanges,
	}
	acknowledged := map[processIdentity]agentStatus{}
	for _, agent := range config.Agents {
		if agent.Dynamic {
			continue
		}
		identity, err := checkInstallationAgent(procRoot, processes, snapshot, config.Code, agent, now)
		if err != nil {
			return hookReadiness{}, fmt.Errorf("hook %s: %w", agent.ID, err)
		}
		status := snapshot.Agents[agent.ID]
		if status.State == "active" {
			if previous, present := acknowledged[identity]; present &&
				(previous.Revision != status.Revision || !slices.Equal(previous.Modules, status.Modules)) {
				return hookReadiness{}, fmt.Errorf("hook %s: target module plan changed", agent.ID)
			}
			acknowledged[identity] = status
		}
		ready.Targets[agent.ID] = identity
	}
	return ready, nil
}

func checkInstallationAgent(procRoot string, processes map[string][]int, snapshot loaderStatus, code nativeCode, agent nativeAgentConfig, now int64) (processIdentity, error) {
	status := snapshot.Agents[agent.ID]
	identity, err := findCatalogProcess(procRoot, processes, agent.Target)
	if err != nil {
		return processIdentity{}, err
	}
	if err := checkAgentIdentity(agent, status, identity); err != nil {
		return processIdentity{}, err
	}
	if status.State != "active" {
		return identity, nil
	}
	if err := requireRecordedAcknowledgement(now, code, agent, status); err != nil {
		return processIdentity{}, err
	}
	actual, err := remoteattach.ReadTarget(procRoot, identity.PID)
	if err != nil {
		return processIdentity{}, err
	}
	if actual.BootID != snapshot.Boot || actual.StartTicks != identity.StartTicks || actual.UID != status.UID {
		return processIdentity{}, fmt.Errorf("acknowledged kernel identity changed")
	}
	return identity, nil
}

func requireRecordedAcknowledgement(now int64, code nativeCode, agent nativeAgentConfig, status agentStatus) error {
	age := now - status.UpdatedUptime
	if status.UpdatedUptime <= 0 || age < -1 || age > maxHeartbeatAgeSeconds {
		return fmt.Errorf("stale module acknowledgement")
	}
	if status.NativeID != code.NativeID || status.RuntimeID != code.RuntimeID {
		return fmt.Errorf("component generation mismatch: native=%s runtime=%s", status.NativeID, status.RuntimeID)
	}
	if status.Revision <= 0 {
		return fmt.Errorf("module plan was not acknowledged")
	}
	if status.Reason != "" {
		return fmt.Errorf("module acknowledgement contains an error: %s", status.Reason)
	}
	for _, module := range agent.Modules {
		if !slices.Contains(status.Modules, module) {
			return fmt.Errorf("required module %s was not acknowledged", module)
		}
	}
	return nil
}

func registeredNativeAgents(config nativeLoaderConfig, snapshot loaderStatus) (map[string]nativeAgentConfig, error) {
	definitions := map[string]nativeAgentConfig{}
	for _, agent := range config.Agents {
		status, present := snapshot.Agents[agent.ID]
		if !present || status.Target != agent.Target {
			return nil, fmt.Errorf("native module %s has no target status", agent.ID)
		}
		definitions[agent.ID] = agent
	}
	for id, status := range snapshot.Agents {
		if _, known := definitions[id]; known {
			continue
		}
		if agent, valid := navigationAudioAgent(status.Target); valid && id == agent.ID {
			definitions[id] = agent
			continue
		}
		if id != "client."+status.Target || !safeName(status.Target) {
			return nil, fmt.Errorf("unknown native agent %s", id)
		}
		definitions[id] = nativeAgentConfig{ID: id, Target: status.Target, Modules: []string{id}, Dynamic: true}
	}
	return definitions, nil
}

func checkNativeHookAcks(statusPath, procRoot string, config nativeLoaderConfig, inspect nativeStatusInspector) error {
	snapshot, err := healthyStatus(statusPath, procRoot)
	if err != nil {
		return err
	}
	definitions, err := registeredNativeAgents(config, snapshot)
	if err != nil {
		return err
	}
	observed := map[processIdentity]agentwire.Status{}
	for id, agent := range definitions {
		status := snapshot.Agents[id]
		if err := checkAgentStatus(procRoot, agent, status); err != nil {
			return err
		}
		if status.State != "active" {
			continue
		}
		if status.NativeID != config.Code.NativeID || status.RuntimeID != config.Code.RuntimeID {
			return fmt.Errorf("native module %s reports another component generation", id)
		}
		actual, err := remoteattach.ReadTarget(procRoot, status.Identity.PID)
		if err != nil {
			return err
		}
		if actual.UID != status.UID || actual.StartTicks != status.Identity.StartTicks || actual.BootID != snapshot.Boot {
			return fmt.Errorf("native module %s kernel identity changed", id)
		}
		actual.NativeID = config.Code.NativeID
		actual.RuntimeID = config.Code.RuntimeID
		value, cached := observed[status.Identity]
		if !cached {
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			value, err = inspect(ctx, actual)
			cancel()
			if err != nil {
				return fmt.Errorf("native module %s live status: %w", id, err)
			}
			observed[status.Identity] = value
		}
		if value.Schema != 1 || value.Target != actual || value.State != "ready" || value.Error != "" || value.Revision != status.Revision || len(value.Modules) != len(status.Modules) {
			return fmt.Errorf("native module %s plan acknowledgement changed", id)
		}
		for _, module := range status.Modules {
			if value.Modules[module] != "active" {
				return fmt.Errorf("native module %s not active in target", module)
			}
		}
		for _, module := range agent.Modules {
			if value.Modules[module] != "active" {
				return fmt.Errorf("required native module %s missing", module)
			}
		}
	}
	return nil
}

func currentStatusPath(stateDirectory string) string {
	return filepath.Join(stateDirectory, "status.json")
}
