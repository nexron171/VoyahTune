package lifecycle

import (
	"context"
	"sync"
)

type Phase string

const (
	Running   Phase = "running"
	Quiescing Phase = "quiescing"
	Quiesced  Phase = "quiesced"
)

type State struct {
	Phase    Phase `json:"phase"`
	InFlight int   `json:"inFlight"`
}

type Coordinator struct {
	mutex    sync.Mutex
	inFlight int
	drained  chan struct{}
}

type Operation struct {
	coordinator *Coordinator
	completed   sync.Once
}

func (coordinator *Coordinator) Begin() (*Operation, bool) {
	coordinator.mutex.Lock()
	defer coordinator.mutex.Unlock()
	if coordinator.drained != nil {
		return nil, false
	}
	coordinator.inFlight++
	return &Operation{coordinator: coordinator}, true
}

func (operation *Operation) Complete() {
	operation.completed.Do(func() {
		coordinator := operation.coordinator
		coordinator.mutex.Lock()
		defer coordinator.mutex.Unlock()
		coordinator.inFlight--
		if coordinator.drained != nil && coordinator.inFlight == 0 {
			close(coordinator.drained)
		}
	})
}

func (coordinator *Coordinator) StartQuiesce() <-chan struct{} {
	coordinator.mutex.Lock()
	defer coordinator.mutex.Unlock()
	if coordinator.drained == nil {
		coordinator.drained = make(chan struct{})
		if coordinator.inFlight == 0 {
			close(coordinator.drained)
		}
	}
	return coordinator.drained
}

func (coordinator *Coordinator) Quiesce(ctx context.Context) error {
	drained := coordinator.StartQuiesce()
	select {
	case <-drained:
		return nil
	default:
	}
	// Cancelling a client request never reopens admission.
	select {
	case <-drained:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

func (coordinator *Coordinator) Snapshot() State {
	coordinator.mutex.Lock()
	defer coordinator.mutex.Unlock()
	phase := Running
	if coordinator.drained != nil {
		phase = Quiescing
		if coordinator.inFlight == 0 {
			phase = Quiesced
		}
	}
	return State{Phase: phase, InFlight: coordinator.inFlight}
}
