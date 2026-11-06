package main

import (
	"context"
	"fmt"
	"path/filepath"
	"time"

	"voyahtune/unified-loader/agentwire"
	"voyahtune/unified-loader/remoteattach"
)

type nativeStatusInspector func(context.Context, agentwire.Target) (agentwire.Status, error)

const installationStabilitySeconds = 10

func inspectNativeStatus(ctx context.Context, target agentwire.Target) (agentwire.Status, error) {
	client, err := agentwire.NewClient(target)
	if err != nil {
		return agentwire.Status{}, err
	}
	return client.Status(ctx)
}

func checkNativeHooks(statusPath, procRoot string, config nativeLoaderConfig) error {
	snapshot, err := healthyStatus(statusPath, procRoot)
	if err != nil {
		return err
	}
	if snapshot.StableSinceUptime <= 0 || snapshot.UpdatedUptime-snapshot.StableSinceUptime < installationStabilitySeconds {
		return fmt.Errorf("waiting for %d seconds of stable loader and hook target PIDs", installationStabilitySeconds)
	}
	agents, err := registeredNativeAgents(config, snapshot)
	if err != nil {
		return err
	}
	for id, agent := range agents {
		status := snapshot.Agents[id]
		if status.Identity.PID == 0 {
			if err := checkAgentStatus(procRoot, agent, status); err != nil {
				return err
			}
			continue
		}
		identity, err := readIdentity(procRoot, status.Identity.PID, agent.Target)
		if err != nil || identity != status.Identity {
			return fmt.Errorf("hook %s target PID %d changed or exited", id, status.Identity.PID)
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
