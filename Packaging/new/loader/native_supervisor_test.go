package main

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"reflect"
	"testing"

	"voyahtune/unified-loader/agentwire"
	"voyahtune/unified-loader/lifecycle"
	"voyahtune/unified-loader/resident"
)

type residentSpy struct {
	beforeEnsure func()
	targets      []agentwire.Target
	plans        []agentwire.Plan
}

func (spy *residentSpy) Ensure(_ context.Context, target agentwire.Target, plan agentwire.Plan) (agentwire.Status, error) {
	if spy.beforeEnsure != nil {
		spy.beforeEnsure()
	}
	spy.targets = append(spy.targets, target)
	spy.plans = append(spy.plans, plan)
	status := agentwire.Status{Schema: 1, Target: target, State: "ready", Revision: int64(len(spy.plans)), Modules: map[string]string{}}
	for _, module := range plan.Modules {
		status.Modules[module] = "active"
	}
	return status, nil
}
func (spy *residentSpy) Quiesce(context.Context) error { return nil }
func (spy *residentSpy) Snapshot() lifecycle.State     { return lifecycle.State{Phase: lifecycle.Running} }
func (spy *residentSpy) Sweep() error                  { return nil }

func supervisorFixture(t *testing.T, agents []nativeAgentConfig) (*nativeSupervisor, *residentSpy) {
	t.Helper()
	root := t.TempDir()
	testFile(t, filepath.Join(root, "sys/kernel/random/boot_id"), "boot", 0600)
	testFile(t, filepath.Join(root, "uptime"), "100 0", 0600)
	spy := new(residentSpy)
	manager := &nativeSupervisor{
		stateDirectory: t.TempDir(),
		procRoot:       root,
		boot:           "boot",
		engine:         spy,
		status:         &statusStore{agents: map[string]agentStatus{}},
		agents:         map[string]nativeAgentConfig{},
		wanted:         map[string]bool{},
		apolloPath:     filepath.Join(root, "apollo.v1"),
		config:         nativeLoaderConfig{Code: nativeCode{NativeID: "native", RuntimeID: "java"}},
		readSetting:    func(context.Context, string) string { return "off" },
	}
	for _, agent := range agents {
		manager.agents[agent.ID] = agent
		manager.wanted[agent.ID] = true
	}
	return manager, spy
}
func supervisorTarget(t *testing.T, manager *nativeSupervisor, name, ticks string, class byte, machine uint16, uid string) {
	t.Helper()
	fakeProcess(t, manager.procRoot, 42, name, ticks)
	testFile(t, filepath.Join(manager.procRoot, "42/status"), "Uid:\t"+uid+"\t"+uid+"\t"+uid+"\t"+uid+"\n", 0600)
	testTargetELF(t, manager.procRoot, 42, class, machine)
}

func TestNativeSupervisorRequestsWholeSharedProcessPlan(t *testing.T) {
	name := "com.qinggan.app.vehiclesetting"
	agents := []nativeAgentConfig{{ID: "drive-reset", Target: name, Modules: []string{"drive-reset"}, TimeoutSeconds: 35}, {ID: "apollo", Target: name, Modules: []string{"apollo"}, Condition: "apollo", TimeoutSeconds: 35}}
	manager, spy := supervisorFixture(t, agents)
	supervisorTarget(t, manager, name, "100", 2, 183, "1000")
	testFile(t, manager.apolloPath, "v=1\nboot=boot\nenabled=1\n", 0600)
	manager.cycle(context.Background(), name)
	if len(spy.plans) != 1 || !reflect.DeepEqual(spy.plans[0].Modules, []string{"apollo", "drive-reset"}) || spy.plans[0].Configuration["apollo.bootId"] != "boot" {
		t.Fatal(spy.plans)
	}
	for _, agent := range agents {
		status := manager.status.agents[agent.ID]
		if status.State != "active" || status.NativeID != "native" || status.Revision != 1 || len(status.Modules) != 2 {
			t.Fatal(status)
		}
	}
	manager.wanted["apollo"] = false
	manager.cycle(context.Background(), name)
	if !reflect.DeepEqual(spy.plans[1].Modules, []string{"drive-reset"}) || manager.status.agents["apollo"].State != "disabled" {
		t.Fatal(spy.plans, manager.status.agents)
	}
}

func TestNativeSupervisorRejectsNonARMAndSystemDynamicClient(t *testing.T) {
	for _, scenario := range []struct {
		class   byte
		machine uint16
		dynamic bool
	}{{2, 62, false}, {2, 183, true}} {
		agent := nativeAgentConfig{ID: "client.com.example.app", Target: "com.example.app", Modules: []string{"client.com.example.app"}, Dynamic: scenario.dynamic}
		manager, spy := supervisorFixture(t, []nativeAgentConfig{agent})
		supervisorTarget(t, manager, agent.Target, "100", scenario.class, scenario.machine, "1000")
		manager.cycle(context.Background(), agent.Target)
		if len(spy.plans) != 0 || manager.status.agents[agent.ID].State != "unsupported" {
			t.Fatal(spy.plans, manager.status.agents)
		}
	}
}

func TestNativeSupervisorKeepsKeyboardChoiceUntilNewProcess(t *testing.T) {
	name := "com.qinggan.app.qgime"
	agents := []nativeAgentConfig{{ID: "keyboard-en", Target: name, Modules: []string{"keyboard-en"}, Condition: "keyboard-en"}, {ID: "keyboard-ru", Target: name, Modules: []string{"keyboard-ru"}, Condition: "keyboard-ru"}}
	manager, spy := supervisorFixture(t, agents)
	mode := "ru"
	manager.readSetting = func(context.Context, string) string { return mode }
	supervisorTarget(t, manager, name, "100", 2, 183, "1000")
	manager.cycle(context.Background(), name)
	mode = "en"
	manager.cycle(context.Background(), name)
	if !reflect.DeepEqual(spy.plans[0].Modules, []string{"keyboard-ru"}) || !reflect.DeepEqual(spy.plans[1].Modules, []string{"keyboard-ru"}) {
		t.Fatal(spy.plans)
	}
	supervisorTarget(t, manager, name, "101", 2, 183, "1000")
	manager.cycle(context.Background(), name)
	if !reflect.DeepEqual(spy.plans[2].Modules, []string{"keyboard-en"}) {
		t.Fatal(spy.plans)
	}
	if _, err := os.Stat(filepath.Join(manager.stateDirectory, "keyboard-choice.json")); err != nil {
		t.Fatal(err)
	}
}

func TestNativeSupervisorKeepsConfirmedStateDuringResidentRefresh(t *testing.T) {
	agent := nativeAgentConfig{ID: "phone", Target: "com.qinggan.bluetoothphone", Modules: []string{"phone"}, TimeoutSeconds: 35}
	manager, spy := supervisorFixture(t, []nativeAgentConfig{agent})
	supervisorTarget(t, manager, agent.Target, "100", 2, 183, "1000")
	manager.cycle(context.Background(), agent.Target)
	spy.beforeEnsure = func() {
		status := manager.status.agents[agent.ID]
		if status.State != "active" || status.NativeID != "native" || status.Revision != 1 {
			t.Fatal("Confirmed live state was discarded before refresh", status)
		}
	}
	manager.cycle(context.Background(), agent.Target)
}

func TestNativeSupervisorAdmitsARM32OEMProcess(t *testing.T) {
	name := "com.qinggan.canbus.service"
	agent := nativeAgentConfig{ID: "acc", Target: name, Modules: []string{"acc"}, TimeoutSeconds: 35}
	manager, spy := supervisorFixture(t, []nativeAgentConfig{agent})
	supervisorTarget(t, manager, name, "100", 1, 40, "1000")
	manager.cycle(context.Background(), name)
	if len(spy.plans) != 1 || manager.status.agents["acc"].State != "active" {
		t.Fatal(spy.plans, manager.status.agents)
	}
}

func TestSafePointTimeoutDoesNotConsumeTheInjectionBudget(t *testing.T) {
	agent := nativeAgentConfig{ID: "windows", Target: "system_server", Modules: []string{"windows"}, MaxAttempts: 1, TimeoutSeconds: 35}
	manager, _ := supervisorFixture(t, []nativeAgentConfig{agent})
	manager.updateBlock = filepath.Join(t.TempDir(), "update.block")
	manager.migrationBoot = filepath.Join(t.TempDir(), "migration.boot")
	supervisorTarget(t, manager, agent.Target, "100", 2, 183, "1000")
	target := agentwire.Target{BootID: manager.boot, PID: 42, UID: 1000, StartTicks: "100", NativeID: "native", RuntimeID: "java"}
	if err := manager.admit(target); err != nil {
		t.Fatal(err)
	}
	failure := &resident.SafePointNotReadyError{Reason: "native attach: wait-safe-syscall: Connection timed out"}
	if !manager.recordAttemptResult(agent.Target, processIdentity{42, "100"}, false, failure) {
		t.Fatal("safe timeout exhausted injection budget")
	}
	var record attemptRecord
	if err := readJSON(manager.attemptPath(agent.Target), &record); err != nil {
		t.Fatal(err)
	}
	if record.Attempts != 0 || record.State != "waiting" || record.NextAttempt <= 100 {
		t.Fatal(record)
	}
	var waiting *resident.SafePointNotReadyError
	if err := manager.admit(target); !errors.As(err, &waiting) {
		t.Fatal("backoff did not remain pending", err)
	}
	if !manager.recordAttemptResult(agent.Target, processIdentity{42, "100"}, false, waiting) {
		t.Fatal("pending retry became failed")
	}
	testFile(t, filepath.Join(manager.procRoot, "uptime"), "102 0", 0600)
	if err := manager.admit(target); err != nil {
		t.Fatal("retry denied", err)
	}
	manager.recordAttemptResult(agent.Target, processIdentity{42, "100"}, true, nil)
	if err := readJSON(manager.attemptPath(agent.Target), &record); err != nil {
		t.Fatal(err)
	}
	if record.State != "active" || record.Attempts != 1 {
		t.Fatal(record)
	}
}

func TestSafePointRetriesAreBoundedAndRealFailuresRemainFailed(t *testing.T) {
	identity := processIdentity{42, "100"}
	record := attemptRecord{Boot: "boot", Identity: identity, State: "injecting", Attempts: 1, LastAttach: 100}
	if !record.deferSafePoint(103) {
		t.Fatal(record)
	}
	deadline := record.SafePointWaitUntil
	for now := int64(110); now < deadline; now += 10 {
		record.State = "injecting"
		record.Attempts++
		record.LastAttach = now
		if !record.deferSafePoint(now + 1) {
			t.Fatal(record)
		}
		if record.NextAttempt > deadline {
			t.Fatal("retry moved beyond deadline", record)
		}
	}
	record.State = "injecting"
	record.Attempts++
	if record.deferSafePoint(deadline) {
		t.Fatal("safe point retries never expire", record)
	}
	if record.canAttempt(deadline+30, 1) {
		t.Fatal("exhausted budget was reset", record)
	}
}

func TestNativeStartupFailureStillConsumesTheInjectionBudget(t *testing.T) {
	agent := nativeAgentConfig{ID: "windows", Target: "system_server", Modules: []string{"windows"}, MaxAttempts: 1}
	manager, _ := supervisorFixture(t, []nativeAgentConfig{agent})
	manager.updateBlock = filepath.Join(t.TempDir(), "update.block")
	manager.migrationBoot = filepath.Join(t.TempDir(), "migration.boot")
	supervisorTarget(t, manager, agent.Target, "100", 2, 183, "1000")
	target := agentwire.Target{BootID: manager.boot, PID: 42, UID: 1000, StartTicks: "100", NativeID: "native", RuntimeID: "java"}
	if err := manager.admit(target); err != nil {
		t.Fatal(err)
	}
	if manager.recordAttemptResult(agent.Target, processIdentity{42, "100"}, false, errors.New("module failed after native startup")) {
		t.Fatal("real failure became pending")
	}
	testFile(t, filepath.Join(manager.procRoot, "uptime"), "110 0", 0600)
	if err := manager.admit(target); err == nil {
		t.Fatal("failed startup was injected again")
	}
}
