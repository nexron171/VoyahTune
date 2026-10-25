package main

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
	"time"
)

func testFile(t *testing.T, path, body string, mode os.FileMode) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(body), mode); err != nil {
		t.Fatal(err)
	}
}

func fakeProcess(t *testing.T, root string, pid int, name, ticks string) {
	t.Helper()
	directory := filepath.Join(root, fmt.Sprint(pid))
	testFile(t, filepath.Join(directory, "cmdline"), name+"\x00argument\x00", 0644)
	fields := append([]string{"S"}, strings.Fields(strings.Repeat("0 ", 18))...)
	fields = append(fields, ticks)
	testFile(t, filepath.Join(directory, "stat"), fmt.Sprintf("%d (name with ) spaces) %s", pid, strings.Join(fields, " ")), 0644)
}

func TestIdentityRejectsSecondaryProcessAndPIDReuse(t *testing.T) {
	root := t.TempDir()
	fakeProcess(t, root, 10, "com.qinggan.app.launcher:worker", "100")
	fakeProcess(t, root, 20, "com.qinggan.app.launcher", "200")
	identity, err := findProcess(root, "com.qinggan.app.launcher")
	if err != nil || identity != (processIdentity{20, "200"}) {
		t.Fatalf("%+v %v", identity, err)
	}
	fakeProcess(t, root, 20, "com.qinggan.app.launcher", "201")
	current, err := readIdentity(root, 20, "com.qinggan.app.launcher")
	if err != nil || current == identity {
		t.Fatal("reused PID accepted")
	}
}

func TestPriorityReleaseAndTimeout(t *testing.T) {
	ready := make(chan struct{})
	completed := make(chan bool, 1)
	go func() { completed <- waitPriority(context.Background(), ready, time.Second) }()
	select {
	case <-completed:
		t.Fatal("ordinary agents started before ACC")
	case <-time.After(10 * time.Millisecond):
	}
	close(ready)
	if !<-completed {
		t.Fatal("ready ACC did not release other agents")
	}
	if !waitPriority(context.Background(), make(chan struct{}), time.Millisecond) {
		t.Fatal("absent ACC starved other agents")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if waitPriority(ctx, make(chan struct{}), time.Second) {
		t.Fatal("cancelled loader started workers")
	}
}

func TestPersistentAttemptBudgetAndCrashCircuit(t *testing.T) {
	identity := processIdentity{42, "100"}
	record := observeTarget(attemptRecord{}, "boot", identity, "hash", 100, 2)
	if !record.canAttempt(100, 1) {
		t.Fatal("first target rejected")
	}
	record.Attempts = 1
	record.State = "injecting"
	record.LastAttach = 100
	encoded, _ := json.Marshal(record)
	var restarted attemptRecord
	if err := json.Unmarshal(encoded, &restarted); err != nil {
		t.Fatal(err)
	}
	if restarted.canAttempt(101, 1) {
		t.Fatal("supervisor restart repeated uncertain injection")
	}
	restarted.State = "active"
	if observeTarget(restarted, "boot", identity, "new", 102, 2).State != "restart-required" {
		t.Fatal("replaced an eternalized script in a live process")
	}
	for tick := 101; tick < 103; tick++ {
		restarted = observeTarget(restarted, "boot", processIdentity{42, fmt.Sprint(tick)}, "hash", int64(tick), 2)
		restarted.LastAttach = int64(tick)
	}
	if !restarted.Blocked {
		t.Fatal("rapid crash circuit remained open")
	}
	if observeTarget(restarted, "new-boot", identity, "hash", 1, 2).Blocked {
		t.Fatal("new boot inherited circuit")
	}
}

func TestHealthSuspendGraceExpiresOnce(t *testing.T) {
	identity := processIdentity{10, "100"}
	var observation healthObservation
	if observation.expired(identity, 100, 100) {
		t.Fatal("fresh pulse")
	}
	if observation.expired(identity, 200, 100) {
		t.Fatal("no resume grace")
	}
	if observation.expired(identity, 214, 100) {
		t.Fatal("short grace")
	}
	if !observation.expired(identity, 215, 100) {
		t.Fatal("expired pulse retained")
	}
	if !observation.expired(identity, 250, 100) {
		t.Fatal("repeated grace hid a dead agent")
	}
	if observation.expired(identity, 251, 251) {
		t.Fatal("recovered pulse rejected")
	}
}

func TestKeyboardChoicePersistsUntilProcessRestart(t *testing.T) {
	path := filepath.Join(t.TempDir(), "keyboard.json")
	identity := processIdentity{10, "100"}
	mode, err := keyboardMode(path, "boot", identity, func() string { return "ru" })
	if err != nil || mode != "ru" {
		t.Fatal(mode, err)
	}
	mode, err = keyboardMode(path, "boot", identity, func() string { return "en" })
	if err != nil || mode != "ru" {
		t.Fatal("two keyboard agents could attach to one process")
	}
	mode, err = keyboardMode(path, "boot", processIdentity{10, "101"}, func() string { return "en" })
	if err != nil || mode != "en" {
		t.Fatal(mode, err)
	}
}

func TestApolloFlagAndDynamicClientIsolation(t *testing.T) {
	if !apolloEnabled("v=1\nboot=b\nenabled=1\n", "b") {
		t.Fatal("valid Apollo flag")
	}
	for _, invalid := range []string{"v=1\nboot=old\nenabled=1", "v=\nv=1\nboot=b\nenabled=1", "v=1\nboot=b\nenabled=0"} {
		if apolloEnabled(invalid, "b") {
			t.Fatal("invalid flag accepted", invalid)
		}
	}
	agents := dynamicAgents("com.qinggan.cluster,com.qinggan.canbus.service,../bad,system_server,com.example.app", "com.yango.maps.android")
	if len(agents) != 2 {
		t.Fatal(agents)
	}
	for _, agent := range agents {
		if agent.Target != "com.example.app" && agent.Target != "com.yango.maps.android" {
			t.Fatal(agent)
		}
	}
}

func TestConfigRejectsUnsafeAndUnboundedRuntime(t *testing.T) {
	directory := t.TempDir()
	testFile(t, filepath.Join(directory, "frida-inject"), "executable", 0755)
	testFile(t, filepath.Join(directory, "frida-inject-arm"), "executable", 0755)
	testFile(t, filepath.Join(directory, "agent.js"), "Java.perform(function(){});", 0644)
	agent := agentConfig{ID: "acc", Target: "com.qinggan.canbus.service", Script: "agent.js", Ready: []string{"ready"}, Lifetime: "eternalized", Priority: true, MaxAttempts: 1, TimeoutSeconds: 30}
	config := loaderConfig{Schema: 1, PrioritySeconds: 45, Agents: []agentConfig{agent}}
	if err := writeAtomic(filepath.Join(directory, "agents.json"), config); err != nil {
		t.Fatal(err)
	}
	if _, err := loadConfig(directory); err != nil {
		t.Fatal(err)
	}
	for _, mutate := range []func(*agentConfig){func(a *agentConfig) { a.Script = "../agent.js" }, func(a *agentConfig) { a.Target = "system_server"; a.Lifetime = "attached" }, func(a *agentConfig) { a.Ready = nil }, func(a *agentConfig) { a.TimeoutSeconds = 0 }} {
		value := agent
		mutate(&value)
		config.Agents = []agentConfig{value}
		if err := writeAtomic(filepath.Join(directory, "agents.json"), config); err != nil {
			t.Fatal(err)
		}
		if _, err := loadConfig(directory); err == nil {
			t.Fatal("invalid config accepted", value)
		}
	}
}

func TestPublicStatusKeepsExistingContract(t *testing.T) {
	snapshot := loaderStatus{State: "running", Loader: processIdentity{12, "3"}, Agents: map[string]agentStatus{"windows": {State: "unsupported"}}}
	value := publicStatus(snapshot)
	if len(strings.Split(value, ";")) != 10 || !strings.Contains(value, "vd-bypass=failed:0") || !strings.Contains(value, "keyboard-ru=waiting:0") {
		t.Fatal(value)
	}
}

func TestWindowAgentV2StatusAndProcessIdentity(t *testing.T) {
	directory := t.TempDir()
	manager := supervisor{healthDirectory: directory, boot: "boot"}
	path := filepath.Join(directory, "vd_hooks/status.v1")
	testFile(t, path, "v2:boot:42:100|3.22.0-v1|active|geometry\n", 0600)
	state, reason := manager.windowState(processIdentity{42, "100"})
	if state != "active" || reason != "geometry" {
		t.Fatal(state, reason)
	}
	state, _ = manager.windowState(processIdentity{42, "101"})
	if state != "injecting" {
		t.Fatal("reused PID accepted stale status")
	}
	testFile(t, path, "v2:boot:42:100|3.22.0-v1|failed|clean.layout.attach\n", 0600)
	state, reason = manager.windowState(processIdentity{42, "100"})
	if state != "failed" || reason != "clean.layout.attach" {
		t.Fatal(state, reason)
	}
}

func TestInjectorReadinessExitAndChangedTarget(t *testing.T) {
	for _, scenario := range []struct {
		name, body, lifetime string
		changed, success     bool
	}{
		{"eternalized", "echo '[test] ready'; exit 0", "eternalized", false, true},
		{"error-after-ready", "echo '[test] ready'; exit 7", "eternalized", false, false},
		{"script-error", "echo '{\"type\":\"error\",\"description\":\"failed Java callback\"}'; echo '[test] ready'; exit 0", "eternalized", false, false},
		{"timeout-after-ready", "echo '[test] ready'; exec sleep 10", "eternalized", false, false},
		{"no-ready", "exit 0", "eternalized", false, false},
		{"failed-after-ready", "echo '[test] ready'; echo 'hook failed'; exit 0", "eternalized", false, false},
		{"attached-exit", "echo '[test] ready'; exit 0", "attached", false, false},
		{"pid-reuse", "echo '[test] ready'; exit 0", "eternalized", true, false},
	} {
		t.Run(scenario.name, func(t *testing.T) {
			directory := t.TempDir()
			testFile(t, filepath.Join(directory, "frida-inject"), "#!/bin/sh\n"+scenario.body+"\n", 0755)
			identity := processIdentity{42, "100"}
			testTargetELF(t, directory, 42, 2, 183)
			reads := 0
			runner := injector{Directory: directory, StateDirectory: directory, ProcRoot: directory, ReadIdentity: func(_ string, pid int, name string) (processIdentity, error) {
				if name == "target" {
					reads++
					if scenario.changed && reads > 1 {
						return processIdentity{42, "101"}, nil
					}
					return identity, nil
				}
				return processIdentity{pid, "child"}, nil
			}}
			agent := agentConfig{ID: "test", Target: "target", Script: "test.js", Ready: []string{"[test] ready"}, Lifetime: scenario.lifetime, TimeoutSeconds: 1}
			err := runner.run(context.Background(), agent, identity, nil, func(processIdentity) error { return nil }, func() error { return nil })
			if (err == nil) != scenario.success {
				t.Fatalf("success=%v error=%v", scenario.success, err)
			}
		})
	}
}

func TestInjectorReportsReadyBeforeWrapperExit(t *testing.T) {
	directory := t.TempDir()
	testFile(t, filepath.Join(directory, "frida-inject"), "#!/bin/sh\necho '[test] ready'\nexec sleep 0.4\n", 0755)
	identity := processIdentity{42, "100"}
	testTargetELF(t, directory, 42, 2, 183)
	runner := injector{Directory: directory, StateDirectory: directory, ProcRoot: directory, ReadIdentity: func(_ string, pid int, _ string) (processIdentity, error) {
		if pid == 42 {
			return identity, nil
		}
		return processIdentity{pid, "child"}, nil
	}}
	ready := make(chan struct{})
	finished := make(chan error, 1)
	go func() {
		finished <- runner.run(context.Background(), agentConfig{ID: "test", Target: "target", Script: "test.js", Ready: []string{"[test] ready"}, Lifetime: "eternalized", TimeoutSeconds: 2}, identity, nil, func(processIdentity) error { return nil }, func() error { close(ready); return nil })
	}()
	select {
	case <-ready:
	case <-finished:
		t.Fatal("ACC readiness waited for wrapper exit")
	case <-time.After(time.Second):
		t.Fatal("no readiness")
	}
	if err := <-finished; err != nil {
		t.Fatal(err)
	}
}

func TestInjectorTimeoutStopsDescendants(t *testing.T) {
	directory := t.TempDir()
	pulse := filepath.Join(directory, "pulse")
	child := filepath.Join(directory, "child")
	testFile(t, child, fmt.Sprintf("#!/bin/sh\ntrap '' TERM\nwhile :; do printf . >> %q; sleep 0.05; done\n", pulse), 0755)
	testFile(t, filepath.Join(directory, "frida-inject"), fmt.Sprintf("#!/bin/sh\n%q &\nwait\n", child), 0755)
	identity := processIdentity{42, "100"}
	testTargetELF(t, directory, 42, 2, 183)
	runner := injector{Directory: directory, StateDirectory: directory, ProcRoot: directory, ReadIdentity: func(_ string, pid int, _ string) (processIdentity, error) {
		if pid == 42 {
			return identity, nil
		}
		return processIdentity{pid, "child"}, nil
	}}
	agent := agentConfig{ID: "test", Target: "target", Script: "test.js", Ready: []string{"[test] ready"}, Lifetime: "eternalized", TimeoutSeconds: 1}
	err := runner.run(context.Background(), agent, identity, nil, func(child processIdentity) error {
		t.Cleanup(func() { syscall.Kill(-child.PID, syscall.SIGKILL) })
		return nil
	}, func() error { return nil })
	if err == nil {
		t.Fatal("unready child reported success")
	}
	before, err := os.ReadFile(pulse)
	if err != nil || len(before) == 0 {
		t.Fatalf("child did not run: %v", err)
	}
	time.Sleep(200 * time.Millisecond)
	after, err := os.ReadFile(pulse)
	if err != nil || string(after) != string(before) {
		t.Fatalf("child survived timeout: %v", err)
	}
}

func TestTargetLockSurvivesSupervisorDescriptorClose(t *testing.T) {
	path := filepath.Join(t.TempDir(), "target.lock")
	lock, err := lockFile(path)
	if err != nil {
		t.Fatal(err)
	}
	command := exec.Command(os.Args[0], "-test.run=^TestInheritedLockHelper$")
	command.Env = append(os.Environ(), "VOYAH_LOCK_HELPER=1")
	command.ExtraFiles = []*os.File{lock}
	if err := command.Start(); err != nil {
		t.Fatal(err)
	}
	lock.Close()
	if acquired, err := lockFile(path); !errors.Is(err, syscall.EWOULDBLOCK) {
		if acquired != nil {
			unlockFile(acquired)
		}
		t.Fatalf("surviving injector lost lock: %v", err)
	}
	command.Process.Kill()
	command.Wait()
	acquired, err := lockFile(path)
	if err != nil {
		t.Fatal(err)
	}
	unlockFile(acquired)
}

func TestInheritedLockHelper(t *testing.T) {
	if os.Getenv("VOYAH_LOCK_HELPER") == "1" {
		time.Sleep(10 * time.Second)
		os.Exit(0)
	}
}

func testTargetELF(t *testing.T, root string, pid int, class byte, machine uint16) {
	t.Helper()
	header := make([]byte, 20)
	copy(header, "\x7fELF")
	header[4], header[5] = class, 1
	binary.LittleEndian.PutUint16(header[18:20], machine)
	testFile(t, filepath.Join(root, fmt.Sprint(pid), "exe"), string(header), 0755)
}

func TestInjectorChoosesTargetArchitectureAndRejectsUnknownELF(t *testing.T) {
	for _, scenario := range []struct {
		name     string
		class    byte
		machine  uint16
		expected string
	}{
		{"arm32", 1, 40, "frida-inject-arm"},
		{"arm64", 2, 183, "frida-inject"},
		{"x86", 1, 3, ""},
		{"x64", 2, 62, ""},
		{"inconsistent", 2, 40, ""},
	} {
		t.Run(scenario.name, func(t *testing.T) {
			directory := t.TempDir()
			testTargetELF(t, directory, 42, scenario.class, scenario.machine)
			actual, err := injectorExecutable(directory, directory, 42)
			if scenario.expected == "" {
				if err == nil {
					t.Fatal("unsupported architecture accepted")
				}
			} else if err != nil || actual != filepath.Join(directory, scenario.expected) {
				t.Fatal(actual, err)
			}
		})
	}
	directory := t.TempDir()
	if _, err := injectorExecutable(directory, directory, 42); err == nil {
		t.Fatal("missing executable accepted")
	}
	testFile(t, filepath.Join(directory, "42/exe"), "short", 0755)
	if _, err := injectorExecutable(directory, directory, 42); err == nil {
		t.Fatal("truncated ELF accepted")
	}
}

func TestFailedAttemptRetainsCauseAcrossSupervisorRestart(t *testing.T) {
	record := attemptRecord{
		Boot: "boot", Identity: processIdentity{42, "100"}, State: "failed",
		Attempts: 1, LastError: "agent readiness timeout",
	}
	encoded, err := json.Marshal(record)
	if err != nil {
		t.Fatal(err)
	}
	var restored attemptRecord
	if err := json.Unmarshal(encoded, &restored); err != nil {
		t.Fatal(err)
	}
	if restored.canAttempt(100, 1) || restored.failureReason() != record.LastError {
		t.Fatal("lost failure cause after supervisor restart", restored)
	}
	next := observeTarget(restored, "boot", processIdentity{43, "101"}, "hash", 200, 2)
	if next.LastError != "" {
		t.Fatal("new target inherited a stale error")
	}
}
