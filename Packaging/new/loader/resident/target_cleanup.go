package resident

import (
	"context"
	"errors"

	"voyahtune/unified-loader/agentwire"
)

// PrepareRestart drains one resident without closing admission for other targets.
func (manager *Manager) PrepareRestart(ctx context.Context, target agentwire.Target) error {
	manager.mutex.Lock()
	current := manager.sessions[key(target)]
	manager.mutex.Unlock()
	if current == nil {
		return errors.New("resident to restart is missing")
	}
	current.mutex.Lock()
	defer current.mutex.Unlock()
	if manager.isStopping() || current.retired {
		return ErrQuiescing
	}
	if current.record.Target != target {
		return errors.New("resident identity changed before cleanup")
	}
	live, err := manager.alive(target)
	if err != nil {
		return err
	}
	if !live {
		return errors.New("resident process ended before cleanup")
	}
	if current.record.Phase == "quiesced" {
		return nil
	}
	return manager.closeResident(ctx, current)
}
