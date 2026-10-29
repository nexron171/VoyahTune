package injecthelper

import (
	"errors"
	"fmt"
	"strconv"
	"sync"

	"voyahtune/unified-loader/agentwire"
)

type AttemptSnapshot struct {
	State           string `json:"state"`
	ActivePublished bool   `json:"activePublished"`
	Error           string `json:"error,omitempty"`
}

type Attempt struct {
	mutex           sync.Mutex
	request         Request
	helperCompleted bool
	state           string
	failure         error
}

func NewAttempt(request Request) *Attempt {
	request.Plan.Modules = append([]string(nil), request.Plan.Modules...)
	return &Attempt{request: request, state: "injecting"}
}

func (attempt *Attempt) RecordHelperExit(receipt Receipt, expectedPID, exitCode int) {
	attempt.mutex.Lock()
	defer attempt.mutex.Unlock()
	if attempt.failure != nil || attempt.helperCompleted {
		return
	}
	attempt.helperCompleted = true
	if exitCode != 0 {
		attempt.reject(fmt.Errorf("helper exited %d", exitCode))
		return
	}
	if err := verifyReceipt(attempt.request, receipt, expectedPID); err != nil {
		attempt.reject(err)
	}
}

func (attempt *Attempt) ConfirmTargetIdentity(actual agentwire.Target) {
	attempt.mutex.Lock()
	defer attempt.mutex.Unlock()
	if attempt.failure != nil {
		return
	}
	expected := attempt.request.Target
	if actual.BootID != expected.BootID || actual.PID != expected.PID || actual.UID != expected.UID || actual.StartTicks != expected.StartTicks {
		attempt.reject(errors.New("target identity changed before active publication"))
		return
	}
	if attempt.helperCompleted {
		attempt.state = "active"
	}
}

func (attempt *Attempt) Snapshot() AttemptSnapshot {
	attempt.mutex.Lock()
	defer attempt.mutex.Unlock()
	result := AttemptSnapshot{State: attempt.state, ActivePublished: attempt.state == "active"}
	if attempt.failure != nil {
		result.Error = attempt.failure.Error()
	}
	return result
}

func (attempt *Attempt) reject(failure error) {
	attempt.failure = failure
	attempt.state = "failed"
}

func verifyReceipt(request Request, receipt Receipt, expectedPID int) error {
	if request.Schema != 1 || request.RequestID == "" || request.Plan.Validate(request.Target.RuntimeID) != nil ||
		receipt.Schema != 1 || receipt.RequestID != request.RequestID || receipt.Target != request.Target ||
		!receipt.Completed || receipt.Error != "" || receipt.Stage != "active" {
		return errors.New("helper did not return a correlated active receipt")
	}
	if expectedPID <= 1 || receipt.Helper.PID != expectedPID || receipt.Helper.UID != 0 || receipt.Helper.BootID != request.Target.BootID {
		return errors.New("helper process identity mismatch")
	}
	if _, err := strconv.ParseUint(receipt.Helper.StartTicks, 10, 64); err != nil {
		return errors.New("helper start ticks missing")
	}
	native := receipt.Native
	if !native.Restored || !native.Detached || !native.WorkerStarted || native.Uncertain || native.TargetExited || native.BootstrapResult != 0 ||
		receipt.Reused == native.Attached {
		return errors.New("helper did not confirm native restoration and worker readiness")
	}
	if native.Attached && (!native.RegistersSaved || !native.LibraryLoaded || native.SafePC == 0 || native.SafePC%4 != 0) {
		return errors.New("native attach context was not confirmed")
	}
	status := receipt.Status
	if status == nil || status.Schema != 1 || status.Target != request.Target || status.State != "ready" || status.Error != "" ||
		status.Revision != request.Plan.Revision || len(status.Modules) != len(request.Plan.Modules) {
		return errors.New("helper has no complete Java module ACK")
	}
	for _, id := range request.Plan.Modules {
		if status.Modules[id] != "active" {
			return fmt.Errorf("module %s was not active", id)
		}
	}
	return nil
}
