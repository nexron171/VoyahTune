package main

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"testing"

	"voyahtune/unified-loader/lifecycle"
)

func offlineFixture(t *testing.T) (string, string, string) {
	t.Helper()
	root := t.TempDir()
	proc := filepath.Join(root, "proc")
	fakeProcess(t, proc, os.Getpid(), "voyahtune-loader", "100")
	testFile(t, filepath.Join(proc, "sys/kernel/random/boot_id"), "boot", 0600)
	testFile(t, filepath.Join(proc, fmt.Sprint(os.Getpid()), "status"), fmt.Sprintf("Uid:\t%d\t%d\t%d\t%d\n", os.Geteuid(), os.Geteuid(), os.Geteuid(), os.Geteuid()), 0600)
	return filepath.Join(root, "runtime"), filepath.Join(root, "state"), proc
}

func TestOfflineQuiesceDoesNotRequireCurrentRuntimeFiles(t *testing.T) {
	runtime, state, proc := offlineFixture(t)
	result, err := quiesceOffline(context.Background(), runtime, state, proc)
	if err != nil {
		t.Fatal(err)
	}
	if result.State != (lifecycle.State{Phase: lifecycle.Quiesced}) || result.Identity.PID != os.Getpid() || result.Identity.StartTicks != "100" || result.RequestID == "" {
		t.Fatal(result)
	}
	again, err := quiesceOffline(context.Background(), runtime, state, proc)
	if err != nil || again.State != result.State || again.RequestID == result.RequestID {
		t.Fatal(again, err)
	}
}

func TestOfflineQuiesceCannotCompeteWithSupervisor(t *testing.T) {
	runtime, state, proc := offlineFixture(t)
	lock, err := lockNativeState(state)
	if err != nil {
		t.Fatal(err)
	}
	defer unlockFile(lock)
	if _, err := quiesceOffline(context.Background(), runtime, state, proc); err == nil {
		t.Fatal("Competing cleanup admitted")
	}
}

func TestOfflineQuiesceCannotIgnoreCorruptJournal(t *testing.T) {
	runtime, state, proc := offlineFixture(t)
	if _, err := quiesceOffline(context.Background(), runtime, state, proc); err != nil {
		t.Fatal(err)
	}
	testFile(t, filepath.Join(state, "residents/corrupt.json"), "{broken", 0600)
	if _, err := quiesceOffline(context.Background(), runtime, state, proc); err == nil {
		t.Fatal("Corrupt journal treated as drained")
	}
}
