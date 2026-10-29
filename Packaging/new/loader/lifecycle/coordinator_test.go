package lifecycle

import (
	"context"
	"errors"
	"sync"
	"testing"
)

func TestQuiesceDrainsEveryAdmittedOperation(t *testing.T) {
	coordinator := new(Coordinator)
	first, ok := coordinator.Begin()
	if !ok {
		t.Fatal("first operation rejected")
	}
	second, ok := coordinator.Begin()
	if !ok {
		t.Fatal("second operation rejected")
	}
	drained := coordinator.StartQuiesce()
	if _, ok := coordinator.Begin(); ok {
		t.Fatal("operation admitted after quiesce")
	}
	first.Complete()
	first.Complete()
	select {
	case <-drained:
		t.Fatal("quiesce acknowledged before every operation completed")
	default:
	}
	if state := coordinator.Snapshot(); state.Phase != Quiescing || state.InFlight != 1 {
		t.Fatalf("unexpected state: %+v", state)
	}
	second.Complete()
	select {
	case <-drained:
	default:
		t.Fatal("completed operations did not release quiesce")
	}
	if state := coordinator.Snapshot(); state.Phase != Quiesced || state.InFlight != 0 {
		t.Fatalf("unexpected final state: %+v", state)
	}
	if coordinator.StartQuiesce() != drained {
		t.Fatal("repeated quiesce started another transition")
	}
}

func TestCancelledRequestDoesNotReopenAdmission(t *testing.T) {
	coordinator := new(Coordinator)
	operation, _ := coordinator.Begin()
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if err := coordinator.Quiesce(ctx); !errors.Is(err, context.Canceled) {
		t.Fatalf("request did not report cancellation: %v", err)
	}
	if _, ok := coordinator.Begin(); ok {
		t.Fatal("request cancellation reopened admission")
	}
	operation.Complete()
	if err := coordinator.Quiesce(context.Background()); err != nil {
		t.Fatal(err)
	}
}

func TestIdleQuiesceCompletesImmediately(t *testing.T) {
	coordinator := new(Coordinator)
	if err := coordinator.Quiesce(context.Background()); err != nil {
		t.Fatal(err)
	}
	if state := coordinator.Snapshot(); state.Phase != Quiesced || state.InFlight != 0 {
		t.Fatalf("idle coordinator did not stop: %+v", state)
	}
}

func TestConcurrentCompletionKeepsAdmissionClosed(t *testing.T) {
	coordinator := new(Coordinator)
	operations := make([]*Operation, 32)
	for index := range operations {
		var ok bool
		operations[index], ok = coordinator.Begin()
		if !ok {
			t.Fatal("running coordinator rejected operation")
		}
	}
	drained := coordinator.StartQuiesce()
	var workers sync.WaitGroup
	for _, operation := range operations {
		workers.Add(1)
		go func(operation *Operation) {
			defer workers.Done()
			operation.Complete()
			operation.Complete()
		}(operation)
	}
	workers.Wait()
	select {
	case <-drained:
	default:
		t.Fatal("concurrent completion did not drain operations")
	}
	if _, ok := coordinator.Begin(); ok {
		t.Fatal("completion reopened admission")
	}
}
