package main

import (
	"context"
	"encoding/json"
	"strings"
	"testing"
)

func TestDiagnosticsPublishEveryAgentAndKeepFailureDetails(t *testing.T) {
	snapshot := loaderStatus{Boot: "boot", Loader: processIdentity{12, "3"}, UpdatedUptime: 100, State: "running", Agents: map[string]agentStatus{
		"phone":              {Identity: processIdentity{23, "4"}, State: "active", Target: "com.qinggan.bluetoothphone", UpdatedUptime: 99, NativeID: "native", RuntimeID: "java", Modules: []string{"phone"}},
		"client.example.app": {Identity: processIdentity{24, "5"}, State: "restart-required", Reason: "cleanup uncertain", Target: "example.app", UpdatedUptime: 98},
	}}
	data, err := diagnosticsPayload(snapshot, nativeCode{NativeID: "native", RuntimeID: "java"}, "")
	if err != nil {
		t.Fatal(err)
	}
	var decoded map[string]any
	if err := json.Unmarshal([]byte(data), &decoded); err != nil {
		t.Fatal(err)
	}
	agents := decoded["agents"].(map[string]any)
	if len(agents) != 2 || agents["client.example.app"].(map[string]any)["reason"] != "cleanup uncertain" || decoded["schema"] != float64(2) {
		t.Fatal(decoded)
	}
	if agents["phone"].(map[string]any)["updatedUptime"] != float64(99) {
		t.Fatal(decoded)
	}
}

func TestDiagnosticsHeartbeatRetriesAndDoesNotSpawnEverySecond(t *testing.T) {
	calls := 0
	publisher := diagnosticsPublisher{command: func(_ context.Context, command string, args ...string) string {
		if command == "getprop" {
			return "1"
		}
		calls++
		if !strings.Contains(strings.Join(args, " "), "publishRuntimeStatusV2") {
			t.Fatal(args)
		}
		if calls < 4 {
			return ""
		}
		return "Bundle[{stored=true}]"
	}}
	for second := int64(100); second <= 121; second++ {
		if err := publisher.publish(context.Background(), loaderStatus{UpdatedUptime: second}, nativeCode{}, ""); err != nil {
			t.Fatal(err)
		}
	}
	if calls != 5 {
		t.Fatalf("want five bounded heartbeat attempts, got %d", calls)
	}
}
