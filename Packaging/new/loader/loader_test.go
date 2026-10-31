package main

import (
	"encoding/binary"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
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
		t.Fatal("replaced resident code in a live process")
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
	agents := nativeDynamicAgents("com.qinggan.cluster,com.qinggan.canbus.service,../bad,system_server,com.example.app,ru.yandex.yandexmaps,com.yango.maps.android")
	if len(agents) != 1 {
		t.Fatal(agents)
	}
	for _, agent := range agents {
		if agent.Target != "com.example.app" {
			t.Fatal(agent)
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

func testTargetELF(t *testing.T, root string, pid int, class byte, machine uint16) {
	t.Helper()
	header := make([]byte, 20)
	copy(header, "\x7fELF")
	header[4], header[5] = class, 1
	binary.LittleEndian.PutUint16(header[18:20], machine)
	testFile(t, filepath.Join(root, fmt.Sprint(pid), "exe"), string(header), 0755)
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

func TestPublicStatusMapsDrainPhasesToExistingUiContract(t *testing.T) {
	for _, phase := range []string{"quiescing", "quiesced", "failed"} {
		snapshot := loaderStatus{State: phase, Loader: processIdentity{12, "3"}, Agents: map[string]agentStatus{"windows": {State: "stopping", Identity: processIdentity{42, "4"}}}}
		value := publicStatus(snapshot)
		if !strings.Contains(value, "loader=stopped;pid=0;") {
			t.Fatal(value)
		}
		state := "unknown"
		if phase == "quiesced" {
			state = "disabled"
		}
		if !strings.Contains(value, "vd-bypass="+state+":42") {
			t.Fatal(value)
		}
	}
}
