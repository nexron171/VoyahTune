package resident

import (
	"context"
	"errors"
	"testing"
	"voyahtune/unified-loader/agentwire"
)

type observedBackend struct {
	*fakeBackend
	recoveries int
	probes     int
	probeError error
}

func (backend *observedBackend) Recover(ctx context.Context, record Record) (Recovery, error) {
	backend.recoveries++
	return backend.fakeBackend.Recover(ctx, record)
}

func (backend *observedBackend) Status(ctx context.Context, target agentwire.Target) (agentwire.Status, error) {
	backend.probes++
	if backend.probeError != nil {
		return agentwire.Status{}, backend.probeError
	}
	return *backend.status, nil
}

func TestActiveSessionUsesStatusAndRecoversAfterConnectionError(t *testing.T) {
	_, original, store := setup(t)
	backend := &observedBackend{fakeBackend: original}
	manager, err := New(store, backend, func(agentwire.Target) (bool, error) { return true, nil })
	if err != nil {
		t.Fatal(err)
	}
	ctx := context.Background()
	for range 2 {
		if _, err := manager.Ensure(ctx, target(), plan("phone")); err != nil {
			t.Fatal(err)
		}
	}
	if backend.recoveries != 1 || backend.probes != 1 || backend.starts != 1 || backend.applies != 0 {
		t.Fatal(backend)
	}
	backend.probeError = errors.New("connection lost")
	if _, err := manager.Ensure(ctx, target(), plan("phone")); err != nil {
		t.Fatal(err)
	}
	if backend.recoveries != 2 || backend.starts != 1 {
		t.Fatal("failed probe bypassed recovery", backend)
	}
	restarted, err := New(store, backend, func(agentwire.Target) (bool, error) { return true, nil })
	if err != nil {
		t.Fatal(err)
	}
	if _, err := restarted.Ensure(ctx, target(), plan("phone")); err != nil {
		t.Fatal(err)
	}
	if backend.recoveries != 3 {
		t.Fatal("restarted loader trusted persisted state", backend.recoveries)
	}
}

func TestActiveSessionStillValidatesAcknowledgement(t *testing.T) {
	for _, state := range []string{"quiesced", "failed"} {
		t.Run(state, func(t *testing.T) {
			_, original, store := setup(t)
			backend := &observedBackend{fakeBackend: original}
			manager, err := New(store, backend, func(agentwire.Target) (bool, error) { return true, nil })
			if err != nil {
				t.Fatal(err)
			}
			ctx := context.Background()
			if _, err := manager.Ensure(ctx, target(), plan("phone")); err != nil {
				t.Fatal(err)
			}
			backend.status.State = state
			if _, err := manager.Ensure(ctx, target(), plan("phone")); err == nil {
				t.Fatal("invalid acknowledgement accepted")
			}
			if backend.starts != 1 || backend.applies != 0 {
				t.Fatal("invalid state caused injection", backend)
			}
		})
	}
}

func TestActiveSessionAppliesChangedPlanThroughStatus(t *testing.T) {
	_, original, store := setup(t)
	backend := &observedBackend{fakeBackend: original}
	manager, err := New(store, backend, func(agentwire.Target) (bool, error) { return true, nil })
	if err != nil {
		t.Fatal(err)
	}
	ctx := context.Background()
	if _, err := manager.Ensure(ctx, target(), plan("phone")); err != nil {
		t.Fatal(err)
	}
	status, err := manager.Ensure(ctx, target(), plan("phone", "rds"))
	if err != nil || status.Modules["rds"] != "active" || status.Revision != 2 {
		t.Fatal(status, err)
	}
	if backend.recoveries != 1 || backend.probes != 1 || backend.applies != 1 || backend.starts != 1 {
		t.Fatal(backend)
	}
}
