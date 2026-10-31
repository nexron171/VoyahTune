package main

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"voyahtune/unified-loader/agentwire"
)

type recoverableLauncher struct {
	residentSpy
	cleanupError  error
	cleanups      int
	duringCleanup func()
}

func (engine *recoverableLauncher) PrepareRestart(_ context.Context, _ agentwire.Target) error {
	engine.cleanups++
	if engine.duringCleanup != nil {
		engine.duringCleanup()
	}
	return engine.cleanupError
}
func recoveryFixture(t *testing.T) (*nativeSupervisor, *recoverableLauncher, agentwire.Target, *int) {
	t.Helper()
	manager, _ := supervisorFixture(t, []nativeAgentConfig{{ID: "launcher", Target: launcherTarget, Modules: []string{"launcher"}}})
	supervisorTarget(t, manager, launcherTarget, "100", 2, 183, "1000")
	engine := new(recoverableLauncher)
	manager.engine = engine
	manager.updateBlock = filepath.Join(t.TempDir(), "block")
	manager.migrationBoot = filepath.Join(t.TempDir(), "migration")
	signals := new(int)
	manager.restartLauncherProcess = func(target agentwire.Target) error {
		if engine.cleanups == 0 {
			t.Fatal("signal preceded cleanup")
		}
		*signals++
		return nil
	}
	target := agentwire.Target{BootID: "boot", PID: 42, UID: 1000, StartTicks: "100", NativeID: "native", RuntimeID: "java"}
	return manager, engine, target, signals
}
func TestLauncherRecoveryIsBoundedAndSurvivesSupervisorRestart(t *testing.T) {
	manager, engine, target, signals := recoveryFixture(t)
	state, reason, handled := manager.recoverLauncher(context.Background(), launcherTarget, target,
		agentwire.Status{Schema: 1, Target: target, State: "restart-required", Error: "missing browser"})
	if !handled || state != "restarting" || reason != "missing browser" || *signals != 1 {
		t.Fatal(state, reason, handled, *signals)
	}
	state, err := manager.restartLauncher(context.Background(), target, "missing browser")
	if state != "restart-required" || err == nil || *signals != 1 || engine.cleanups != 1 {
		t.Fatal(state, err, *signals)
	}
	target.PID = 43
	if _, err := manager.restartLauncher(context.Background(), target, "second failure"); err == nil || *signals != 1 {
		t.Fatal("missing backoff", err)
	}
	testFile(t, filepath.Join(manager.procRoot, "uptime"), "161 0", 0600)
	state, err = manager.restartLauncher(context.Background(), target, "second failure")
	if err != nil || state != "restarting" || *signals != 2 {
		t.Fatal(state, err, *signals)
	}
	replacement, _, _, replacementSignals := recoveryFixture(t)
	replacement.stateDirectory = manager.stateDirectory
	replacement.procRoot = manager.procRoot
	target.PID = 44
	testFile(t, filepath.Join(manager.procRoot, "uptime"), "300 0", 0600)
	if _, err := replacement.restartLauncher(context.Background(), target, "third failure"); err == nil || *replacementSignals != 0 {
		t.Fatal("restart budget lost", err)
	}
}
func TestLauncherRecoveryDoesNotSignalWithoutCleanupOrDuringUpdate(t *testing.T) {
	for _, condition := range []string{"cleanup", "update", "update-during-cleanup", "cancelled", "corrupt-journal"} {
		t.Run(condition, func(t *testing.T) {
			manager, engine, target, signals := recoveryFixture(t)
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			switch condition {
			case "cleanup":
				engine.cleanupError = errors.New("callbacks not drained")
			case "update":
				testFile(t, manager.updateBlock, "blocked", 0600)
			case "update-during-cleanup":
				engine.duringCleanup = func() { testFile(t, manager.updateBlock, "blocked", 0600) }
			case "cancelled":
				engine.duringCleanup = cancel
			case "corrupt-journal":
				testFile(t, filepath.Join(manager.stateDirectory, "launcher-recovery.json"), "broken", 0600)
			}
			state, err := manager.restartLauncher(ctx, target, "error")
			if state != "restart-required" || err == nil || *signals != 0 {
				t.Fatal(state, err, *signals)
			}
		})
	}
}
func TestRecoveryRejectsOtherTargetsAndChangedCode(t *testing.T) {
	manager, _, target, signals := recoveryFixture(t)
	ack := agentwire.Status{Schema: 1, Target: target, State: "restart-required", Error: "original failure"}
	if _, _, handled := manager.recoverLauncher(context.Background(), "system_server", target, ack); handled {
		t.Fatal("recovered system_server")
	}
	ack.RuntimeID = "old-java"
	if _, _, handled := manager.recoverLauncher(context.Background(), launcherTarget, target, ack); handled {
		t.Fatal("recovered incompatible runtime")
	}
	if *signals != 0 {
		t.Fatal(*signals)
	}
}
func TestLauncherFailureReplacesStaleActiveJournal(t *testing.T) {
	manager, _, target, _ := recoveryFixture(t)
	identity := processIdentity{PID: target.PID, StartTicks: target.StartTicks}
	path := manager.attemptPath(launcherTarget)
	if err := writeAtomic(path, attemptRecord{Boot: "boot", Identity: identity, State: "active", Attempts: 1}); err != nil {
		t.Fatal(err)
	}
	manager.recordLauncherFailure(launcherTarget, identity, "missing browser")
	var record attemptRecord
	if err := readJSON(path, &record); err != nil {
		t.Fatal(err)
	}
	if record.State != "restart-required" || record.LastError != "missing browser" {
		t.Fatal(record)
	}
}
func TestLauncherRestartRejectsReusedProcessIdentity(t *testing.T) {
	manager, _, target, _ := recoveryFixture(t)
	target.StartTicks = "99"
	if err := manager.signalLauncherRestart(target); err == nil || !strings.Contains(err.Error(), "identity changed") {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(manager.procRoot, "42/stat")); err != nil {
		t.Fatal(err)
	}
}

func TestLauncherRecoveryRetriesFailedSignalAfterBackoff(t *testing.T) {
	manager, _, target, signals := recoveryFixture(t)
	manager.restartLauncherProcess = func(agentwire.Target) error { return errors.New("signal denied") }
	if _, err := manager.restartLauncher(context.Background(), target, "failure"); err == nil {
		t.Fatal("signal failure ignored")
	}
	manager.restartLauncherProcess = func(agentwire.Target) error { (*signals)++; return nil }
	testFile(t, filepath.Join(manager.procRoot, "uptime"), "161 0", 0600)
	state, err := manager.restartLauncher(context.Background(), target, "failure")
	if err != nil || state != "restarting" || *signals != 1 {
		t.Fatal(state, err, *signals)
	}
}
