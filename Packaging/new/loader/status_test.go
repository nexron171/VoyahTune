package main

import (
	"context"
	"errors"
	"path/filepath"
	"strings"
	"testing"

	"voyahtune/unified-loader/agentwire"
)

func nativeHealthFixture(t *testing.T) (string, string, nativeLoaderConfig, loaderStatus, agentwire.Status) {
	t.Helper()
	root := t.TempDir()
	fakeProcess(t, root, 10, "/data/local/bin/voyahtune-new/voyahtune-loader", "100")
	fakeProcess(t, root, 20, "com.qinggan.canbus.service", "200")
	testFile(t, filepath.Join(root, "sys/kernel/random/boot_id"), "boot", 0600)
	testFile(t, filepath.Join(root, "uptime"), "110.00 0.00", 0600)
	testFile(t, filepath.Join(root, "20/status"), "Uid:\t1000\t1000\t1000\t1000\n", 0600)
	config := nativeLoaderConfig{Code: nativeCode{NativeID: "native", RuntimeID: "java"}, Agents: []nativeAgentConfig{{ID: "acc", Target: "com.qinggan.canbus.service", Modules: []string{"acc"}}}}
	snapshot := loaderStatus{Schema: 1, Boot: "boot", Loader: processIdentity{10, "100"}, UpdatedUptime: 109, State: "running", Agents: map[string]agentStatus{
		"acc": {Identity: processIdentity{20, "200"}, Target: config.Agents[0].Target, State: "active", UID: 1000, NativeID: "native", RuntimeID: "java", Revision: 7, Modules: []string{"acc"}},
	}}
	ack := agentwire.Status{Schema: 1, Target: agentwire.Target{BootID: "boot", PID: 20, UID: 1000, StartTicks: "200", NativeID: "native", RuntimeID: "java"}, State: "ready", Revision: 7, Modules: map[string]string{"acc": "active"}}
	return filepath.Join(root, "status.json"), root, config, snapshot, ack
}

func TestNativeHealthRequiresCurrentSupervisorAndAgentState(t *testing.T) {
	for _, scenario := range []struct {
		name     string
		mutate   func(*loaderStatus)
		accepted bool
	}{
		{"active", func(*loaderStatus) {}, true},
		{"missing", func(s *loaderStatus) { delete(s.Agents, "acc") }, false},
		{"stale-heartbeat", func(s *loaderStatus) { s.UpdatedUptime = 90 }, false},
		{"loader-reused", func(s *loaderStatus) { s.Loader.StartTicks = "99" }, false},
		{"loader-quiesced", func(s *loaderStatus) { s.State = "quiesced" }, false},
	} {
		t.Run(scenario.name, func(t *testing.T) {
			path, root, config, snapshot, ack := nativeHealthFixture(t)
			scenario.mutate(&snapshot)
			if err := writeAtomic(path, snapshot); err != nil {
				t.Fatal(err)
			}
			err := checkNativeHooksWithInspector(path, root, config, func(context.Context, agentwire.Target) (agentwire.Status, error) { return ack, nil })
			if (err == nil) != scenario.accepted {
				t.Fatalf("accepted=%t: %v", scenario.accepted, err)
			}
		})
	}
	for _, state := range []string{"failed", "injecting", "restart-required", "unsupported", "disabled", "waiting"} {
		t.Run(state, func(t *testing.T) {
			path, root, config, snapshot, ack := nativeHealthFixture(t)
			status := snapshot.Agents["acc"]
			status.State = state
			snapshot.Agents["acc"] = status
			if err := writeAtomic(path, snapshot); err != nil {
				t.Fatal(err)
			}
			if err := checkNativeHooksWithInspector(path, root, config, func(context.Context, agentwire.Target) (agentwire.Status, error) { return ack, nil }); err == nil {
				t.Fatal("Unready mandatory agent accepted")
			}
		})
	}
}

func TestNativeHealthRequiresFullLiveAcknowledgement(t *testing.T) {
	for _, scenario := range []struct {
		name   string
		mutate func(*agentwire.Status)
	}{
		{"revision", func(s *agentwire.Status) { s.Revision++ }},
		{"runtime", func(s *agentwire.Status) { s.RuntimeID = "old" }},
		{"uid", func(s *agentwire.Status) { s.UID++ }},
		{"schema", func(s *agentwire.Status) { s.Schema = 0 }},
		{"partial", func(s *agentwire.Status) { delete(s.Modules, "acc") }},
		{"inactive", func(s *agentwire.Status) { s.Modules["acc"] = "failed" }},
		{"draining", func(s *agentwire.Status) { s.State = "quiescing" }},
		{"error", func(s *agentwire.Status) { s.Error = "cleanup uncertain" }},
	} {
		t.Run(scenario.name, func(t *testing.T) {
			path, root, config, snapshot, ack := nativeHealthFixture(t)
			scenario.mutate(&ack)
			if err := writeAtomic(path, snapshot); err != nil {
				t.Fatal(err)
			}
			if err := checkNativeHooksWithInspector(path, root, config, func(context.Context, agentwire.Target) (agentwire.Status, error) { return ack, nil }); err == nil {
				t.Fatal("Invalid live acknowledgement accepted")
			}
		})
	}
	path, root, config, snapshot, _ := nativeHealthFixture(t)
	if err := writeAtomic(path, snapshot); err != nil {
		t.Fatal(err)
	}
	err := checkNativeHooksWithInspector(path, root, config, func(context.Context, agentwire.Target) (agentwire.Status, error) {
		return agentwire.Status{}, errors.New("endpoint unavailable")
	})
	if err == nil || !strings.Contains(err.Error(), "endpoint unavailable") {
		t.Fatal(err)
	}
}

func TestNativeHealthAllowsAbsentAndConditionallyDisabledAgents(t *testing.T) {
	root := t.TempDir()
	agent := nativeAgentConfig{ID: "keyboard-en", Target: "com.qinggan.app.qgime", Condition: "keyboard-en"}
	status := agentStatus{Target: agent.Target, State: "waiting"}
	if err := checkAgentStatus(root, agent, status); err != nil {
		t.Fatal(err)
	}
	fakeProcess(t, root, 20, agent.Target, "200")
	status.Identity = processIdentity{20, "200"}
	status.State = "disabled"
	if err := checkAgentStatus(root, agent, status); err != nil {
		t.Fatal(err)
	}
	status.Identity.StartTicks = "199"
	if err := checkAgentStatus(root, agent, status); err == nil {
		t.Fatal("Stale disabled identity accepted")
	}
}
