package resident

import (
	"context"
	"errors"
	"fmt"
	"reflect"
	"sort"
	"sync"
	"time"

	"voyahtune/unified-loader/agentwire"
	"voyahtune/unified-loader/lifecycle"
)

var ErrQuiescing = errors.New("runtime admission is closed")
var ErrUncertain = errors.New("existing native operation has not settled")
var ErrRestartRequired = errors.New("resident Java runtime is quiesced; process restart required")

type Record struct {
	Schema int              `json:"schema"`
	Target agentwire.Target `json:"target"`
	Plan   agentwire.Plan   `json:"plan"`
	Phase  string           `json:"phase"`
}

type Store interface {
	Load() ([]Record, error)
	Save(Record) error
	Remove(agentwire.Target) error
}

type Recovery struct {
	Status *agentwire.Status
	// SafeToAttach requires proof that neither an orphan helper nor mapped native code remains.
	SafeToAttach bool
}

type Backend interface {
	Recover(context.Context, Record) (Recovery, error)
	Attach(context.Context, Record) (agentwire.Status, error)
	Apply(context.Context, Record) (agentwire.Status, error)
	Quiesce(context.Context, agentwire.Target) (agentwire.Status, error)
}

type session struct {
	mutex     sync.Mutex
	record    Record
	admission *lifecycle.Operation
	retired   bool
}

type Manager struct {
	mutex       sync.Mutex
	sessions    map[string]*session
	stopping    bool
	store       Store
	backend     Backend
	alive       func(agentwire.Target) (bool, error)
	gate        lifecycle.Coordinator
	admitAttach func(agentwire.Target) error
}

type Option func(*Manager)

func WithAttachAdmission(admit func(agentwire.Target) error) Option {
	return func(manager *Manager) { manager.admitAttach = admit }
}

func key(target agentwire.Target) string {
	return fmt.Sprintf("%s:%d:%s:%d", target.BootID, target.PID, target.StartTicks, target.UID)
}

func New(store Store, backend Backend, alive func(agentwire.Target) (bool, error), options ...Option) (*Manager, error) {
	if store == nil || backend == nil || alive == nil {
		return nil, errors.New("resident store, backend and identity verifier required")
	}
	records, err := store.Load()
	if err != nil {
		return nil, err
	}
	manager := &Manager{sessions: map[string]*session{}, store: store, backend: backend, alive: alive}
	for _, option := range options {
		option(manager)
	}
	for _, record := range records {
		if err := validateRecord(record); err != nil {
			return nil, err
		}
		id := key(record.Target)
		if manager.sessions[id] != nil {
			return nil, errors.New("duplicate resident process record")
		}
		operation, _ := manager.gate.Begin()
		manager.sessions[id] = &session{record: cloneRecord(record), admission: operation}
	}
	return manager, nil
}

func validateRecord(record Record) error {
	if record.Schema != 1 {
		return errors.New("invalid resident record schema")
	}
	if _, err := agentwire.NewClient(record.Target); err != nil {
		return err
	}
	if err := record.Plan.Validate(record.Target.RuntimeID); err != nil {
		return err
	}
	switch record.Phase {
	case "new", "pending", "active", "quiesced":
		return nil
	default:
		return errors.New("invalid resident record phase")
	}
}

func (manager *Manager) Ensure(ctx context.Context, target agentwire.Target, desired agentwire.Plan) (agentwire.Status, error) {
	if _, err := agentwire.NewClient(target); err != nil {
		return agentwire.Status{}, err
	}
	if err := desired.Validate(target.RuntimeID); err != nil {
		return agentwire.Status{}, err
	}
	manager.mutex.Lock()
	if manager.stopping {
		manager.mutex.Unlock()
		return agentwire.Status{}, ErrQuiescing
	}
	current := manager.sessions[key(target)]
	if current == nil {
		if len(desired.Modules) == 0 {
			manager.mutex.Unlock()
			return agentwire.Status{Schema: 1, Target: target, State: "disabled", Modules: map[string]string{}}, nil
		}
		operation, accepted := manager.gate.Begin()
		if !accepted {
			manager.mutex.Unlock()
			return agentwire.Status{}, ErrQuiescing
		}
		current = &session{record: Record{Schema: 1, Target: target, Plan: agentwire.Plan{Schema: 1, RuntimeID: target.RuntimeID}, Phase: "new"}, admission: operation}
		manager.sessions[key(target)] = current
	}
	manager.mutex.Unlock()
	current.mutex.Lock()
	defer current.mutex.Unlock()
	if current.retired {
		return agentwire.Status{}, errors.New("target session ended")
	}
	if manager.isStopping() {
		return agentwire.Status{}, ErrQuiescing
	}
	live, err := manager.alive(current.record.Target)
	if err != nil {
		return agentwire.Status{}, err
	}
	if !live {
		if err := manager.retire(current); err != nil {
			return agentwire.Status{}, err
		}
		return agentwire.Status{}, errors.New("target process ended")
	}
	if current.record.Phase == "new" {
		if err := manager.store.Save(cloneRecord(current.record)); err != nil {
			return agentwire.Status{}, err
		}
	}
	recovery, err := manager.backend.Recover(ctx, cloneRecord(current.record))
	if err != nil {
		return agentwire.Status{}, err
	}
	if recovery.Status != nil {
		observed := *recovery.Status
		if !sameProcess(observed.Target, current.record.Target) {
			return observed, errors.New("resident process identity mismatch")
		}
		if !sameCode(observed.Target, target) {
			current.record.Target = observed.Target
			current.record.Plan = agentwire.Plan{Schema: 1, RuntimeID: observed.RuntimeID, Revision: observed.Revision}
			current.record.Phase = "pending"
			if err := manager.store.Save(cloneRecord(current.record)); err != nil {
				return observed, err
			}
			if err := manager.closeResident(ctx, current); err != nil {
				return observed, err
			}
			return observed, agentwire.ErrRuntimeChanged
		}
		if observed.State == "quiesced" || current.record.Phase == "quiesced" {
			return observed, ErrRestartRequired
		}
		if observed.Schema != 1 || observed.Error != "" || (observed.State != "ready" && observed.State != "starting") {
			return observed, errors.New("resident Java runtime requires cleanup and process restart")
		}
		if samePlan(current.record.Plan, desired) && confirm(observed, current.record.Target, current.record.Plan) == nil {
			if manager.isStopping() {
				return observed, ErrQuiescing
			}
			return observed, nil
		}
	} else if !recovery.SafeToAttach {
		return agentwire.Status{}, ErrUncertain
	} else if len(desired.Modules) == 0 {
		if err := manager.retire(current); err != nil {
			return agentwire.Status{}, err
		}
		return agentwire.Status{Schema: 1, Target: target, State: "disabled", Modules: map[string]string{}}, nil
	}
	if recovery.Status == nil && manager.admitAttach != nil {
		if err := manager.admitAttach(target); err != nil {
			return agentwire.Status{}, err
		}
	}
	current.record.Target = target
	revision := current.record.Plan.Revision
	if recovery.Status != nil && recovery.Status.Revision > revision {
		revision = recovery.Status.Revision
	}
	desired = normalizePlan(desired)
	desired.Revision = revision + 1
	current.record.Plan = desired
	current.record.Phase = "pending"
	if err := manager.store.Save(cloneRecord(current.record)); err != nil {
		if recovery.Status == nil && recovery.SafeToAttach {
			manager.retire(current)
		}
		return agentwire.Status{}, err
	}
	var status agentwire.Status
	if recovery.Status == nil {
		status, err = manager.backend.Attach(ctx, cloneRecord(current.record))
	} else {
		status, err = manager.backend.Apply(ctx, cloneRecord(current.record))
	}
	if err != nil {
		return status, err
	}
	if err := confirm(status, target, desired); err != nil {
		return status, err
	}
	if live, err := manager.alive(target); err != nil || !live {
		if err != nil {
			return status, err
		}
		return status, errors.New("target ended before active publication")
	}
	current.record.Phase = "active"
	if err := manager.store.Save(cloneRecord(current.record)); err != nil {
		return status, err
	}
	if manager.isStopping() {
		return status, ErrQuiescing
	}
	return status, nil
}

func confirm(status agentwire.Status, target agentwire.Target, plan agentwire.Plan) error {
	if status.Schema != 1 || status.Target != target || status.State != "ready" || status.Error != "" || status.Revision != plan.Revision || len(status.Modules) != len(plan.Modules) {
		return errors.New("complete current Java module acknowledgement required")
	}
	for _, module := range plan.Modules {
		if status.Modules[module] != "active" {
			return fmt.Errorf("module %s is not active", module)
		}
	}
	return nil
}

func (manager *Manager) closeResident(ctx context.Context, current *session) error {
	status, err := manager.backend.Quiesce(ctx, current.record.Target)
	if err != nil {
		return err
	}
	if status.Schema != 1 || status.Target != current.record.Target || status.State != "quiesced" || status.Error != "" || status.Modules == nil || len(status.Modules) != 0 {
		return errors.New("resident cleanup is not confirmed")
	}
	current.record.Phase = "quiesced"
	return manager.store.Save(cloneRecord(current.record))
}

func (manager *Manager) isStopping() bool {
	manager.mutex.Lock()
	defer manager.mutex.Unlock()
	return manager.stopping
}
func (manager *Manager) Snapshot() lifecycle.State { return manager.gate.Snapshot() }

func (manager *Manager) Sweep() error {
	manager.mutex.Lock()
	sessions := make([]*session, 0, len(manager.sessions))
	for _, current := range manager.sessions {
		sessions = append(sessions, current)
	}
	manager.mutex.Unlock()
	var failures []error
	for _, current := range sessions {
		if !current.mutex.TryLock() {
			continue
		}
		if !current.retired {
			live, err := manager.alive(current.record.Target)
			if err != nil {
				failures = append(failures, err)
			}
			if err == nil && !live {
				if err := manager.retire(current); err != nil {
					failures = append(failures, err)
				}
			}
		}
		current.mutex.Unlock()
	}
	return errors.Join(failures...)
}

func (manager *Manager) Quiesce(ctx context.Context) error {
	manager.mutex.Lock()
	if !manager.stopping {
		manager.stopping = true
		manager.gate.StartQuiesce()
		for _, current := range manager.sessions {
			go manager.drain(current)
		}
	}
	manager.mutex.Unlock()
	return manager.gate.Quiesce(ctx)
}

func (manager *Manager) drain(current *session) {
	for {
		current.mutex.Lock()
		done := current.retired
		if !done {
			done = manager.drainOnce(current) == nil
		}
		if done {
			current.admission.Complete()
		}
		current.mutex.Unlock()
		if done {
			return
		}
		time.Sleep(250 * time.Millisecond)
	}
}

func (manager *Manager) drainOnce(current *session) error {
	live, err := manager.alive(current.record.Target)
	if err != nil {
		return err
	}
	if !live {
		return manager.retire(current)
	}
	recovery, err := manager.backend.Recover(context.Background(), cloneRecord(current.record))
	if err != nil {
		return err
	}
	if recovery.Status == nil {
		if recovery.SafeToAttach {
			return manager.retire(current)
		}
		return ErrUncertain
	}
	observed := recovery.Status
	if !sameProcess(observed.Target, current.record.Target) {
		return errors.New("cleanup endpoint process identity mismatch")
	}
	current.record.Target = observed.Target
	current.record.Plan.RuntimeID = observed.RuntimeID
	if observed.State == "quiesced" && observed.Schema == 1 && observed.Error == "" && observed.Modules != nil && len(observed.Modules) == 0 {
		current.record.Phase = "quiesced"
		return manager.store.Save(cloneRecord(current.record))
	}
	return manager.closeResident(context.Background(), current)
}

func (manager *Manager) retire(current *session) error {
	if err := manager.store.Remove(current.record.Target); err != nil {
		return err
	}
	manager.mutex.Lock()
	if manager.sessions[key(current.record.Target)] == current {
		delete(manager.sessions, key(current.record.Target))
	}
	manager.mutex.Unlock()
	current.retired = true
	current.admission.Complete()
	return nil
}

func sameProcess(left, right agentwire.Target) bool { return key(left) == key(right) }
func sameCode(left, right agentwire.Target) bool {
	return left.NativeID == right.NativeID && left.RuntimeID == right.RuntimeID
}
func samePlan(left, right agentwire.Plan) bool {
	left = normalizePlan(left)
	right = normalizePlan(right)
	left.Revision = 0
	right.Revision = 0
	return reflect.DeepEqual(left, right)
}
func normalizePlan(plan agentwire.Plan) agentwire.Plan {
	if plan.Modules == nil {
		plan.Modules = []string{}
	} else {
		plan.Modules = append([]string{}, plan.Modules...)
	}
	sort.Strings(plan.Modules)
	configuration := map[string]string{}
	for key, value := range plan.Configuration {
		configuration[key] = value
	}
	plan.Configuration = configuration
	return plan
}
func cloneRecord(record Record) Record { record.Plan = normalizePlan(record.Plan); return record }
