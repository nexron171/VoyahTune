package resident

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"

	"voyahtune/unified-loader/agentwire"
	"voyahtune/unified-loader/lifecycle"
)

type memoryStore struct {
	mutex   sync.Mutex
	records map[string]Record
	fail    bool
}

func (store *memoryStore) Load() ([]Record, error) {
	store.mutex.Lock()
	defer store.mutex.Unlock()
	var records []Record
	for _, record := range store.records {
		records = append(records, record)
	}
	return records, nil
}
func (store *memoryStore) Save(record Record) error {
	store.mutex.Lock()
	defer store.mutex.Unlock()
	if store.fail {
		return errors.New("disk full")
	}
	store.records[key(record.Target)] = record
	return nil
}
func (store *memoryStore) Remove(target agentwire.Target) error {
	store.mutex.Lock()
	defer store.mutex.Unlock()
	delete(store.records, key(target))
	return nil
}

type fakeBackend struct {
	mutex                   sync.Mutex
	store                   *memoryStore
	status                  *agentwire.Status
	starts, applies, closes int
	entered                 chan struct{}
	attachRelease           chan struct{}
	drainRelease            chan struct{}
	drainEntered            chan struct{}
	recoveryRelease         chan struct{}
	recoveryEntered         chan struct{}
	partial                 bool
}

func (backend *fakeBackend) Recover(ctx context.Context, record Record) (Recovery, error) {
	if backend.recoveryEntered != nil {
		select {
		case backend.recoveryEntered <- struct{}{}:
		default:
		}
	}
	if backend.recoveryRelease != nil {
		<-backend.recoveryRelease
	}
	backend.mutex.Lock()
	defer backend.mutex.Unlock()
	if backend.status == nil {
		return Recovery{SafeToAttach: true}, nil
	}
	status := *backend.status
	status.Modules = cloneModules(status.Modules)
	return Recovery{Status: &status}, nil
}
func (backend *fakeBackend) Attach(ctx context.Context, record Record) (agentwire.Status, error) {
	records, _ := backend.store.Load()
	persisted := false
	for _, saved := range records {
		if saved.Target == record.Target && saved.Phase == "pending" && saved.Plan.Revision == record.Plan.Revision {
			persisted = true
		}
	}
	if !persisted {
		return agentwire.Status{}, errors.New("native attach preceded durable admission")
	}
	if backend.entered != nil {
		close(backend.entered)
	}
	if backend.attachRelease != nil {
		<-backend.attachRelease
	}
	backend.mutex.Lock()
	defer backend.mutex.Unlock()
	backend.starts++
	return backend.ack(record), nil
}
func (backend *fakeBackend) Apply(ctx context.Context, record Record) (agentwire.Status, error) {
	backend.mutex.Lock()
	defer backend.mutex.Unlock()
	backend.applies++
	return backend.ack(record), nil
}
func (backend *fakeBackend) ack(record Record) agentwire.Status {
	status := agentwire.Status{Schema: 1, Target: record.Target, State: "ready", Revision: record.Plan.Revision, Modules: map[string]string{}}
	for _, module := range record.Plan.Modules {
		status.Modules[module] = "active"
	}
	if backend.partial {
		delete(status.Modules, record.Plan.Modules[len(record.Plan.Modules)-1])
	}
	backend.status = &status
	return status
}
func (backend *fakeBackend) Quiesce(ctx context.Context, target agentwire.Target) (agentwire.Status, error) {
	if backend.drainEntered != nil {
		select {
		case backend.drainEntered <- struct{}{}:
		default:
		}
	}
	if backend.drainRelease != nil {
		<-backend.drainRelease
	}
	backend.mutex.Lock()
	defer backend.mutex.Unlock()
	backend.closes++
	status := agentwire.Status{Schema: 1, Target: target, State: "quiesced", Modules: map[string]string{}}
	backend.status = &status
	return status, nil
}
func cloneModules(values map[string]string) map[string]string {
	result := map[string]string{}
	for key, value := range values {
		result[key] = value
	}
	return result
}
func target() agentwire.Target {
	return agentwire.Target{BootID: "boot", PID: 42, UID: 1000, StartTicks: "100", NativeID: "native", RuntimeID: "java"}
}
func plan(modules ...string) agentwire.Plan {
	return agentwire.Plan{Schema: 1, RuntimeID: "java", Modules: modules, Configuration: map[string]string{}}
}
func setup(t *testing.T) (*Manager, *fakeBackend, *memoryStore) {
	t.Helper()
	store := &memoryStore{records: map[string]Record{}}
	backend := &fakeBackend{store: store}
	manager, err := New(store, backend, func(agentwire.Target) (bool, error) { return true, nil })
	if err != nil {
		t.Fatal(err)
	}
	return manager, backend, store
}
func drain(t *testing.T, manager *Manager) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if err := manager.Quiesce(ctx); err != nil {
		t.Fatal(err)
	}
}

func TestEmptyPlanRemainsReusableUntilExplicitQuiesce(t *testing.T) {
	manager, backend, _ := setup(t)
	if _, err := manager.Ensure(context.Background(), target(), plan("drive-reset", "apollo")); err != nil {
		t.Fatal(err)
	}
	if _, err := manager.Ensure(context.Background(), target(), plan()); err != nil {
		t.Fatal(err)
	}
	if _, err := manager.Ensure(context.Background(), target(), plan("apollo")); err != nil {
		t.Fatal(err)
	}
	if backend.starts != 1 || backend.applies != 2 || backend.closes != 0 {
		t.Fatal("module removal closed the reusable resident", backend.starts, backend.applies, backend.closes)
	}
	drain(t, manager)
	if manager.Snapshot() != (lifecycle.State{Phase: lifecycle.Quiesced}) || backend.closes != 1 {
		t.Fatal(manager.Snapshot(), backend.closes)
	}
	if _, err := manager.Ensure(context.Background(), target(), plan("apollo")); !errors.Is(err, ErrQuiescing) {
		t.Fatal("drained admission reopened", err)
	}
}

func TestHelperReturnDoesNotReleaseTargetWorkerAdmission(t *testing.T) {
	manager, backend, _ := setup(t)
	backend.entered = make(chan struct{})
	backend.attachRelease = make(chan struct{})
	backend.drainRelease = make(chan struct{})
	backend.drainEntered = make(chan struct{}, 1)
	completed := make(chan error, 1)
	go func() { _, err := manager.Ensure(context.Background(), target(), plan("launcher")); completed <- err }()
	<-backend.entered
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Millisecond)
	if !errors.Is(manager.Quiesce(ctx), context.DeadlineExceeded) {
		t.Fatal("quiesce acknowledged an active native helper")
	}
	cancel()
	if manager.Snapshot().InFlight != 1 {
		t.Fatal(manager.Snapshot())
	}
	close(backend.attachRelease)
	<-completed
	<-backend.drainEntered
	if manager.Snapshot().InFlight != 1 {
		t.Fatal("helper exit released target worker")
	}
	if _, err := manager.Ensure(context.Background(), target(), plan()); !errors.Is(err, ErrQuiescing) {
		t.Fatal(err)
	}
	close(backend.drainRelease)
	drain(t, manager)
}

func TestRecoveredPendingOperationBlocksInstallUntilHelperSettles(t *testing.T) {
	store := &memoryStore{records: map[string]Record{key(target()): {Schema: 1, Target: target(), Plan: plan("windows"), Phase: "pending"}}}
	backend := &fakeBackend{store: store, recoveryRelease: make(chan struct{})}
	manager, err := New(store, backend, func(agentwire.Target) (bool, error) { return true, nil })
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Millisecond)
	if !errors.Is(manager.Quiesce(ctx), context.DeadlineExceeded) {
		t.Fatal("loader restart forgot admitted orphan helper")
	}
	cancel()
	close(backend.recoveryRelease)
	drain(t, manager)
	if backend.starts != 0 || backend.closes != 0 {
		t.Fatal("recovery injected new code", backend.starts, backend.closes)
	}
}

func TestMissingModuleAckCannotBecomeActive(t *testing.T) {
	manager, backend, _ := setup(t)
	backend.partial = true
	if status, err := manager.Ensure(context.Background(), target(), plan("drive-reset", "apollo")); err == nil || status.State == "active" {
		t.Fatal("partial plan accepted", status, err)
	}
	drain(t, manager)
}

func TestCodeReplacementQuiescesObservedRuntimeWithoutAttach(t *testing.T) {
	manager, backend, _ := setup(t)
	old := target()
	old.NativeID = "previous-native"
	old.RuntimeID = "previous-java"
	backend.status = &agentwire.Status{Schema: 1, Target: old, State: "ready", Modules: map[string]string{"windows": "active"}}
	if _, err := manager.Ensure(context.Background(), target(), plan("windows")); !errors.Is(err, agentwire.ErrRuntimeChanged) {
		t.Fatal(err)
	}
	if backend.starts != 0 || backend.applies != 0 || backend.closes != 1 {
		t.Fatal("mixed runtimes in a live process", backend.starts, backend.applies, backend.closes)
	}
	drain(t, manager)
}

func TestDurableAdmissionFailurePreventsNativeAttach(t *testing.T) {
	manager, backend, store := setup(t)
	store.fail = true
	if _, err := manager.Ensure(context.Background(), target(), plan("phone")); err == nil {
		t.Fatal("disk-full admission accepted")
	}
	if backend.starts != 0 {
		t.Fatal("unrecorded native attach")
	}
	drain(t, manager)
}

func TestEquivalentPlansReuseAckAndConfigurationChangesAdvanceRevision(t *testing.T) {
	manager, backend, _ := setup(t)
	first, err := manager.Ensure(context.Background(), target(), plan("drive-reset", "apollo"))
	if err != nil {
		t.Fatal(err)
	}
	second, err := manager.Ensure(context.Background(), target(), plan("apollo", "drive-reset"))
	if err != nil || second.Revision != first.Revision || backend.applies != 0 {
		t.Fatal(second, err, backend.applies)
	}
	changed := plan("apollo", "drive-reset")
	changed.Configuration["deviceModel"] = "h97c"
	third, err := manager.Ensure(context.Background(), target(), changed)
	if err != nil || third.Revision <= second.Revision || backend.applies != 1 {
		t.Fatal(third, err, backend.applies)
	}
	drain(t, manager)
}

func TestAuthenticatedControlDrainsResidentManager(t *testing.T) {
	manager, backend, _ := setup(t)
	if _, err := manager.Ensure(context.Background(), target(), plan("windows")); err != nil {
		t.Fatal(err)
	}
	backend.drainRelease = make(chan struct{})
	identity := lifecycle.ControlIdentity{BootID: "boot", PID: 123, StartTicks: "456"}
	control := lifecycle.NewControl(identity, manager)
	request := lifecycle.ControlRequest{Schema: 1, RequestID: "resident-control", Identity: identity, Command: "quiesce", TimeoutMillis: 5}
	response := control.Execute(context.Background(), 0, request)
	if response.Error != "deadline-exceeded" || response.State.Phase != lifecycle.Quiescing || response.State.InFlight != 1 {
		t.Fatal(response)
	}
	if _, err := manager.Ensure(context.Background(), target(), plan()); !errors.Is(err, ErrQuiescing) {
		t.Fatal("client timeout reopened admission", err)
	}
	close(backend.drainRelease)
	drain(t, manager)
}

func TestEmptyDesiredPlanDoesNotBootstrapARecoveredAbsentRuntime(t *testing.T) {
	store := &memoryStore{records: map[string]Record{key(target()): {Schema: 1, Target: target(), Plan: plan("phone"), Phase: "pending"}}}
	backend := &fakeBackend{store: store}
	manager, err := New(store, backend, func(agentwire.Target) (bool, error) { return true, nil })
	if err != nil {
		t.Fatal(err)
	}
	if _, err := manager.Ensure(context.Background(), target(), plan()); err != nil {
		t.Fatal(err)
	}
	if backend.starts != 0 {
		t.Fatal("empty desired set injected a native runtime")
	}
	drain(t, manager)
}

func TestChangedProcessIdentityReleasesOldRecordWithoutRpc(t *testing.T) {
	store := &memoryStore{records: map[string]Record{key(target()): {Schema: 1, Target: target(), Plan: plan("phone"), Phase: "active"}}}
	backend := &fakeBackend{store: store}
	manager, err := New(store, backend, func(agentwire.Target) (bool, error) { return false, nil })
	if err != nil {
		t.Fatal(err)
	}
	drain(t, manager)
	records, _ := store.Load()
	if len(records) != 0 || backend.closes != 0 {
		t.Fatal("PID reuse sent RPC to a different process", records, backend.closes)
	}
}

func TestSweepRemovesEndedResidentsWithoutClosingLiveOnes(t *testing.T) {
	store := &memoryStore{records: map[string]Record{key(target()): {Schema: 1, Target: target(), Plan: plan("phone"), Phase: "active"}}}
	backend := &fakeBackend{store: store}
	live := true
	manager, err := New(store, backend, func(agentwire.Target) (bool, error) { return live, nil })
	if err != nil {
		t.Fatal(err)
	}
	if err := manager.Sweep(); err != nil || manager.Snapshot().InFlight != 1 {
		t.Fatal(manager.Snapshot(), err)
	}
	live = false
	if err := manager.Sweep(); err != nil || manager.Snapshot().InFlight != 0 {
		t.Fatal(manager.Snapshot(), err)
	}
	if backend.closes != 0 {
		t.Fatal("sweep sent cleanup to an ended process")
	}
	drain(t, manager)
}

func TestReuseCannotPublishActiveAfterQuiesceStarted(t *testing.T) {
	manager, backend, _ := setup(t)
	if _, err := manager.Ensure(context.Background(), target(), plan("phone")); err != nil {
		t.Fatal(err)
	}
	backend.recoveryRelease = make(chan struct{})
	backend.recoveryEntered = make(chan struct{}, 1)
	result := make(chan error, 1)
	go func() { _, err := manager.Ensure(context.Background(), target(), plan("phone")); result <- err }()
	<-backend.recoveryEntered
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Millisecond)
	if !errors.Is(manager.Quiesce(ctx), context.DeadlineExceeded) {
		t.Fatal("quiesce skipped an admitted status operation")
	}
	cancel()
	close(backend.recoveryRelease)
	if err := <-result; !errors.Is(err, ErrQuiescing) {
		t.Fatal("reused ready was published after admission closed", err)
	}
	drain(t, manager)
}

func TestColdAttachBudgetCannotBlockReuseAndCleanup(t *testing.T) {
	store := &memoryStore{records: map[string]Record{}}
	backend := &fakeBackend{store: store}
	allowed := false
	checks := 0
	manager, err := New(store, backend, func(agentwire.Target) (bool, error) { return true, nil }, WithAttachAdmission(func(agentwire.Target) error {
		checks++
		if !allowed {
			return errors.New("attempt budget blocked")
		}
		return nil
	}))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := manager.Ensure(context.Background(), target(), plan("phone")); err == nil || backend.starts != 0 {
		t.Fatal("denied cold attach ran", err, backend.starts)
	}
	allowed = true
	if _, err := manager.Ensure(context.Background(), target(), plan("phone")); err != nil {
		t.Fatal(err)
	}
	allowed = false
	if _, err := manager.Ensure(context.Background(), target(), plan("phone")); err != nil {
		t.Fatal("attempt budget blocked live status reuse", err)
	}
	if _, err := manager.Ensure(context.Background(), target(), plan()); err != nil {
		t.Fatal("attempt budget blocked module removal", err)
	}
	drain(t, manager)
	if checks != 2 || backend.starts != 1 {
		t.Fatal(checks, backend.starts)
	}
}

func TestQuiescedSameCodeRequiresRestartWithoutReportingCodeMismatch(t *testing.T) {
	manager, backend, _ := setup(t)
	backend.status = &agentwire.Status{Schema: 1, Target: target(), State: "quiesced", Modules: map[string]string{}}
	if _, err := manager.Ensure(context.Background(), target(), plan("phone")); err == nil || errors.Is(err, agentwire.ErrRuntimeChanged) {
		t.Fatalf("same-code quiesced resident reported as code mismatch: %v", err)
	}
	if backend.starts != 0 || backend.applies != 0 {
		t.Fatal("quiesced resident was reinjected")
	}
	drain(t, manager)
}
