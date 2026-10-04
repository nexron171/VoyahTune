package main

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"testing"
	"time"
)

func writeTestFile(t *testing.T, path, body string, mode os.FileMode) {
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
	dir := filepath.Join(root, strconv.Itoa(pid))
	writeTestFile(t, filepath.Join(dir, "cmdline"), name+"\x00unused\x00", 0644)
	fields := append([]string{"S"}, strings.Fields(strings.Repeat("0 ", 18))...)
	fields = append(fields, ticks)
	writeTestFile(t, filepath.Join(dir, "stat"), fmt.Sprintf("%d (name with ) spaces) %s\n", pid, strings.Join(fields, " ")), 0644)
}
func TestProcessIdentityExactNameAndPIDReuse(t *testing.T) {
	root := t.TempDir()
	fakeProcess(t, root, 11, "com.qinggan.app.launcher:worker", "100")
	fakeProcess(t, root, 22, "com.qinggan.app.launcher", "200")
	got, err := findProcess(root, "com.qinggan.app.launcher")
	if err != nil || got != (processIdentity{22, "200"}) {
		t.Fatalf("got %+v %v", got, err)
	}
	fakeProcess(t, root, 22, "com.qinggan.app.launcher", "300")
	next, err := readIdentity(root, 22, "com.qinggan.app.launcher")
	if err != nil || next == got {
		t.Fatalf("PID reuse was not detected: %+v %v", next, err)
	}
	fakeProcess(t, root, 1, "/system/bin/system_server", "99")
	got, err = findProcess(root, "system_server")
	if err != nil || got.PID != 1 {
		t.Fatalf("basename: %+v %v", got, err)
	}
}
func TestDelayedTargetDoesNotSpendRetryBudget(t *testing.T) {
	var retry retryState
	for i := 0; i < 100; i++ {
		retry.observe(processIdentity{})
	}
	retry.observe(processIdentity{42, "100"})
	if !retry.allowed() || retry.failures != 0 {
		t.Fatal("missing process exhausted retry budget")
	}
	for i := 0; i < maxFailures; i++ {
		retry.finished(time.Second)
	}
	if retry.allowed() {
		t.Fatal("unbounded retries")
	}
	retry.observe(processIdentity{42, "101"})
	if !retry.allowed() {
		t.Fatal("new process inherited old failures")
	}
	retry.finished(time.Second)
	retry.finished(2 * time.Minute)
	if retry.failures != 1 {
		t.Fatal("stable injector did not reset the failure streak")
	}
}
func TestConfigRejectsUnsafeMissingAndEmptyAgents(t *testing.T) {
	root := t.TempDir()
	writeTestFile(t, filepath.Join(root, "frida-inject"), "#!/bin/sh\nexit 0\n", 0755)
	writeTestFile(t, filepath.Join(root, "agent.js"), "Java.perform(function() {});", 0644)
	cases := []struct {
		config string
		valid  bool
	}{
		{`{"com.qinggan.app.launcher":["agent.js"]}`, true},
		{`{"../escape":["agent.js"]}`, false},
		{`{"com.qinggan.app.launcher":["../agent.js"]}`, false},
		{`{"com.qinggan.app.launcher":["missing.js"]}`, false},
		{`{"com.qinggan.app.launcher":["agent.js","agent.js"]}`, false},
		{`{}`, false},
	}
	for _, tc := range cases {
		writeTestFile(t, filepath.Join(root, "injects.json"), tc.config, 0644)
		_, err := readConfig(root)
		if (err == nil) != tc.valid {
			t.Errorf("%s error=%v", tc.config, err)
		}
	}
	writeTestFile(t, filepath.Join(root, "injects.json"), cases[0].config, 0644)
	writeTestFile(t, filepath.Join(root, "agent.js"), "", 0644)
	if _, err := readConfig(root); err == nil {
		t.Fatal("empty agent accepted")
	}
}
func TestStatusConcurrentPublication(t *testing.T) {
	status := &statusStore{value: loaderStatus{Schema: 1, Infrastructure: "pi", Targets: map[string]targetStatus{}}}
	path := filepath.Join(t.TempDir(), "status.json")
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			for j := 0; j < 100; j++ {
				status.set(strconv.Itoa(i), targetStatus{State: "injecting", PID: j})
			}
		}(i)
	}
	for i := 0; i < 100; i++ {
		if err := status.publish(path, int64(i)); err != nil {
			t.Fatal(err)
		}
	}
	wg.Wait()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var result loaderStatus
	if err := json.Unmarshal(data, &result); err != nil {
		t.Fatal(err)
	}
	if result.Infrastructure != "pi" {
		t.Fatal("missing profile")
	}
}
func TestInjectorRetainsSessionAndStopsOnIdentityChange(t *testing.T) {
	root, state := t.TempDir(), t.TempDir()
	pidFile := filepath.Join(state, "injector.pid")
	// exec gives the long-lived child the injector PID, just like real frida-inject.
	writeTestFile(t, filepath.Join(root, "frida-inject"), "#!/bin/sh\necho $$ > '"+pidFile+"'\nexec sleep 60\n", 0755)
	identity := processIdentity{42, "100"}
	var replaced atomic.Bool
	status := &statusStore{value: loaderStatus{Targets: map[string]targetStatus{}}}
	w := worker{scriptsDir: root, stateDir: state, poll: 5 * time.Millisecond, status: status,
		read: func(string, int, string) (processIdentity, error) {
			if replaced.Load() {
				return processIdentity{42, "101"}, nil
			}
			return identity, nil
		}}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	finished := make(chan bool, 1)
	go func() {
		changed, _ := w.session(ctx, "com.qinggan.app.launcher", []string{"launcherdock.js"}, identity)
		finished <- changed
	}()
	var pid int
	for timeLimit := time.Now().Add(2 * time.Second); time.Now().Before(timeLimit); {
		data, _ := os.ReadFile(pidFile)
		pid, _ = strconv.Atoi(strings.TrimSpace(string(data)))
		if pid > 0 {
			break
		}
		time.Sleep(5 * time.Millisecond)
	}
	if pid == 0 {
		t.Fatal("injector did not start")
	}
	if err := syscall.Kill(pid, 0); err != nil {
		t.Fatal("injector session was detached early", err)
	}
	replaced.Store(true)
	select {
	case changed := <-finished:
		if !changed {
			t.Fatal("identity change not detected")
		}
	case <-ctx.Done():
		t.Fatal("injector was not stopped")
	}
	if err := syscall.Kill(pid, 0); err == nil {
		t.Fatal("injector survived replaced target")
	}
	args := injectorArgs(identity, root, []string{"launcherdock.js"})
	for _, arg := range args {
		if arg == "-e" {
			t.Fatal("PI injector must retain its session")
		}
	}
}
func TestRunSingletonHeartbeatAndShutdown(t *testing.T) {
	root, state, proc := t.TempDir(), t.TempDir(), t.TempDir()
	statusPath := filepath.Join(state, "status.json")
	writeTestFile(t, filepath.Join(root, "injects.json"), `{"com.qinggan.app.launcher":["agent.js"]}`, 0644)
	writeTestFile(t, filepath.Join(root, "agent.js"), "// test", 0644)
	writeTestFile(t, filepath.Join(root, "frida-inject"), "#!/bin/sh\nexit 1\n", 0755)
	fakeProcess(t, proc, os.Getpid(), "loaderFrida", "999")
	writeTestFile(t, filepath.Join(proc, "sys/kernel/random/boot_id"), "test-boot\n", 0644)
	writeTestFile(t, filepath.Join(proc, "uptime"), "100.25 99.0\n", 0644)
	ctx, cancel := context.WithCancel(context.Background())
	finished := make(chan error, 1)
	go func() { finished <- run(ctx, root, state, statusPath, proc) }()
	for deadline := time.Now().Add(2 * time.Second); time.Now().Before(deadline); {
		if _, err := os.Stat(statusPath); err == nil {
			break
		}
		time.Sleep(5 * time.Millisecond)
	}
	data, err := os.ReadFile(statusPath)
	if err != nil {
		cancel()
		t.Fatal(err)
	}
	var status loaderStatus
	if err := json.Unmarshal(data, &status); err != nil {
		t.Fatal(err)
	}
	if status.State != "running" || status.BootID != "test-boot" || status.LoaderStartTicks != "999" || status.UpdatedUptimeSeconds != 100 {
		t.Fatalf("bad status: %+v", status)
	}
	if err := run(ctx, root, state, statusPath, proc); err == nil || !strings.Contains(err.Error(), "already running") {
		t.Fatalf("duplicate loader: %v", err)
	}
	cancel()
	if err := <-finished; err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(statusPath); !os.IsNotExist(err) {
		t.Fatal("shutdown retained stale heartbeat")
	}
}

func TestHealthRejectsStaleRebootedAndReusedPID(t *testing.T) {
	root := t.TempDir()
	path := filepath.Join(root, "status.json")
	fakeProcess(t, root, 42, "/data/local/bin/loaderFrida", "99")
	writeTestFile(t, filepath.Join(root, "sys/kernel/random/boot_id"), "current-boot\n", 0644)
	writeTestFile(t, filepath.Join(root, "uptime"), "100.0 99.0\n", 0644)
	base := loaderStatus{Schema: 1, Infrastructure: "pi", State: "running", LoaderPID: 42, LoaderStartTicks: "99", BootID: "current-boot", UpdatedUptimeSeconds: 99}
	cases := []struct {
		name   string
		change func(*loaderStatus)
		valid  bool
	}{
		{"live", func(*loaderStatus) {}, true},
		{"stale", func(s *loaderStatus) { s.UpdatedUptimeSeconds = 84 }, false},
		{"future", func(s *loaderStatus) { s.UpdatedUptimeSeconds = 101 }, false},
		{"reboot", func(s *loaderStatus) { s.BootID = "old-boot" }, false},
		{"PID reuse", func(s *loaderStatus) { s.LoaderStartTicks = "98" }, false},
	}
	for _, tc := range cases {
		value := base
		tc.change(&value)
		data, _ := json.Marshal(value)
		writeTestFile(t, path, string(data), 0644)
		if err := health(path, root); (err == nil) != tc.valid {
			t.Errorf("%s: %v", tc.name, err)
		}
	}
}
