package resident

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"syscall"
	"testing"

	"voyahtune/unified-loader/agentwire"
	"voyahtune/unified-loader/injecthelper"
	"voyahtune/unified-loader/remoteattach"
)

type fakeAgent struct {
	status  agentwire.Status
	failure error
}

func (agent *fakeAgent) Status(context.Context) (agentwire.Status, error) {
	return agent.status, agent.failure
}
func (agent *fakeAgent) Apply(context.Context, agentwire.Plan) (agentwire.Status, error) {
	return agent.status, agent.failure
}
func (agent *fakeAgent) Quiesce(context.Context) (agentwire.Status, error) {
	return agent.status, agent.failure
}

func backendFixture(t *testing.T) (*NativeBackend, Record, *fakeAgent) {
	t.Helper()
	root := t.TempDir()
	backend, err := NewNativeBackend(NativeOptions{StateDirectory: filepath.Join(root, "helpers"), ProcRoot: filepath.Join(root, "proc"), HelperPath: "/runtime/voyahtune-inject", LibraryPath: "/runtime/agent.so", DEXPath: "/runtime/agents.dex", DEXSHA256: "digest"})
	if err != nil {
		t.Fatal(err)
	}
	agent := &fakeAgent{failure: syscall.ECONNREFUSED}
	backend.client = func(agentwire.Target) (agentConnection, error) { return agent, nil }
	return backend, Record{Schema: 1, Target: target(), Plan: plan("phone"), Phase: "pending"}, agent
}
func persistReceipt(t *testing.T, backend *NativeBackend, record Record, receipt injecthelper.Receipt) string {
	t.Helper()
	directory, err := backend.processDirectory(record.Target)
	if err != nil {
		t.Fatal(err)
	}
	request := injecthelper.Request{Schema: 1, RequestID: "backend-test-request", Target: record.Target, Plan: record.Plan}
	for name, value := range map[string]any{"request.json": request, "receipt.json": receipt} {
		data, err := json.Marshal(value)
		if err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(directory, name), data, 0600); err != nil {
			t.Fatal(err)
		}
	}
	return directory
}
func safeReceipt(record Record) injecthelper.Receipt {
	return injecthelper.Receipt{Schema: 1, RequestID: "backend-test-request", Target: record.Target, Helper: agentwire.Target{BootID: "boot", PID: 101, UID: 0, StartTicks: "200"}, Stage: "failed", Completed: true, Native: remoteattach.NativeResult{Attached: true, RegistersSaved: true, Restored: true, Detached: true}}
}

func TestNativeRecoveryRequiresMoreThanAnAbsentSocket(t *testing.T) {
	backend, record, _ := backendFixture(t)
	if recovery, err := backend.Recover(context.Background(), record); !errors.Is(err, ErrUncertain) || recovery.SafeToAttach {
		t.Fatal("lost pending helper treated as a fresh process", recovery, err)
	}
	record.Phase = "new"
	if recovery, err := backend.Recover(context.Background(), record); err != nil || !recovery.SafeToAttach {
		t.Fatal(recovery, err)
	}
}
func TestNativeRecoveryDistinguishesRestoredFailureAndMappedCode(t *testing.T) {
	backend, record, _ := backendFixture(t)
	receipt := safeReceipt(record)
	persistReceipt(t, backend, record, receipt)
	if recovery, err := backend.Recover(context.Background(), record); err != nil || !recovery.SafeToAttach {
		t.Fatal(recovery, err)
	}
	receipt.Native.LibraryLoaded = true
	persistReceipt(t, backend, record, receipt)
	if recovery, err := backend.Recover(context.Background(), record); !errors.Is(err, ErrUncertain) || recovery.SafeToAttach {
		t.Fatal("second library allowed over mapped code", recovery, err)
	}
}
func TestNativeRecoveryCannotSkipBusyOrphanLock(t *testing.T) {
	backend, record, agent := backendFixture(t)
	directory := persistReceipt(t, backend, record, safeReceipt(record))
	lock, err := os.OpenFile(filepath.Join(directory, "target-42-100.lock"), os.O_CREATE|os.O_RDWR, 0600)
	if err != nil {
		t.Fatal(err)
	}
	defer lock.Close()
	if err := syscall.Flock(int(lock.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
		t.Fatal(err)
	}
	defer syscall.Flock(int(lock.Fd()), syscall.LOCK_UN)
	agent.failure = nil
	agent.status = agentwire.Status{Schema: 1, Target: record.Target, State: "ready", Modules: map[string]string{"phone": "active"}}
	if recovery, err := backend.Recover(context.Background(), record); !errors.Is(err, ErrUncertain) || recovery.Status != nil {
		t.Fatal("live endpoint hid a busy native helper", recovery, err)
	}
}
func TestNativeRecoveryKeepsObservedCodeIdentityForCleanup(t *testing.T) {
	backend, record, agent := backendFixture(t)
	agent.failure = agentwire.ErrRuntimeChanged
	agent.status = agentwire.Status{Schema: 1, Target: record.Target, State: "ready", Modules: map[string]string{"phone": "active"}}
	agent.status.NativeID = "previous-native"
	recovery, err := backend.Recover(context.Background(), record)
	if err != nil || recovery.Status == nil || recovery.Status.NativeID != "previous-native" || recovery.SafeToAttach {
		t.Fatal(recovery, err)
	}
}
func TestNativeRecoveryRejectsStaleReceipt(t *testing.T) {
	backend, record, _ := backendFixture(t)
	receipt := safeReceipt(record)
	receipt.Target.StartTicks = "reused-pid"
	persistReceipt(t, backend, record, receipt)
	if recovery, err := backend.Recover(context.Background(), record); err == nil || recovery.SafeToAttach {
		t.Fatal("stale receipt authorized another attach", recovery, err)
	}
}

func TestNativeBackendSelectsMatchingHelperAndLibrary(t *testing.T) {
	backend, record, _ := backendFixture(t)
	backend.options.ARM32HelperPath = "/runtime/voyahtune-inject-arm"
	backend.options.ARM32LibraryPath = "/runtime/libvoyahtune-agent-arm.so"
	directory := filepath.Join(backend.options.ProcRoot, fmt.Sprint(record.Target.PID))
	if err := os.MkdirAll(directory, 0700); err != nil {
		t.Fatal(err)
	}
	for _, class := range []byte{1, 2} {
		header := make([]byte, 20)
		copy(header, "\x7fELF")
		header[4], header[5] = class, 1
		header[18] = 40
		if class == 2 {
			header[18] = 183
		}
		if err := os.WriteFile(filepath.Join(directory, "exe"), header, 0600); err != nil {
			t.Fatal(err)
		}
		helper, library, err := backend.components(record.Target)
		if err != nil {
			t.Fatal(err)
		}
		if class == 1 && (helper != backend.options.ARM32HelperPath || library != backend.options.ARM32LibraryPath) {
			t.Fatal(helper, library)
		}
		if class == 2 && (helper != backend.options.HelperPath || library != backend.options.LibraryPath) {
			t.Fatal(helper, library)
		}
	}
}
