package main

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestApolloFlagCurrentBootAndStrictKeys(t *testing.T) {
	const boot = "22c4d70f-667d-4405-9431-7f1ff2a8b90b"
	enabled := "v=1\nboot=" + boot + "\nenabled=1\n"
	if !apolloEnabled(enabled, boot) {
		t.Fatal("valid Native runtime flag rejected")
	}
	for _, bad := range []string{"", strings.ReplaceAll(enabled, "enabled=1", "enabled=0"), enabled + "v=1\n", enabled + "extra=1\n", strings.ReplaceAll(enabled, boot, "previous-boot"), strings.ReplaceAll(enabled, "v=1", "v=2"), enabled + strings.Repeat("\n", 193)} {
		if apolloEnabled(bad, boot) {
			t.Errorf("unsafe Apollo flag accepted: %q", bad)
		}
	}
}
func TestApolloReadinessRequiresExactMarkerWithoutInjectorErrors(t *testing.T) {
	for _, tc := range []struct {
		text  string
		ready bool
	}{
		{"[apollo] hook ready profile=persisted-target", true},
		{"[apollo] hook failed stage=install", false},
		{"[apollo] hook ready\nError resolving target", false},
		{"hook ready", false},
	} {
		if apolloOutputReady([]byte(tc.text)) != tc.ready {
			t.Error(tc)
		}
	}
}
func TestApolloEternalizedAttemptSurvivesLoaderRestart(t *testing.T) {
	root, state := t.TempDir(), t.TempDir()
	argsPath := filepath.Join(state, "args")
	countPath := filepath.Join(state, "count")
	writeTestFile(t, filepath.Join(root, "frida-inject"), "#!/bin/sh\necho run >> '"+countPath+"'\nprintf '%s\\n' \"$@\" > '"+argsPath+"'\necho '[apollo] hook ready profile=persisted-target'\n", 0755)
	identity := processIdentity{42, "99"}
	status := &statusStore{value: loaderStatus{Targets: map[string]targetStatus{}}}
	w := worker{scriptsDir: root, stateDir: state, status: status, read: func(string, int, string) (processIdentity, error) { return identity, nil }}
	w.apolloCycle(context.Background(), "boot-a", identity)
	if status.value.Targets[apolloTarget].State != "active" {
		t.Fatalf("not confirmed: %+v", status.value.Targets[apolloTarget])
	}
	args, err := os.ReadFile(argsPath)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(args), "\n-e\n") {
		t.Fatalf("Apollo needs OD eternalization: %s", args)
	}
	// The reservation lives on disk, not in the previous worker's in-memory state.
	next := worker{scriptsDir: root, stateDir: state, status: status, read: w.read}
	next.apolloCycle(context.Background(), "boot-a", identity)
	calls, _ := os.ReadFile(countPath)
	if string(calls) != "run\n" {
		t.Fatalf("reinjected into existing identity: %q", calls)
	}
	identity.StartTicks = "100"
	next.apolloCycle(context.Background(), "boot-a", identity)
	calls, _ = os.ReadFile(countPath)
	if string(calls) != "run\nrun\n" {
		t.Fatalf("new identity not injected: %q", calls)
	}
}
func TestApolloFailureIsLatchedAndNeverReportedActive(t *testing.T) {
	root, state := t.TempDir(), t.TempDir()
	countPath := filepath.Join(state, "count")
	writeTestFile(t, filepath.Join(root, "frida-inject"), "#!/bin/sh\necho run >> '"+countPath+"'\necho '[apollo] hook failed stage=install'\nexit 0\n", 0755)
	identity := processIdentity{42, "99"}
	status := &statusStore{value: loaderStatus{Targets: map[string]targetStatus{}}}
	w := worker{scriptsDir: root, stateDir: state, status: status, read: func(string, int, string) (processIdentity, error) { return identity, nil }}
	w.apolloCycle(context.Background(), "boot-a", identity)
	w.apolloCycle(context.Background(), "boot-a", identity)
	calls, _ := os.ReadFile(countPath)
	if string(calls) != "run\n" {
		t.Fatalf("failed Apollo retried: %q", calls)
	}
	if status.value.Targets[apolloTarget].State != "failed" {
		t.Fatal("exit 0 was mistaken for hook readiness")
	}
}

func TestApolloWorkerWaitsForNativeCurrentBootFlag(t *testing.T) {
	root, state, proc := t.TempDir(), t.TempDir(), t.TempDir()
	flagPath := filepath.Join(state, "native-runtime-flag")
	countPath := filepath.Join(state, "count")
	const boot = "22c4d70f-667d-4405-9431-7f1ff2a8b90b"
	writeTestFile(t, filepath.Join(proc, "sys/kernel/random/boot_id"), boot+"\n", 0644)
	writeTestFile(t, filepath.Join(root, "frida-inject"), "#!/bin/sh\necho run >> '"+countPath+"'\necho '[apollo] hook ready'\n", 0755)
	identity := processIdentity{42, "99"}
	status := &statusStore{value: loaderStatus{Targets: map[string]targetStatus{}}}
	w := worker{scriptsDir: root, stateDir: state, procRoot: proc, apolloFlag: flagPath, poll: 5 * time.Millisecond, status: status,
		find: func(string, string) (processIdentity, error) { return identity, nil }, read: func(string, int, string) (processIdentity, error) { return identity, nil }}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	done := make(chan struct{})
	go func() { defer close(done); w.run(ctx, apolloTarget, []string{"apollo_tech.js"}) }()
	defer func() { cancel(); <-done }()
	stateOf := func() string {
		status.mu.Lock()
		defer status.mu.Unlock()
		return status.value.Targets[apolloTarget].State
	}
	for stateOf() != "disabled" {
		if !wait(ctx, time.Millisecond) {
			t.Fatal("missing flag did not disable Apollo")
		}
	}
	if _, err := os.Stat(countPath); !os.IsNotExist(err) {
		t.Fatal("Apollo injected without opt-in")
	}
	writeTestFile(t, flagPath, "v=1\nboot="+boot+"\nenabled=1\n", 0644)
	for stateOf() != "active" {
		if !wait(ctx, time.Millisecond) {
			t.Fatal("current-boot opt-in did not activate Apollo")
		}
	}
	calls, _ := os.ReadFile(countPath)
	if string(calls) != "run\n" {
		t.Fatalf("unexpected Apollo calls: %q", calls)
	}
}
