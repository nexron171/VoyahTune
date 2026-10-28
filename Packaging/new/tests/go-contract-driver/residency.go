package main

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"voyahtune/unified-loader/agentwire"
	"voyahtune/unified-loader/lifecycle"
	"voyahtune/unified-loader/resident"
)

type processBoundary struct {
	mutex    sync.Mutex
	statuses map[agentwire.Target]agentwire.Status
	attaches int
}

func (boundary *processBoundary) Recover(_ context.Context, record resident.Record) (resident.Recovery, error) {
	boundary.mutex.Lock()
	defer boundary.mutex.Unlock()
	for target, status := range boundary.statuses {
		if target.BootID == record.Target.BootID && target.PID == record.Target.PID && target.StartTicks == record.Target.StartTicks && target.UID == record.Target.UID {
			copy := status
			return resident.Recovery{Status: &copy}, nil
		}
	}
	return resident.Recovery{SafeToAttach: true}, nil
}
func (boundary *processBoundary) Attach(_ context.Context, record resident.Record) (agentwire.Status, error) {
	boundary.mutex.Lock()
	defer boundary.mutex.Unlock()
	boundary.attaches++
	return boundary.install(record), nil
}
func (boundary *processBoundary) Apply(_ context.Context, record resident.Record) (agentwire.Status, error) {
	boundary.mutex.Lock()
	defer boundary.mutex.Unlock()
	return boundary.install(record), nil
}
func (boundary *processBoundary) install(record resident.Record) agentwire.Status {
	status := agentwire.Status{Schema: 1, Target: record.Target, Revision: record.Plan.Revision, State: "ready", Modules: map[string]string{}}
	for _, module := range record.Plan.Modules {
		status.Modules[module] = "active"
	}
	boundary.statuses[record.Target] = status
	return status
}
func (boundary *processBoundary) Quiesce(_ context.Context, target agentwire.Target) (agentwire.Status, error) {
	boundary.mutex.Lock()
	defer boundary.mutex.Unlock()
	status := boundary.statuses[target]
	status.State = "quiesced"
	status.Error = ""
	status.Modules = map[string]string{}
	boundary.statuses[target] = status
	return status, nil
}

func identity(data json.RawMessage) (any, error) {
	var input struct{ OldBoot, SameBoot, SamePid, SameTicks, SameRuntime, InstallBlock, LoaderRestarted bool }
	if err := json.Unmarshal(data, &input); err != nil {
		return nil, err
	}
	directory, err := os.MkdirTemp("", "resident-contract-")
	if err != nil {
		return nil, err
	}
	defer os.RemoveAll(directory)
	store, err := resident.NewFileStore(filepath.Join(directory, "journal"))
	if err != nil {
		return nil, err
	}
	boundary := &processBoundary{statuses: map[agentwire.Target]agentwire.Status{}}
	first := agentwire.Target{BootID: "boot-1", PID: 41, UID: 1000, StartTicks: "100", NativeID: "native", RuntimeID: "java"}
	current := first
	alive := func(target agentwire.Target) (bool, error) {
		return target.BootID == current.BootID && target.PID == current.PID && target.StartTicks == current.StartTicks && target.UID == current.UID, nil
	}
	manager, err := resident.New(store, boundary, alive)
	if err != nil {
		return nil, err
	}
	plan := agentwire.Plan{Schema: 1, RuntimeID: "java", Modules: []string{"phone"}}
	if _, err := manager.Ensure(context.Background(), first, plan); err != nil {
		return nil, err
	}
	if input.LoaderRestarted {
		manager, err = resident.New(store, boundary, alive)
		if err != nil {
			return nil, err
		}
	}
	if input.InstallBlock {
		block := filepath.Join(directory, "install.block")
		if err := os.WriteFile(block, []byte("install"), 0600); err != nil {
			return nil, err
		}
		if err := resident.CheckAdmission(block, filepath.Join(directory, "migration.boot"), current.BootID); !errors.Is(err, resident.ErrInstallBlocked) {
			return nil, err
		}
		if err := manager.Quiesce(context.Background()); err != nil {
			return nil, err
		}
		_, attempt := manager.Ensure(context.Background(), current, plan)
		return map[string]any{"state": manager.Snapshot().Phase, "attachAllowed": attempt == nil}, nil
	}
	if input.OldBoot {
		current.BootID = "boot-2"
	}
	if input.SameBoot && !input.SamePid {
		current.PID++
	}
	if input.SameBoot && !input.SameTicks {
		current.StartTicks = "101"
	}
	if input.SameBoot && input.SameTicks && !input.SameRuntime {
		current.RuntimeID = "java-next"
		plan.RuntimeID = current.RuntimeID
	}
	before := boundary.attaches
	_, ensureError := manager.Ensure(context.Background(), current, plan)
	attached := boundary.attaches > before
	result := map[string]any{"reuseReady": ensureError == nil && !attached, "attachAllowed": ensureError == nil && attached}
	if errors.Is(ensureError, agentwire.ErrRuntimeChanged) {
		result = map[string]any{"state": "restart-required", "attachAllowed": false}
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if err := manager.Quiesce(ctx); err != nil {
		return nil, err
	}
	return result, nil
}

func backendExclusion(data json.RawMessage) (any, error) {
	var input struct {
		ResidentBackend, RequestedBackend string
		AttachFailed                      bool
	}
	if err := json.Unmarshal(data, &input); err != nil {
		return nil, err
	}
	directory, err := os.MkdirTemp("", "backend-exclusion-")
	if err != nil {
		return nil, err
	}
	defer os.RemoveAll(directory)
	store, err := resident.NewFileStore(filepath.Join(directory, "journal"))
	if err != nil {
		return nil, err
	}
	marker := filepath.Join(directory, "migration.boot")
	if input.ResidentBackend == "frida" {
		if err := os.WriteFile(marker, []byte("boot-1"), 0600); err != nil {
			return nil, err
		}
	}
	if input.ResidentBackend != "frida" {
		return nativeFailureWithoutFallback(directory)
	}
	boundary := &processBoundary{statuses: map[agentwire.Target]agentwire.Status{}}
	manager, err := resident.New(store, boundary, func(agentwire.Target) (bool, error) { return true, nil },
		resident.WithAttachAdmission(func(target agentwire.Target) error {
			return resident.CheckAdmission(filepath.Join(directory, "block"), marker, target.BootID)
		}))
	if err != nil {
		return nil, err
	}
	target := agentwire.Target{BootID: "boot-1", PID: 41, UID: 1000, StartTicks: "100", NativeID: "native", RuntimeID: "java"}
	_, failure := manager.Ensure(context.Background(), target, agentwire.Plan{Schema: 1, RuntimeID: "java", Modules: []string{"phone"}})
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if err := manager.Quiesce(ctx); err != nil {
		return nil, err
	}
	if input.ResidentBackend == "frida" {
		state := "failed"
		if errors.Is(failure, resident.ErrRebootRequired) {
			state = "restart-required"
		}
		return map[string]any{"state": state, "attachCalls": boundary.attaches}, nil
	}
	if failure == nil || manager.Snapshot().Phase != lifecycle.Quiesced {
		return nil, errors.New("failed native attach was accepted")
	}
	return nil, errors.New("unsupported backend exclusion scenario")
}

func nativeFailureWithoutFallback(directory string) (any, error) {
	commands := filepath.Join(directory, "commands")
	commandDirectory := filepath.Join(directory, "bin")
	if err := os.Mkdir(commandDirectory, 0700); err != nil {
		return nil, err
	}
	oldPath := os.Getenv("PATH")
	previousLog, hadLog := os.LookupEnv("VOYAHTUNE_COMMAND_PROBE_LOG")
	if err := os.Setenv("PATH", commandDirectory+string(os.PathListSeparator)+oldPath); err != nil {
		return nil, err
	}
	if err := os.Setenv("VOYAHTUNE_COMMAND_PROBE_LOG", commands); err != nil {
		return nil, err
	}
	defer func() {
		os.Setenv("PATH", oldPath)
		if hadLog {
			os.Setenv("VOYAHTUNE_COMMAND_PROBE_LOG", previousLog)
		} else {
			os.Unsetenv("VOYAHTUNE_COMMAND_PROBE_LOG")
		}
	}()
	for _, name := range []string{"voyahtune-inject", "frida-inject", "frida-inject-arm", "frida-server"} {
		script := "#!/bin/sh\nprintf '%s\\n' \"${0##*/}\" >> \"$VOYAHTUNE_COMMAND_PROBE_LOG\"\nexit 7\n"
		if err := os.WriteFile(filepath.Join(commandDirectory, name), []byte(script), 0700); err != nil {
			return nil, err
		}
	}
	procRoot := filepath.Join(directory, "proc")
	processDirectory := filepath.Join(procRoot, "41")
	if err := os.MkdirAll(processDirectory, 0700); err != nil {
		return nil, err
	}
	header := make([]byte, 20)
	copy(header, []byte{0x7f, 'E', 'L', 'F', 2, 1})
	header[18] = 183
	if err := os.WriteFile(filepath.Join(processDirectory, "exe"), header, 0600); err != nil {
		return nil, err
	}
	backend, err := resident.NewNativeBackend(resident.NativeOptions{
		StateDirectory: filepath.Join(directory, "helpers"), ProcRoot: procRoot,
		HelperPath: filepath.Join(commandDirectory, "voyahtune-inject"), LibraryPath: filepath.Join(directory, "agent.so"), DEXPath: filepath.Join(directory, "agents.dex"),
	})
	if err != nil {
		return nil, err
	}
	target := agentwire.Target{BootID: "boot-1", PID: 41, UID: 1000, StartTicks: "100", NativeID: "native", RuntimeID: "java"}
	_, failure := backend.Attach(context.Background(), resident.Record{Schema: 1, Target: target,
		Plan: agentwire.Plan{Schema: 1, RuntimeID: "java", Revision: 1, Modules: []string{"phone"}}, Phase: "pending"})
	if failure == nil {
		return nil, errors.New("failed helper was accepted")
	}
	data, err := os.ReadFile(commands)
	if err != nil {
		return nil, err
	}
	nativeCommands, legacyCommands := 0, 0
	for _, name := range strings.Fields(string(data)) {
		if name == "voyahtune-inject" {
			nativeCommands++
		}
		if strings.HasPrefix(name, "frida-") {
			legacyCommands++
		}
	}
	if nativeCommands != 1 {
		return nil, errors.New("native helper was not executed once")
	}
	return map[string]any{"fridaCommands": legacyCommands, "state": "failed"}, nil
}
