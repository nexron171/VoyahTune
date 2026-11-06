package main

import (
	"context"
	"errors"
	"fmt"
	"maps"
	"os"
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
			err := checkNativeHookAcks(path, root, config, func(context.Context, agentwire.Target) (agentwire.Status, error) { return ack, nil })
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
			if err := checkNativeHookAcks(path, root, config, func(context.Context, agentwire.Target) (agentwire.Status, error) { return ack, nil }); err == nil {
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
			if err := checkNativeHookAcks(path, root, config, func(context.Context, agentwire.Target) (agentwire.Status, error) { return ack, nil }); err == nil {
				t.Fatal("Invalid live acknowledgement accepted")
			}
		})
	}
	path, root, config, snapshot, _ := nativeHealthFixture(t)
	if err := writeAtomic(path, snapshot); err != nil {
		t.Fatal(err)
	}
	err := checkNativeHookAcks(path, root, config, func(context.Context, agentwire.Target) (agentwire.Status, error) {
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

func TestInstallationReadinessAfterHookErrorsAndRestarts(t *testing.T) {
	path, root, config, snapshot, _ := nativeHealthFixture(t)
	publisher := statusPublisher{path: path, publicPath: filepath.Join(root, "hook-status.v1")}
	publish := func(second int64, wantReady bool) {
		t.Helper()
		snapshot.UpdatedUptime = second
		testFile(t, filepath.Join(root, "uptime"), fmt.Sprintf("%d.00 0.00", second), 0600)
		if err := publisher.publish(context.Background(), snapshot); err != nil {
			t.Fatal(err)
		}
		err := checkNativeHooks(path, root, config)
		if (err == nil) != wantReady {
			t.Fatalf("second %d: want ready=%t, got %v", second, wantReady, err)
		}
	}
	changeAgent := func(state string, identity processIdentity) {
		snapshot.Agents = maps.Clone(snapshot.Agents)
		agent := snapshot.Agents["acc"]
		agent.State, agent.Identity, agent.Reason = state, identity, "previous attach attempt failed"
		snapshot.Agents["acc"] = agent
	}
	identity := snapshot.Agents["acc"].Identity
	changeAgent("failed", identity)
	publish(100, false)
	publish(109, false)
	changeAgent("injecting", identity)
	publish(110, true)

	fakeProcess(t, root, 20, config.Agents[0].Target, "300")
	if err := checkNativeHooks(path, root, config); err == nil {
		t.Fatal("accepted recycled agent PID")
	}
	changeAgent("failed", processIdentity{20, "300"})
	publish(111, false)
	publish(120, false)
	publish(121, true)

	fakeProcess(t, root, 10, "/data/local/bin/voyahtune-new/voyahtune-loader", "400")
	if err := checkNativeHooks(path, root, config); err == nil {
		t.Fatal("accepted recycled loader PID")
	}
	snapshot.Loader.StartTicks = "400"
	publish(122, false)
	publish(132, true)
	publish(160, false)
	for restart := 1; restart <= 3; restart++ {
		second := int64(160 + restart*8)
		previousPID := snapshot.Agents["acc"].Identity.PID
		if err := os.Remove(filepath.Join(root, fmt.Sprint(previousPID), "cmdline")); err != nil {
			t.Fatal(err)
		}
		identity := processIdentity{PID: 30 + restart, StartTicks: fmt.Sprint(second * 100)}
		fakeProcess(t, root, identity.PID, config.Agents[0].Target, identity.StartTicks)
		changeAgent("active", identity)
		publish(second, false)
	}
	publish(194, true)
}

func TestInstallationReadinessChecksWaitingTargets(t *testing.T) {
	path, root, config, snapshot, _ := nativeHealthFixture(t)
	snapshot.StableSinceUptime = 99
	target := nativeAgentConfig{ID: "extra", Target: "com.example.extra"}
	config.Agents = append(config.Agents, target)
	check := func(wantReady bool) {
		t.Helper()
		if err := writeAtomic(path, snapshot); err != nil {
			t.Fatal(err)
		}
		if err := checkNativeHooks(path, root, config); (err == nil) != wantReady {
			t.Fatalf("want ready=%t, got %v", wantReady, err)
		}
	}
	check(false)
	snapshot.Agents[target.ID] = agentStatus{State: "waiting", Target: target.Target}
	check(true)
	fakeProcess(t, root, 30, target.Target, "300")
	check(false)
	snapshot.Agents[target.ID] = agentStatus{State: "active", Target: target.Target, Identity: processIdentity{30, "300"}}
	check(true)
	fakeProcess(t, root, 30, "com.example.unrelated", "301")
	check(false)
}

func TestInstallationReadinessRecognizesNavigationAudioProcesses(t *testing.T) {
	path, root, config, snapshot, _ := nativeHealthFixture(t)
	snapshot.StableSinceUptime = 99
	name := "com.example.maps"
	fakeProcess(t, root, 30, name+":audio", "300")
	agents := navigationAudioProcesses(root, navigationAudioAgents(name))
	for _, agent := range agents {
		identity, err := findProcess(root, agent.Target)
		if err != nil {
			t.Fatal(err)
		}
		snapshot.Agents[agent.ID] = agentStatus{Target: agent.Target, Identity: identity, State: "waiting"}
	}
	if err := writeAtomic(path, snapshot); err != nil {
		t.Fatal(err)
	}
	if err := checkNativeHooks(path, root, config); err != nil {
		t.Fatal(err)
	}
	snapshot.Agents["unknown"] = agentStatus{Target: name, State: "waiting"}
	if err := writeAtomic(path, snapshot); err != nil {
		t.Fatal(err)
	}
	if err := checkNativeHooks(path, root, config); err == nil {
		t.Fatal("accepted unknown agent")
	}
}
