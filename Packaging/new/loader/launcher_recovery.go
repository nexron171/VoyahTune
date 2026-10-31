package main

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"syscall"
	"time"

	"voyahtune/unified-loader/agentwire"
	"voyahtune/unified-loader/lifecycle"
	"voyahtune/unified-loader/remoteattach"
)

const launcherTarget = "com.qinggan.app.launcher"

type launcherRecoveryRecord struct {
	Boot        string          `json:"boot"`
	Code        string          `json:"code"`
	Attempts    int             `json:"attempts"`
	NextAttempt int64           `json:"nextAttempt"`
	LastTarget  processIdentity `json:"lastTarget"`
	Signalled   bool            `json:"signalled"`
	Reason      string          `json:"reason"`
}

type targetCleanup interface {
	PrepareRestart(context.Context, agentwire.Target) error
}

func (manager *nativeSupervisor) recoverLauncher(ctx context.Context, name string, target agentwire.Target, ack agentwire.Status) (string, string, bool) {
	if name != launcherTarget || ack.Target != target || ack.Schema != 1 ||
		(ack.State != "restart-required" && ack.State != "failed" && ack.State != "quiesced") {
		return "", "", false
	}
	reason := ack.Error
	if reason == "" {
		reason = "launcher resident " + ack.State
	}
	state, err := manager.restartLauncher(ctx, target, reason)
	if err != nil {
		reason += "; recovery: " + err.Error()
	}
	return state, reason, true
}

func (manager *nativeSupervisor) restartLauncher(ctx context.Context, target agentwire.Target, reason string) (string, error) {
	if manager.engine.Snapshot().Phase != lifecycle.Running || manager.admissionBlock() != nil {
		return "restart-required", errors.New("runtime admission is closed")
	}
	cleanup, ok := manager.engine.(targetCleanup)
	if !ok {
		return "restart-required", errors.New("target cleanup unavailable")
	}
	now, err := uptimeSeconds(manager.procRoot)
	if err != nil {
		return "restart-required", err
	}
	path := filepath.Join(manager.stateDirectory, "launcher-recovery.json")
	var record launcherRecoveryRecord
	if err := readJSON(path, &record); err != nil && !os.IsNotExist(err) {
		return "restart-required", err
	}
	code := target.NativeID + ":" + target.RuntimeID
	if record.Boot != target.BootID || record.Code != code {
		record = launcherRecoveryRecord{Boot: target.BootID, Code: code}
	}
	identity := processIdentity{PID: target.PID, StartTicks: target.StartTicks}
	if record.Signalled && record.LastTarget == identity {
		return "restart-required", errors.New("waiting for launcher process to exit")
	}
	if record.Attempts >= 2 {
		return "restart-required", errors.New("automatic launcher recovery budget exhausted for this boot")
	}
	if now < record.NextAttempt {
		return "restart-required", errors.New("launcher recovery backoff pending")
	}
	record.Attempts++
	record.NextAttempt = now + 60
	record.LastTarget = identity
	record.Signalled = false
	record.Reason = reason
	if err := writeAtomic(path, record); err != nil {
		return "restart-required", err
	}
	operation, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	if err := cleanup.PrepareRestart(operation, target); err != nil {
		return "restart-required", fmt.Errorf("cleanup not confirmed: %w", err)
	}
	if err := operation.Err(); err != nil {
		return "restart-required", err
	}
	if manager.engine.Snapshot().Phase != lifecycle.Running || manager.admissionBlock() != nil {
		return "restart-required", errors.New("runtime admission closed during cleanup")
	}
	// Persist before signalling so a supervisor restart cannot repeat the same restart.
	record.Signalled = true
	if err := writeAtomic(path, record); err != nil {
		return "restart-required", err
	}
	restart := manager.restartLauncherProcess
	if restart == nil {
		restart = manager.signalLauncherRestart
	}
	if err := restart(target); err != nil {
		record.Signalled = false
		if persistError := writeAtomic(path, record); persistError != nil {
			return "restart-required", fmt.Errorf("signal failed: %v; persist failure: %w", err, persistError)
		}
		return "restart-required", err
	}
	return "restarting", nil
}

func (manager *nativeSupervisor) signalLauncherRestart(target agentwire.Target) error {
	process, err := os.FindProcess(target.PID)
	if err != nil {
		return err
	}
	defer process.Release()
	if err := remoteattach.VerifyTarget(manager.procRoot, target); err != nil {
		return err
	}
	name, err := os.ReadFile(filepath.Join(manager.procRoot, fmt.Sprint(target.PID), "cmdline"))
	if err != nil {
		return err
	}
	if string(name) != launcherTarget+"\x00" {
		return errors.New("restart target is not the OEM launcher")
	}
	// Android owns the persistent launcher's restart; do not force-stop the package.
	return process.Signal(syscall.SIGTERM)
}
