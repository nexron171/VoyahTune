package main

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sync/atomic"
	"syscall"

	"voyahtune/unified-loader/agentwire"
	"voyahtune/unified-loader/injecthelper"
	"voyahtune/unified-loader/remoteattach"
)

type cancellationPlatform struct {
	stage        string
	target       agentwire.Target
	cancel       context.CancelFunc
	entered      chan struct{}
	release      chan struct{}
	nativeCalls  atomic.Int32
	cleanupCalls atomic.Int32
}

func (platform *cancellationPlatform) Self() (agentwire.Target, error) {
	return agentwire.Target{BootID: platform.target.BootID, PID: 42, UID: 0, StartTicks: "101"}, nil
}
func (*cancellationPlatform) Verify(agentwire.Target) error { return nil }
func (*cancellationPlatform) ValidateFile(string) error     { return nil }
func (platform *cancellationPlatform) Connect(agentwire.Target) (injecthelper.Agent, error) {
	return platform, nil
}
func (*cancellationPlatform) Resolve(agentwire.Target) (remoteattach.Functions, error) {
	return remoteattach.Functions{}, nil
}
func (platform *cancellationPlatform) pause() {
	platform.cancel()
	close(platform.entered)
	<-platform.release
}
func (platform *cancellationPlatform) Inject(ctx context.Context, _ remoteattach.Request, _ remoteattach.Functions, progress func(remoteattach.Stage)) (remoteattach.NativeResult, error) {
	if platform.nativeCalls.Add(1) > 1 {
		return remoteattach.NativeResult{}, errors.New("competing native operation")
	}
	// Native ptrace results are the external boundary here; ART restore/detach is checked on Android.
	outcome := remoteattach.NativeResult{Attached: true, RegistersSaved: true, Restored: true, Detached: true, LibraryLoaded: true, WorkerStarted: true, SafePC: 4096}
	switch platform.stage {
	case "attach":
		progress(remoteattach.Attached)
		outcome.LibraryLoaded, outcome.WorkerStarted = false, false
	case "save-registers":
		progress(remoteattach.Saved)
		outcome.LibraryLoaded, outcome.WorkerStarted = false, false
	case "remote-load":
		progress(remoteattach.Loading)
		outcome.WorkerStarted = false
	case "worker-start":
		progress(remoteattach.WorkerStarting)
	default:
		return outcome, nil
	}
	platform.pause()
	return outcome, ctx.Err()
}
func (*cancellationPlatform) Status(context.Context) (agentwire.Status, error) {
	return agentwire.Status{}, syscall.ECONNREFUSED
}
func (platform *cancellationPlatform) Apply(ctx context.Context, _ agentwire.Plan) (agentwire.Status, error) {
	platform.pause()
	return agentwire.Status{Schema: 1, Target: platform.target, State: "starting", Revision: 1, Modules: map[string]string{}}, ctx.Err()
}
func (platform *cancellationPlatform) Quiesce(ctx context.Context) (agentwire.Status, error) {
	if ctx.Err() != nil {
		return agentwire.Status{}, errors.New("cleanup inherited cancelled client context")
	}
	platform.cleanupCalls.Add(1)
	return agentwire.Status{Schema: 1, Target: platform.target, State: "quiesced", Revision: 1, Modules: map[string]string{}}, nil
}

func attachCancellation(data json.RawMessage) (any, error) {
	var input struct {
		CancelAt string `json:"cancelAt"`
	}
	if err := json.Unmarshal(data, &input); err != nil {
		return nil, err
	}
	directory, err := os.MkdirTemp("", "helper-cancellation-")
	if err != nil {
		return nil, err
	}
	defer os.RemoveAll(directory)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	target := agentwire.Target{BootID: "boot", PID: 41, UID: 1000, StartTicks: "100", NativeID: "native", RuntimeID: "java"}
	platform := &cancellationPlatform{stage: input.CancelAt, target: target, cancel: cancel, entered: make(chan struct{}), release: make(chan struct{})}
	engine := injecthelper.Engine{Platform: platform}
	request := injecthelper.Request{Schema: 1, RequestID: "cancel-contract", Target: target,
		LibraryPath: filepath.Join(directory, "agent.so"), DEXPath: filepath.Join(directory, "agents.dex"),
		AcquireTimeoutMillis: 1000, StartupTimeoutMillis: 1000,
		Plan: agentwire.Plan{Schema: 1, RuntimeID: "java", Revision: 1, Modules: []string{"phone"}}}
	type completion struct {
		receipt injecthelper.Receipt
		failure error
	}
	completed := make(chan completion, 1)
	go func() {
		receipt, failure := engine.Run(ctx, request, filepath.Join(directory, "receipt.json"))
		completed <- completion{receipt, failure}
	}()
	select {
	case <-platform.entered:
	case result := <-completed:
		return nil, errors.Join(errors.New("helper did not reach cancellation boundary"), result.failure)
	}
	retry := request
	retry.RequestID = "competing-contract"
	_, retryError := engine.Run(context.Background(), retry, filepath.Join(directory, "retry.json"))
	retriedWhileUncertain := platform.nativeCalls.Load() > 1
	close(platform.release)
	result := <-completed
	if result.failure == nil || retryError == nil {
		return nil, errors.New("cancelled/competing attach was accepted")
	}
	if result.receipt.Native.WorkerStarted && platform.cleanupCalls.Load() == 0 {
		return nil, errors.New("target worker cleanup was skipped")
	}
	saved, err := os.ReadFile(filepath.Join(directory, "receipt.json"))
	if err != nil {
		return nil, err
	}
	var persisted injecthelper.Receipt
	if err := json.Unmarshal(saved, &persisted); err != nil {
		return nil, err
	}
	if !persisted.Completed || persisted.RequestID != request.RequestID {
		return nil, errors.New("cancelled helper receipt was not persisted")
	}
	attempt := injecthelper.NewAttempt(request)
	attempt.RecordHelperExit(persisted, 42, 1)
	attempt.ConfirmTargetIdentity(target)
	status := attempt.Snapshot()
	return map[string]any{"state": status.State, "activePublished": status.ActivePublished,
		"traceeRestored": persisted.Native.Restored, "detached": persisted.Native.Detached,
		"retryWhileUncertain": retriedWhileUncertain}, nil
}
