package main

import (
	"context"
	"encoding/json"
	"fmt"
)

const diagnosticsIntervalSeconds = 5

func diagnosticsPayload(snapshot loaderStatus, code nativeCode, configurationError string) (string, error) {
	// Keep the public record bounded without modifying the supervisor's private state.
	agents := make(map[string]agentStatus, len(snapshot.Agents))
	for id, status := range snapshot.Agents {
		reason := []rune(status.Reason)
		if len(reason) > 384 {
			status.Reason = string(reason[:384]) + "…"
		}
		agents[id] = status
	}
	snapshot.Schema = 2
	snapshot.Agents = agents
	payload := struct {
		loaderStatus
		NativeID           string `json:"nativeId"`
		RuntimeID          string `json:"runtimeId"`
		ConfigurationError string `json:"configurationError"`
	}{snapshot, code.NativeID, code.RuntimeID, configurationError}
	data, err := json.Marshal(payload)
	if err != nil {
		return "", err
	}
	if len(data) > 65536 {
		return "", fmt.Errorf("runtime diagnostics exceed 64 KiB")
	}
	return string(data), nil
}

type diagnosticsPublisher struct {
	command     func(context.Context, string, ...string) string
	lastAttempt int64
	lastState   string
	attempted   bool
}

func (publisher *diagnosticsPublisher) publish(ctx context.Context, snapshot loaderStatus, code nativeCode, configurationError string) error {
	if publisher.attempted && snapshot.State == publisher.lastState && snapshot.UpdatedUptime-publisher.lastAttempt < diagnosticsIntervalSeconds {
		return nil
	}
	publisher.lastAttempt = snapshot.UpdatedUptime
	publisher.lastState = snapshot.State
	publisher.attempted = true
	if publisher.command(ctx, "getprop", "sys.boot_completed") != "1" {
		return nil
	}
	payload, err := diagnosticsPayload(snapshot, code, configurationError)
	if err != nil {
		return err
	}
	// Every heartbeat retries delivery, including after an unchanged failed publication.
	publisher.command(ctx, "content", "call", "--user", "0", "--uri", "content://ru.big.town.restoremode.restoremodecontentprovider", "--method", "publishRuntimeStatusV2", "--arg", payload)
	return nil
}
