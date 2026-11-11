package main

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"reflect"
	"strconv"
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
		"acc": {UpdatedUptime: 109, Identity: processIdentity{20, "200"}, Target: config.Agents[0].Target, State: "active", UID: 1000, NativeID: "native", RuntimeID: "java", Revision: 7, Modules: []string{"acc"}},
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

func TestInstallationReadinessRequiresCurrentRecordedAcknowledgement(t *testing.T) {
	for _, scenario := range []struct {
		name   string
		mutate func(*loaderStatus)
	}{
		{"heartbeat", func(s *loaderStatus) { s.UpdatedUptime = 90 }},
		{"boot", func(s *loaderStatus) { s.Boot = "old-boot" }},
		{"loader-reused", func(s *loaderStatus) { s.Loader.StartTicks = "99" }},
		{"quiesced", func(s *loaderStatus) { s.State = "quiesced" }},
		{"target-reused", func(s *loaderStatus) {
			status := s.Agents["acc"]
			status.Identity.StartTicks = "199"
			s.Agents["acc"] = status
		}},
		{"stale-ack", func(s *loaderStatus) {
			status := s.Agents["acc"]
			status.UpdatedUptime = 90
			s.Agents["acc"] = status
		}},
		{"future-ack", func(s *loaderStatus) {
			status := s.Agents["acc"]
			status.UpdatedUptime = 120
			s.Agents["acc"] = status
		}},
		{"native", func(s *loaderStatus) {
			status := s.Agents["acc"]
			status.NativeID = "old"
			s.Agents["acc"] = status
		}},
		{"runtime", func(s *loaderStatus) {
			status := s.Agents["acc"]
			status.RuntimeID = "old"
			s.Agents["acc"] = status
		}},
		{"revision", func(s *loaderStatus) {
			status := s.Agents["acc"]
			status.Revision = 0
			s.Agents["acc"] = status
		}},
		{"modules", func(s *loaderStatus) {
			status := s.Agents["acc"]
			status.Modules = []string{"other"}
			s.Agents["acc"] = status
		}},
		{"uid", func(s *loaderStatus) {
			status := s.Agents["acc"]
			status.UID++
			s.Agents["acc"] = status
		}},
		{"error", func(s *loaderStatus) {
			status := s.Agents["acc"]
			status.Reason = "failed"
			s.Agents["acc"] = status
		}},
	} {
		t.Run(scenario.name, func(t *testing.T) {
			path, root, config, snapshot, _ := nativeHealthFixture(t)
			scenario.mutate(&snapshot)
			if err := writeAtomic(path, snapshot); err != nil {
				t.Fatal(err)
			}
			if _, err := checkNativeHooks(path, root, config); err == nil {
				t.Fatal("invalid acknowledgement accepted")
			}
		})
	}
}

func TestInstallationReadinessIgnoresDynamicClientStateAndPIDChanges(t *testing.T) {
	path, root, config, snapshot, _ := nativeHealthFixture(t)
	if err := writeAtomic(path, snapshot); err != nil {
		t.Fatal(err)
	}
	baseline, err := checkNativeHooks(path, root, config)
	if err != nil {
		t.Fatal(err)
	}
	for step, state := range []string{"waiting", "injecting", "active", "failed", "unsupported", "restart-required"} {
		snapshot.UpdatedUptime = int64(110 + step)
		acknowledged := snapshot.Agents["acc"]
		acknowledged.UpdatedUptime = snapshot.UpdatedUptime
		snapshot.Agents["acc"] = acknowledged
		testFile(t, filepath.Join(root, "uptime"), strconv.FormatInt(snapshot.UpdatedUptime, 10)+".00 0.00", 0600)
		for _, identity := range []processIdentity{{}, {PID: 30, StartTicks: "300"}} {
			snapshot.Agents["client.com.example.player"] = agentStatus{Target: "com.example.player", State: state, Identity: identity}
			if err := writeAtomic(path, snapshot); err != nil {
				t.Fatal(err)
			}
			actual, err := checkNativeHooks(path, root, config)
			if err != nil || !reflect.DeepEqual(actual, baseline) {
				t.Fatalf("dynamic state %s changed required readiness: %v %v", state, actual, err)
			}
		}
	}
}

func TestInstallationReadinessChecksWaitingTargets(t *testing.T) {
	path, root, config, snapshot, _ := nativeHealthFixture(t)
	target := nativeAgentConfig{ID: "extra", Target: "com.example.extra"}
	config.Agents = append(config.Agents, target)
	check := func(wantReady bool) {
		t.Helper()
		if err := writeAtomic(path, snapshot); err != nil {
			t.Fatal(err)
		}
		if _, err := checkNativeHooks(path, root, config); (err == nil) != wantReady {
			t.Fatalf("want ready=%t, got %v", wantReady, err)
		}
	}
	check(false)
	snapshot.Agents[target.ID] = agentStatus{State: "waiting", Target: target.Target}
	check(true)
	fakeProcess(t, root, 30, target.Target, "300")
	check(false)
	acknowledged := snapshot.Agents["acc"]
	acknowledged.Target = target.Target
	acknowledged.Identity = processIdentity{30, "300"}
	snapshot.Agents[target.ID] = acknowledged
	testFile(t, filepath.Join(root, "30/status"), "Uid:\t1000\t1000\t1000\t1000\n", 0600)
	check(true)
	fakeProcess(t, root, 30, "com.example.unrelated", "301")
	check(false)
}

func TestInstallationReadinessRecognizesNavigationAudioProcesses(t *testing.T) {
	path, root, config, snapshot, _ := nativeHealthFixture(t)
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
	if _, err := checkNativeHooks(path, root, config); err != nil {
		t.Fatal(err)
	}
	snapshot.Agents["unknown"] = agentStatus{Target: name, State: "waiting"}
	if err := writeAtomic(path, snapshot); err != nil {
		t.Fatal(err)
	}
	if _, err := checkNativeHooks(path, root, config); err == nil {
		t.Fatal("accepted unknown agent")
	}
}

func TestInstallationReadinessRequiresAcknowledgedModules(t *testing.T) {
	for _, state := range []string{"failed", "injecting", "restart-required", "unsupported", "waiting", "disabled"} {
		t.Run(state, func(t *testing.T) {
			path, root, config, snapshot, _ := nativeHealthFixture(t)
			status := snapshot.Agents["acc"]
			status.State = state
			snapshot.Agents["acc"] = status
			if err := writeAtomic(path, snapshot); err != nil {
				t.Fatal(err)
			}
			if _, err := checkNativeHooks(path, root, config); err == nil {
				t.Fatalf("accepted %s required hook", state)
			}
		})
	}
}

func TestInstallationReadinessDoesNotWaitAfterValidAcknowledgement(t *testing.T) {
	path, root, config, snapshot, _ := nativeHealthFixture(t)
	if err := writeAtomic(path, snapshot); err != nil {
		t.Fatal(err)
	}
	if _, err := checkNativeHooks(path, root, config); err != nil {
		t.Fatalf("acknowledged hook delayed by another stability timer: %v", err)
	}
}

func TestInstallationReadinessAllowsConditionallyDisabledHook(t *testing.T) {
	path, root, config, snapshot, _ := nativeHealthFixture(t)
	config.Agents[0].Condition = "apollo"
	status := snapshot.Agents["acc"]
	status.State = "disabled"
	status.NativeID, status.RuntimeID, status.Modules, status.Revision = "", "", nil, 0
	snapshot.Agents["acc"] = status
	if err := writeAtomic(path, snapshot); err != nil {
		t.Fatal(err)
	}
	if _, err := checkNativeHooks(path, root, config); err != nil {
		t.Fatal(err)
	}
}

func TestInstallationReadinessRequiresOneAcknowledgedPlanPerTarget(t *testing.T) {
	path, root, config, snapshot, _ := nativeHealthFixture(t)
	config.Agents = append(config.Agents, nativeAgentConfig{ID: "cabin", Target: config.Agents[0].Target, Modules: []string{"cabin"}})
	status := snapshot.Agents["acc"]
	status.Modules = []string{"acc", "cabin"}
	snapshot.Agents["acc"], snapshot.Agents["cabin"] = status, status
	if err := writeAtomic(path, snapshot); err != nil {
		t.Fatal(err)
	}
	if _, err := checkNativeHooks(path, root, config); err != nil {
		t.Fatal(err)
	}
	status.Revision++
	snapshot.Agents["cabin"] = status
	if err := writeAtomic(path, snapshot); err != nil {
		t.Fatal(err)
	}
	if _, err := checkNativeHooks(path, root, config); err == nil {
		t.Fatal("mixed target plan revisions accepted")
	}
}

func TestInstallationReadinessRejectsAcknowledgementOlderThanCurrentUptime(t *testing.T) {
	path, root, config, snapshot, _ := nativeHealthFixture(t)
	snapshot.UpdatedUptime = 100
	status := snapshot.Agents["acc"]
	status.UpdatedUptime = 90
	snapshot.Agents["acc"] = status
	if err := writeAtomic(path, snapshot); err != nil {
		t.Fatal(err)
	}
	testFile(t, filepath.Join(root, "uptime"), "111.00 0.00", 0600)
	if _, err := checkNativeHooks(path, root, config); err == nil {
		t.Fatal("21-second-old acknowledgement accepted through an 11-second-old heartbeat")
	}
}

func TestInstallationReadinessRetainsIntermediateRequiredTargetChanges(t *testing.T) {
	path, root, config, snapshot, ack := nativeHealthFixture(t)
	if err := os.RemoveAll(filepath.Join(root, "20")); err != nil {
		t.Fatal(err)
	}
	manager := nativeSupervisor{procRoot: root, status: &statusStore{agents: map[string]agentStatus{}}}
	publisher := statusPublisher{path: path, publicPath: filepath.Join(root, "hook-status.v1")}
	publish := func(second int64) hookReadiness {
		t.Helper()
		testFile(t, filepath.Join(root, "uptime"), strconv.FormatInt(second, 10)+".00 0.00", 0600)
		snapshot.UpdatedUptime = second
		snapshot.Agents, snapshot.RequiredChanges = manager.status.snapshot(root)
		if err := publisher.publish(context.Background(), snapshot); err != nil {
			t.Fatal(err)
		}
		value, err := checkNativeHooks(path, root, config)
		if err != nil {
			t.Fatal(err)
		}
		return value
	}
	agent := config.Agents[0]
	manager.report(agent, processIdentity{}, "waiting", "", nil, nil, "")
	first := publish(100)
	fakeProcess(t, root, 20, agent.Target, "200")
	testFile(t, filepath.Join(root, "20/status"), "Uid:\t1000\t1000\t1000\t1000\n", 0600)
	manager.report(agent, processIdentity{20, "200"}, "active", "", &ack, agent.Modules, "")
	publish(105)
	if err := os.RemoveAll(filepath.Join(root, "20")); err != nil {
		t.Fatal(err)
	}
	manager.report(agent, processIdentity{}, "waiting", "", nil, nil, "")
	last := publish(110)
	if reflect.DeepEqual(first, last) {
		t.Fatal("absent-active-absent lost between executor polls")
	}
}

func TestRequiredChangesIgnoreDynamicClientsAndAcknowledgementRefresh(t *testing.T) {
	_, root, config, _, ack := nativeHealthFixture(t)
	manager := nativeSupervisor{procRoot: root, status: &statusStore{agents: map[string]agentStatus{}}}
	agent := config.Agents[0]
	identity := processIdentity{20, "200"}
	manager.report(agent, identity, "active", "", &ack, agent.Modules, "")
	_, first := manager.status.snapshot(root)
	testFile(t, filepath.Join(root, "uptime"), "115.00 0.00", 0600)
	manager.report(agent, identity, "active", "", &ack, agent.Modules, "")
	dynamic := nativeAgentConfig{ID: "client.com.example.player", Target: "com.example.player", Dynamic: true}
	for _, state := range []string{"injecting", "active", "failed", "waiting"} {
		manager.report(dynamic, processIdentity{30, "300"}, state, "", nil, nil, "")
		_, changes := manager.status.snapshot(root)
		if changes != first {
			t.Fatalf("dynamic update changed required counter: %d != %d", changes, first)
		}
	}
	ack.Revision++
	manager.report(agent, identity, "active", "", &ack, agent.Modules, "")
	_, changed := manager.status.snapshot(root)
	if changed <= first {
		t.Fatal("required module plan change was not retained")
	}
}
