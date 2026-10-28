package injecthelper

import (
	"testing"

	"voyahtune/unified-loader/agentwire"
	"voyahtune/unified-loader/remoteattach"
)

func validAttemptFixture() (Request, Receipt) {
	target := agentwire.Target{BootID: "boot", PID: 41, UID: 1000, StartTicks: "100", NativeID: "native", RuntimeID: "java"}
	request := Request{Schema: 1, RequestID: "request-123", Target: target,
		Plan: agentwire.Plan{Schema: 1, RuntimeID: "java", Revision: 1, Modules: []string{"phone"}}}
	receipt := Receipt{Schema: 1, RequestID: request.RequestID, Target: target, Stage: "active", Completed: true,
		Helper: agentwire.Target{BootID: "boot", PID: 42, UID: 0, StartTicks: "101"},
		Native: remoteattach.NativeResult{Attached: true, RegistersSaved: true, Restored: true, Detached: true,
			LibraryLoaded: true, WorkerStarted: true, SafePC: 4096},
		Status: &agentwire.Status{Schema: 1, Target: target, State: "ready", Revision: 1, Modules: map[string]string{"phone": "active"}}}
	return request, receipt
}

func TestReadinessRequiresHelperExitAndFinalKernelIdentity(t *testing.T) {
	request, receipt := validAttemptFixture()
	attempt := NewAttempt(request)
	if state := attempt.Snapshot(); state.State != "injecting" || state.ActivePublished {
		t.Fatal(state)
	}
	attempt.RecordHelperExit(receipt, 42, 0)
	if state := attempt.Snapshot(); state.State != "injecting" || state.ActivePublished {
		t.Fatal("helper ACK alone became active", state)
	}
	attempt.ConfirmTargetIdentity(request.Target)
	if state := attempt.Snapshot(); state.State != "active" || !state.ActivePublished {
		t.Fatal(state)
	}
}

func TestReadinessRejectsIncompleteOrUncorrelatedHelperResults(t *testing.T) {
	mutations := []func(*Receipt){
		func(value *Receipt) { value.RequestID = "stale-request" },
		func(value *Receipt) { value.Target.StartTicks = "old" },
		func(value *Receipt) { value.Status.NativeID = "old" },
		func(value *Receipt) { value.Helper.PID = 43 },
		func(value *Receipt) { value.Helper.UID = 1000 },
		func(value *Receipt) { value.Completed = false },
		func(value *Receipt) { value.Status.State = "starting" },
		func(value *Receipt) { value.Status.Modules["phone"] = "failed" },
		func(value *Receipt) { value.Native.Detached = false },
		func(value *Receipt) { value.Native.Restored = false },
		func(value *Receipt) { value.Native.Uncertain = true },
		func(value *Receipt) { value.Native.WorkerStarted = false },
	}
	for _, mutate := range mutations {
		request, receipt := validAttemptFixture()
		mutate(&receipt)
		attempt := NewAttempt(request)
		attempt.RecordHelperExit(receipt, 42, 0)
		attempt.ConfirmTargetIdentity(request.Target)
		if state := attempt.Snapshot(); state.State != "failed" || state.ActivePublished {
			t.Fatal(state)
		}
	}
}

func TestReadinessFailureCannotBeOverwrittenByLaterSuccess(t *testing.T) {
	request, receipt := validAttemptFixture()
	for _, exitCode := range []int{0, 7} {
		attempt := NewAttempt(request)
		changed := request.Target
		changed.StartTicks = "101"
		attempt.ConfirmTargetIdentity(changed)
		attempt.RecordHelperExit(receipt, 42, exitCode)
		attempt.ConfirmTargetIdentity(request.Target)
		if state := attempt.Snapshot(); state.State != "failed" || state.ActivePublished {
			t.Fatal(state)
		}
	}
}
