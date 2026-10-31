package resident

import (
	"context"
	"testing"

	"voyahtune/unified-loader/agentwire"
	"voyahtune/unified-loader/lifecycle"
)

func TestPrepareRestartDrainsOnceAndKeepsAdmission(t *testing.T) {
	manager, backend, store := setup(t)
	if _, err := manager.Ensure(context.Background(), target(), plan("launcher")); err != nil {
		t.Fatal(err)
	}
	backend.status.State = "restart-required"
	backend.status.Error = "missing package"
	if err := manager.PrepareRestart(context.Background(), target()); err != nil {
		t.Fatal(err)
	}
	if backend.closes != 1 || backend.starts != 1 || manager.Snapshot().Phase != lifecycle.Running {
		t.Fatal(backend.closes, manager.Snapshot())
	}
	if store.records[key(target())].Phase != "quiesced" {
		t.Fatal(store.records)
	}
	if err := manager.PrepareRestart(context.Background(), target()); err != nil || backend.closes != 1 {
		t.Fatal(err, backend.closes)
	}
	if _, err := manager.Ensure(context.Background(), target(), plan("launcher")); err != ErrRestartRequired {
		t.Fatal(err)
	}
}
func TestPrepareRestartRejectsChangedIdentityAndStoppedAdmission(t *testing.T) {
	manager, backend, _ := setup(t)
	if _, err := manager.Ensure(context.Background(), target(), plan("launcher")); err != nil {
		t.Fatal(err)
	}
	changed := target()
	changed.RuntimeID = "different"
	if err := manager.PrepareRestart(context.Background(), changed); err == nil || backend.closes != 0 {
		t.Fatal(err)
	}
	manager.stopping = true
	if err := manager.PrepareRestart(context.Background(), target()); err != ErrQuiescing || backend.closes != 0 {
		t.Fatal(err)
	}
	manager.stopping = false
	manager.alive = func(_ agentwire.Target) (bool, error) { return false, nil }
	if err := manager.PrepareRestart(context.Background(), target()); err == nil || backend.closes != 0 {
		t.Fatal(err)
	}
}
