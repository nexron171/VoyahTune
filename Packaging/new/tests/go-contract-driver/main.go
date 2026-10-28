package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"strconv"

	"voyahtune/unified-loader/agentwire"
	"voyahtune/unified-loader/injecthelper"
	"voyahtune/unified-loader/lifecycle"
	"voyahtune/unified-loader/moduleplan"
	"voyahtune/unified-loader/remoteattach"
)

type testCase struct {
	ID       string          `json:"id"`
	Contract string          `json:"contract"`
	Input    json.RawMessage `json:"input"`
}

type result struct {
	ID       string `json:"id"`
	Observed any    `json:"observed,omitempty"`
	Error    string `json:"error,omitempty"`
}

func execute(test testCase) (any, error) {
	switch test.Contract {
	case "runtime.attach":
		return attachCancellation(test.Input)
	case "runtime.identity":
		return identity(test.Input)
	case "runtime.backend-exclusion":
		return backendExclusion(test.Input)
	case "runtime.readiness":
		return readiness(test.Input)
	case "keyboard.selection":
		var input struct {
			Previous *struct {
				Mode string
				Boot string
				PID  int
			} `json:"previous"`
			Setting    string `json:"setting"`
			Boot       string `json:"boot"`
			StartTicks int    `json:"startTicks"`
		}
		if err := json.Unmarshal(test.Input, &input); err != nil {
			return nil, err
		}
		identity := moduleplan.ProcessIdentity{BootID: input.Boot, PID: 42, StartTicks: strconv.Itoa(input.StartTicks)}
		var previous *moduleplan.KeyboardSelection
		if input.Previous != nil {
			previous = &moduleplan.KeyboardSelection{Identity: moduleplan.ProcessIdentity{BootID: input.Previous.Boot, PID: 42,
				StartTicks: strconv.Itoa(input.Previous.PID)}, Mode: input.Previous.Mode}
		}
		return map[string]any{"mode": moduleplan.KeyboardMode(previous, identity, func() string { return input.Setting })}, nil
	case "runtime.quiesce":
		var input struct {
			Events []string `json:"events"`
		}
		if err := json.Unmarshal(test.Input, &input); err != nil {
			return nil, err
		}
		return quiesce(input.Events)
	case "runtime.control":
		return control(test.Input)
	default:
		return nil, fmt.Errorf("Go implementation unavailable: %s", test.Contract)
	}
}

func readiness(data json.RawMessage) (any, error) {
	var input struct {
		Events []string `json:"events"`
	}
	if err := json.Unmarshal(data, &input); err != nil {
		return nil, err
	}
	target := agentwire.Target{BootID: "boot", PID: 41, UID: 1000, StartTicks: "100", NativeID: "native", RuntimeID: "java"}
	request := injecthelper.Request{Schema: 1, RequestID: "contract-request", Target: target,
		Plan: agentwire.Plan{Schema: 1, RuntimeID: "java", Revision: 1, Modules: []string{"module-1", "module-2"}}}
	receipt := injecthelper.Receipt{Schema: 1, RequestID: request.RequestID, Target: target, Stage: "active", Completed: true,
		Helper: agentwire.Target{BootID: "boot", PID: 42, UID: 0, StartTicks: "101"},
		Native: remoteattach.NativeResult{Attached: true, RegistersSaved: true, Restored: true, Detached: true,
			LibraryLoaded: true, WorkerStarted: true, SafePC: 4096},
		Status: &agentwire.Status{Schema: 1, Target: target, State: "starting", Revision: 1, Modules: map[string]string{}}}
	attempt := injecthelper.NewAttempt(request)
	for _, event := range input.Events {
		switch event {
		case "module-ready":
			receipt.Status.State = "ready"
			receipt.Status.Modules = map[string]string{"module-1": "active", "module-2": "active"}
		case "module-1-ready":
			receipt.Status.Modules["module-1"] = "active"
		case "module-2-error":
			receipt.Status.State = "failed"
			receipt.Status.Error = "module-2-error"
		case "helper-exit-0":
			attempt.RecordHelperExit(receipt, 42, 0)
		case "helper-exit-7":
			attempt.RecordHelperExit(receipt, 42, 7)
		case "identity-same":
			attempt.ConfirmTargetIdentity(target)
		case "identity-changed":
			changed := target
			changed.StartTicks = "101"
			attempt.ConfirmTargetIdentity(changed)
		default:
			return nil, fmt.Errorf("unknown readiness event: %s", event)
		}
	}
	state := attempt.Snapshot()
	return map[string]any{"state": state.State, "activePublished": state.ActivePublished}, nil
}

func quiesce(events []string) (any, error) {
	coordinator := new(lifecycle.Coordinator)
	operations := map[string]*lifecycle.Operation{}
	for _, event := range events {
		switch event {
		case "begin", "begin-helper", "begin-target-worker":
			operation, ok := coordinator.Begin()
			if !ok {
				return nil, errors.New("operation was rejected")
			}
			operations[event] = operation
		case "quiesce":
			coordinator.StartQuiesce()
		case "complete", "complete-helper":
			name := "begin"
			if event == "complete-helper" {
				name = "begin-helper"
			}
			operation := operations[name]
			if operation == nil {
				return nil, errors.New("no admitted operation to complete")
			}
			operation.Complete()
		case "cancel-client":
			ctx, cancel := context.WithCancel(context.Background())
			cancel()
			if err := coordinator.Quiesce(ctx); !errors.Is(err, context.Canceled) {
				return nil, fmt.Errorf("quiesce did not report cancelled wait: %v", err)
			}
		default:
			return nil, fmt.Errorf("unknown event: %s", event)
		}
	}
	state := coordinator.Snapshot()
	operation, accepted := coordinator.Begin()
	if accepted {
		operation.Complete()
	}
	return map[string]any{
		"state": state.Phase, "inFlight": state.InFlight, "newWorkAccepted": accepted,
	}, nil
}

func control(data json.RawMessage) (any, error) {
	var input struct {
		PeerUID               uint32 `json:"peerUid"`
		BootMatches           *bool  `json:"bootMatches"`
		LoaderIdentityMatches *bool  `json:"loaderIdentityMatches"`
		Schema                *int   `json:"schema"`
	}
	if err := json.Unmarshal(data, &input); err != nil {
		return nil, err
	}
	identity := lifecycle.ControlIdentity{BootID: "boot", PID: 123, StartTicks: "456"}
	request := lifecycle.ControlRequest{Schema: 1, RequestID: "contract-request", Identity: identity, Command: "quiesce", TimeoutMillis: 10}
	if input.BootMatches != nil && !*input.BootMatches {
		request.Identity.BootID = "old-boot"
	}
	if input.LoaderIdentityMatches != nil && !*input.LoaderIdentityMatches {
		request.Identity.StartTicks = "old-loader"
	}
	if input.Schema != nil {
		request.Schema = *input.Schema
	}
	coordinator := new(lifecycle.Coordinator)
	before := coordinator.Snapshot()
	response := lifecycle.NewControl(identity, coordinator).Execute(context.Background(), input.PeerUID, request)
	return map[string]any{
		"accepted": response.Error == "", "admissionChanged": before != coordinator.Snapshot(),
	}, nil
}

func main() {
	var request struct {
		Schema int        `json:"schema"`
		Cases  []testCase `json:"cases"`
	}
	if err := json.NewDecoder(os.Stdin).Decode(&request); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	results := make([]result, 0, len(request.Cases))
	for _, test := range request.Cases {
		observed, err := execute(test)
		value := result{ID: test.ID, Observed: observed}
		if err != nil {
			value.Error = err.Error()
		}
		results = append(results, value)
	}
	if err := json.NewEncoder(os.Stdout).Encode(map[string]any{
		"schema": 1, "results": results,
	}); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
