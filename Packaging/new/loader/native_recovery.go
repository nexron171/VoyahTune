package main

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"syscall"

	"voyahtune/unified-loader/agentwire"
	"voyahtune/unified-loader/lifecycle"
	"voyahtune/unified-loader/remoteattach"
	"voyahtune/unified-loader/resident"
)

func lockNativeState(directory string) (*os.File, error) {
	if err := os.MkdirAll(directory, 0700); err != nil {
		return nil, err
	}
	info, err := os.Lstat(directory)
	if err != nil {
		return nil, err
	}
	stat, ok := info.Sys().(*syscall.Stat_t)
	if !ok || stat.Uid != uint32(os.Geteuid()) || !info.IsDir() || info.Mode().Perm()&0077 != 0 {
		return nil, errors.New("private owned supervisor state required")
	}
	return lockFile(filepath.Join(directory, "loader.lock"))
}

func openNativeResidents(runtimeDirectory, stateDirectory, procRoot, dexDigest string, options ...resident.Option) (*resident.Manager, error) {
	store, err := resident.NewFileStore(filepath.Join(stateDirectory, "residents"))
	if err != nil {
		return nil, err
	}
	backend, err := resident.NewNativeBackend(resident.NativeOptions{
		StateDirectory: filepath.Join(stateDirectory, "helpers"), ProcRoot: procRoot,
		HelperPath: filepath.Join(runtimeDirectory, nativeHelperName), LibraryPath: filepath.Join(runtimeDirectory, nativeLibraryName),
		ARM32HelperPath: filepath.Join(runtimeDirectory, "voyahtune-inject-arm"), ARM32LibraryPath: filepath.Join(runtimeDirectory, "libvoyahtune-agent-arm.so"),
		DEXPath: filepath.Join(runtimeDirectory, agentDEXName), DEXSHA256: dexDigest,
	})
	if err != nil {
		return nil, err
	}
	return resident.New(store, backend, func(target agentwire.Target) (bool, error) {
		err := remoteattach.VerifyTarget(procRoot, target)
		if os.IsNotExist(err) {
			return false, nil
		}
		return err == nil, err
	}, options...)
}

func quiesceNativeRuntime(ctx context.Context, runtimeDirectory, stateDirectory, procRoot string) (lifecycle.ControlResponse, error) {
	response, onlineError := nativeControl(ctx, stateDirectory, procRoot, "quiesce")
	if onlineError == nil {
		return response, nil
	}
	if ctx.Err() != nil {
		return response, ctx.Err()
	}
	response, offlineError := quiesceOffline(ctx, runtimeDirectory, stateDirectory, procRoot)
	if offlineError != nil {
		return response, fmt.Errorf("runtime quiesce unavailable: %w", errors.Join(onlineError, offlineError))
	}
	return response, nil
}

func quiesceOffline(ctx context.Context, runtimeDirectory, stateDirectory, procRoot string) (lifecycle.ControlResponse, error) {
	var response lifecycle.ControlResponse
	lock, err := lockNativeState(stateDirectory)
	if err != nil {
		return response, err
	}
	defer unlockFile(lock)
	self, err := remoteattach.ReadTarget(procRoot, os.Getpid())
	if err != nil {
		return response, err
	}
	manager, err := openNativeResidents(runtimeDirectory, stateDirectory, procRoot, "")
	if err != nil {
		return response, err
	}
	if err := manager.Quiesce(ctx); err != nil {
		return response, err
	}
	var token [16]byte
	if _, err := rand.Read(token[:]); err != nil {
		return response, err
	}
	response = lifecycle.ControlResponse{
		Schema: lifecycle.ControlSchema, RequestID: hex.EncodeToString(token[:]),
		Identity: lifecycle.ControlIdentity{BootID: self.BootID, PID: self.PID, StartTicks: self.StartTicks}, State: manager.Snapshot(),
	}
	if response.State.Phase != lifecycle.Quiesced || response.State.InFlight != 0 {
		return response, errors.New("offline runtime cleanup is not confirmed")
	}
	return response, nil
}
