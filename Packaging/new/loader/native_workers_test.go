package main

import (
	"context"
	"os"
	"path/filepath"
	"reflect"
	"strconv"
	"sync"
	"testing"
	"time"

	"voyahtune/unified-loader/agentwire"
	"voyahtune/unified-loader/lifecycle"
)

type residentRequest struct {
	target  agentwire.Target
	plan    agentwire.Plan
	release chan struct{}
}

type pendingResidents struct {
	requests chan residentRequest
}

func (residents *pendingResidents) Ensure(ctx context.Context, target agentwire.Target, plan agentwire.Plan) (agentwire.Status, error) {
	request := residentRequest{target: target, plan: plan, release: make(chan struct{})}
	select {
	case residents.requests <- request:
	case <-ctx.Done():
		return agentwire.Status{}, ctx.Err()
	}
	select {
	case <-request.release:
	case <-ctx.Done():
		return agentwire.Status{}, ctx.Err()
	}
	status := agentwire.Status{Schema: 1, Target: target, State: "ready", Revision: 1, Modules: map[string]string{}}
	for _, module := range plan.Modules {
		status.Modules[module] = "active"
	}
	return status, nil
}

func (residents *pendingResidents) Quiesce(context.Context) error { return nil }
func (residents *pendingResidents) Snapshot() lifecycle.State {
	return lifecycle.State{Phase: lifecycle.Running}
}
func (residents *pendingResidents) Sweep() error { return nil }

func workerFixture(t *testing.T) (*agentWorkers, *pendingResidents, context.CancelFunc) {
	t.Helper()
	manager, _ := supervisorFixture(t, nil)
	residents := &pendingResidents{requests: make(chan residentRequest, 8)}
	manager.engine = residents
	ctx, cancel := context.WithCancel(context.Background())
	workers := &agentWorkers{manager: manager, context: ctx, group: new(sync.WaitGroup), targets: map[string]bool{}}
	t.Cleanup(func() { cancel(); workers.group.Wait() })
	return workers, residents, cancel
}

func workerProcess(t *testing.T, manager *nativeSupervisor, pid int, name, ticks string) {
	t.Helper()
	fakeProcess(t, manager.procRoot, pid, name, ticks)
	testFile(t, filepath.Join(manager.procRoot, strconv.Itoa(pid), "status"), "Uid:\t10000\t10000\t10000\t10000\n", 0600)
	testTargetELF(t, manager.procRoot, pid, 2, 183)
}

func awaitResident(t *testing.T, residents *pendingResidents) residentRequest {
	t.Helper()
	select {
	case request := <-residents.requests:
		return request
	case <-time.After(3 * time.Second):
		t.Fatal("available process did not reach the resident while other agents were pending")
		return residentRequest{}
	}
}

func TestAgentWorkersStartIndependentlyOfACC(t *testing.T) {
	for _, accPresent := range []bool{false, true} {
		t.Run(strconv.FormatBool(accPresent), func(t *testing.T) {
			workers, residents, _ := workerFixture(t)
			agents := []nativeAgentConfig{
				{ID: "acc", Target: "com.qinggan.canbus.service", Modules: []string{"acc"}, TimeoutSeconds: 35},
				{ID: "rds", Target: "com.pateo.rdsapp", Modules: []string{"rds"}, TimeoutSeconds: 35},
				{ID: "statusbar", Target: "com.qinggan.systemui", Modules: []string{"statusbar"}, TimeoutSeconds: 35},
			}
			expected := map[int]bool{}
			for index, agent := range agents {
				if agent.ID == "acc" && !accPresent {
					continue
				}
				pid := 42 + index
				workerProcess(t, workers.manager, pid, agent.Target, "100")
				expected[pid] = true
			}
			workers.start(agents)
			for len(expected) > 0 {
				request := awaitResident(t, residents)
				if !expected[request.target.PID] {
					t.Fatal("duplicate or unexpected process admission", request.target)
				}
				delete(expected, request.target.PID)
			}
			workerProcess(t, workers.manager, 50, "com.example.navigation", "100")
			workers.manager.readSetting = func(context.Context, string) string { return "com.example.navigation" }
			workers.group.Add(1)
			go func() {
				defer workers.group.Done()
				workers.watchDynamic()
			}()
			if request := awaitResident(t, residents); request.target.PID != 50 {
				t.Fatal("dynamic agent waited for existing residents", request.target)
			}
		})
	}
}

func TestAgentWorkersInstallSharedProcessTogether(t *testing.T) {
	workers, residents, _ := workerFixture(t)
	name := "com.qinggan.app.vehiclesetting"
	testFile(t, workers.manager.apolloPath, "v=1\nboot=boot\nenabled=1\n", 0600)
	workerProcess(t, workers.manager, 42, name, "100")
	workers.start([]nativeAgentConfig{
		{ID: "drive-reset", Target: name, Modules: []string{"drive-reset"}, TimeoutSeconds: 35},
		{ID: "apollo", Target: name, Modules: []string{"apollo"}, Condition: "apollo", TimeoutSeconds: 35},
	})
	request := awaitResident(t, residents)
	if !reflect.DeepEqual(request.plan.Modules, []string{"apollo", "drive-reset"}) {
		t.Fatal("incomplete shared-process plan", request.plan)
	}
	select {
	case duplicate := <-residents.requests:
		t.Fatal("shared process admitted concurrently", duplicate.target)
	case <-time.After(50 * time.Millisecond):
	}
}

func TestAgentWorkersDetectLateAndRestartedProcess(t *testing.T) {
	workers, residents, _ := workerFixture(t)
	agent := nativeAgentConfig{ID: "phone", Target: "com.qinggan.bluetoothphone", Modules: []string{"phone"}, TimeoutSeconds: 35}
	workers.start([]nativeAgentConfig{agent})
	workerProcess(t, workers.manager, 42, agent.Target, "100")
	first := awaitResident(t, residents)
	if first.target.PID != 42 || first.target.StartTicks != "100" {
		t.Fatal(first.target)
	}
	if err := os.RemoveAll(filepath.Join(workers.manager.procRoot, "42")); err != nil {
		t.Fatal(err)
	}
	workerProcess(t, workers.manager, 43, agent.Target, "200")
	close(first.release)
	restarted := awaitResident(t, residents)
	if restarted.target.PID != 43 || restarted.target.StartTicks != "200" {
		t.Fatal("restarted process retained stale identity", restarted.target)
	}
}

func TestAgentWorkersHonorCancelledStartup(t *testing.T) {
	workers, residents, cancel := workerFixture(t)
	agent := nativeAgentConfig{ID: "phone", Target: "com.qinggan.bluetoothphone", Modules: []string{"phone"}}
	workerProcess(t, workers.manager, 42, agent.Target, "100")
	cancel()
	workers.start([]nativeAgentConfig{agent})
	workers.group.Wait()
	select {
	case request := <-residents.requests:
		t.Fatal("cancelled startup reached a resident", request.target)
	default:
	}
}
