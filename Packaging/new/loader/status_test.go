package main

import (
	"path/filepath"
	"strings"
	"testing"
)

func TestHookHealthRequiresEveryConfiguredAgentAndCurrentProcess(t *testing.T) {
	for _, scenario := range []struct {
		name     string
		mutate   func(*loaderStatus)
		accepted bool
	}{
		{"active", func(*loaderStatus) {}, true},
		{"failed", func(status *loaderStatus) {
			status.Agents["acc"] = agentStatus{processIdentity{20, "200"}, "com.qinggan.canbus.service", "failed", "attempt budget consumed"}
		}, false},
		{"missing", func(status *loaderStatus) { delete(status.Agents, "acc") }, false},
		{"empty", func(status *loaderStatus) { status.Agents = nil }, false},
		{"injecting", func(status *loaderStatus) {
			agent := status.Agents["acc"]
			agent.State = "injecting"
			status.Agents["acc"] = agent
		}, false},
		{"restart-required", func(status *loaderStatus) {
			agent := status.Agents["acc"]
			agent.State = "restart-required"
			status.Agents["acc"] = agent
		}, false},
		{"unsupported", func(status *loaderStatus) {
			agent := status.Agents["acc"]
			agent.State = "unsupported"
			status.Agents["acc"] = agent
		}, false},
		{"mandatory-disabled", func(status *loaderStatus) {
			agent := status.Agents["acc"]
			agent.State = "disabled"
			status.Agents["acc"] = agent
		}, false},
		{"live-but-waiting", func(status *loaderStatus) {
			status.Agents["acc"] = agentStatus{Target: "com.qinggan.canbus.service", State: "waiting"}
		}, false},
		{"pid-reused", func(status *loaderStatus) {
			agent := status.Agents["acc"]
			agent.Identity.StartTicks = "199"
			status.Agents["acc"] = agent
		}, false},
		{"wrong-target", func(status *loaderStatus) {
			agent := status.Agents["acc"]
			agent.Target = "another.process"
			status.Agents["acc"] = agent
		}, false},
	} {
		t.Run(scenario.name, func(t *testing.T) {
			root := t.TempDir()
			fakeProcess(t, root, 10, "/data/local/bin/voyahtune-new/voyahtune-loader", "100")
			fakeProcess(t, root, 20, "com.qinggan.canbus.service", "200")
			testFile(t, filepath.Join(root, "sys/kernel/random/boot_id"), "boot", 0644)
			testFile(t, filepath.Join(root, "uptime"), "110.00 0.00", 0644)
			config := loaderConfig{Agents: []agentConfig{{ID: "acc", Target: "com.qinggan.canbus.service"}}}
			snapshot := loaderStatus{
				Schema: 1, Boot: "boot", Loader: processIdentity{10, "100"}, UpdatedUptime: 109, State: "running",
				Agents: map[string]agentStatus{"acc": {processIdentity{20, "200"}, "com.qinggan.canbus.service", "active", ""}},
			}
			scenario.mutate(&snapshot)
			path := filepath.Join(root, "status.json")
			if err := writeAtomic(path, snapshot); err != nil {
				t.Fatal(err)
			}
			if err := checkHealth(path, root); err != nil {
				t.Fatal("supervisor fixture must be healthy", err)
			}
			err := checkHooks(path, root, config)
			if (err == nil) != scenario.accepted {
				t.Fatalf("accepted=%t: %v", scenario.accepted, err)
			}
			if err != nil && !strings.Contains(err.Error(), "hook acc:") {
				t.Fatal("missing agent diagnostics", err)
			}
		})
	}
}

func TestHookHealthAllowsAbsentAppsAndConditionallyDisabledAgents(t *testing.T) {
	root := t.TempDir()
	agent := agentConfig{ID: "keyboard-en", Target: "com.qinggan.app.qgime", Condition: "keyboard-en"}
	status := agentStatus{Target: agent.Target, State: "waiting"}
	if err := checkAgentStatus(root, agent, status); err != nil {
		t.Fatal("absent optional app", err)
	}
	fakeProcess(t, root, 20, agent.Target, "200")
	status.Identity = processIdentity{20, "200"}
	status.State = "disabled"
	if err := checkAgentStatus(root, agent, status); err != nil {
		t.Fatal("disabled keyboard lane", err)
	}
	status.Identity.StartTicks = "199"
	if err := checkAgentStatus(root, agent, status); err == nil {
		t.Fatal("stale disabled state accepted")
	}
}

func TestHookHealthRejectsStaleSupervisorAndFailedDynamicAgent(t *testing.T) {
	root := t.TempDir()
	fakeProcess(t, root, 10, "/data/local/bin/voyahtune-new/voyahtune-loader", "100")
	fakeProcess(t, root, 20, "system_server", "200")
	fakeProcess(t, root, 30, "com.example.app", "300")
	testFile(t, filepath.Join(root, "sys/kernel/random/boot_id"), "boot", 0644)
	testFile(t, filepath.Join(root, "uptime"), "110.00 0.00", 0644)
	config := loaderConfig{Agents: []agentConfig{{ID: "windows", Target: "system_server"}}}
	snapshot := loaderStatus{
		Schema: 1, Boot: "boot", Loader: processIdentity{10, "100"}, UpdatedUptime: 90, State: "running",
		Agents: map[string]agentStatus{
			"windows":                {processIdentity{20, "200"}, "system_server", "active", ""},
			"client.com.example.app": {processIdentity{30, "300"}, "com.example.app", "active", ""},
		},
	}
	path := filepath.Join(root, "status.json")
	check := func(accepted bool) {
		t.Helper()
		if err := writeAtomic(path, snapshot); err != nil {
			t.Fatal(err)
		}
		if err := checkHooks(path, root, config); (err == nil) != accepted {
			t.Fatalf("accepted=%t: %v", accepted, err)
		}
	}
	check(false)
	snapshot.UpdatedUptime = 109
	check(true)
	agent := snapshot.Agents["client.com.example.app"]
	agent.State = "failed"
	snapshot.Agents["client.com.example.app"] = agent
	check(false)
	agent.State = "disabled"
	snapshot.Agents["client.com.example.app"] = agent
	check(true)
	snapshot.Loader.StartTicks = "99"
	check(false)
}
