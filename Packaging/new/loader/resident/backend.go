package resident

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"syscall"

	"voyahtune/unified-loader/agentwire"
	"voyahtune/unified-loader/injecthelper"
	"voyahtune/unified-loader/remoteattach"
)

type NativeOptions struct {
	StateDirectory   string
	ProcRoot         string
	HelperPath       string
	ARM32HelperPath  string
	ARM32LibraryPath string
	LibraryPath      string
	DEXPath          string
	DEXSHA256        string
}

type agentConnection interface {
	Status(context.Context) (agentwire.Status, error)
	Apply(context.Context, agentwire.Plan) (agentwire.Status, error)
	Quiesce(context.Context) (agentwire.Status, error)
}

type NativeBackend struct {
	options NativeOptions
	root    *FileStore
	client  func(agentwire.Target) (agentConnection, error)
}

func NewNativeBackend(options NativeOptions) (*NativeBackend, error) {
	for _, path := range []string{options.StateDirectory, options.ProcRoot, options.HelperPath, options.LibraryPath, options.DEXPath} {
		if !filepath.IsAbs(path) || filepath.Clean(path) != path {
			return nil, errors.New("normalized absolute native backend paths required")
		}
	}
	for _, path := range []string{options.ARM32HelperPath, options.ARM32LibraryPath} {
		if path != "" && (!filepath.IsAbs(path) || filepath.Clean(path) != path) {
			return nil, errors.New("normalized absolute ARM32 component paths required")
		}
	}
	root, err := NewFileStore(options.StateDirectory)
	if err != nil {
		return nil, err
	}
	return &NativeBackend{options: options, root: root, client: func(target agentwire.Target) (agentConnection, error) { return agentwire.NewClient(target) }}, nil
}

func (backend *NativeBackend) processDirectory(target agentwire.Target) (string, error) {
	if err := backend.root.verifyDirectory(); err != nil {
		return "", err
	}
	name := filepath.Base(backend.root.path(target))
	path := filepath.Join(backend.options.StateDirectory, name[:len(name)-len(".json")])
	if err := os.Mkdir(path, 0700); err != nil && !os.IsExist(err) {
		return "", err
	}
	info, err := os.Lstat(path)
	if err != nil {
		return "", err
	}
	if !info.IsDir() || !owned(info) {
		return "", errors.New("unsafe helper process directory")
	}
	return path, nil
}

func (backend *NativeBackend) Recover(ctx context.Context, record Record) (Recovery, error) {
	directory, err := backend.processDirectory(record.Target)
	if err != nil {
		return Recovery{}, err
	}
	lock, err := os.OpenFile(filepath.Join(directory, fmt.Sprintf("target-%d-%s.lock", record.Target.PID, record.Target.StartTicks)), os.O_CREATE|os.O_RDWR|syscall.O_NOFOLLOW, 0600)
	if err != nil {
		return Recovery{}, err
	}
	defer lock.Close()
	if err := syscall.Flock(int(lock.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
		if errors.Is(err, syscall.EWOULDBLOCK) {
			return Recovery{}, ErrUncertain
		}
		return Recovery{}, err
	}
	defer syscall.Flock(int(lock.Fd()), syscall.LOCK_UN)
	var request injecthelper.Request
	requestError := readPrivateJSON(filepath.Join(directory, "request.json"), &request)
	var receipt injecthelper.Receipt
	if requestError == nil {
		if request.Schema != 1 || request.RequestID == "" || !sameProcess(request.Target, record.Target) {
			return Recovery{}, errors.New("helper request process identity mismatch")
		}
		if err := readPrivateJSON(filepath.Join(directory, "receipt.json"), &receipt); err != nil {
			return Recovery{}, fmt.Errorf("%w: helper receipt unavailable: %v", ErrUncertain, err)
		}
		if err := correlatedReceipt(request, receipt); err != nil {
			return Recovery{}, err
		}
		live, err := backend.helperAlive(receipt.Helper)
		if err != nil {
			return Recovery{}, err
		}
		if live || !nativeSettled(receipt) {
			return Recovery{}, ErrUncertain
		}
	} else if !os.IsNotExist(requestError) {
		return Recovery{}, requestError
	}
	client, err := backend.client(record.Target)
	if err != nil {
		return Recovery{}, err
	}
	status, statusError := client.Status(ctx)
	if statusError == nil || errors.Is(statusError, agentwire.ErrRuntimeChanged) {
		return Recovery{Status: &status}, nil
	}
	if !errors.Is(statusError, syscall.ECONNREFUSED) && !errors.Is(statusError, syscall.ENOENT) {
		return Recovery{}, statusError
	}
	if os.IsNotExist(requestError) && record.Phase == "new" {
		return Recovery{SafeToAttach: true}, nil
	}
	if requestError == nil && receipt.Completed && request.Target == record.Target && !receipt.Native.LibraryLoaded && !receipt.Native.WorkerStarted && !receipt.Native.Uncertain {
		return Recovery{SafeToAttach: true}, nil
	}
	return Recovery{}, ErrUncertain
}

func correlatedReceipt(request injecthelper.Request, receipt injecthelper.Receipt) error {
	if receipt.Schema != 1 || receipt.RequestID != request.RequestID || receipt.Target != request.Target || receipt.Helper.BootID != request.Target.BootID || receipt.Helper.PID <= 1 || receipt.Helper.UID != 0 {
		return errors.New("helper receipt identity mismatch")
	}
	if _, err := strconv.ParseUint(receipt.Helper.StartTicks, 10, 64); err != nil {
		return errors.New("helper start ticks unavailable")
	}
	return nil
}

func nativeSettled(receipt injecthelper.Receipt) bool {
	native := receipt.Native
	if native.Uncertain {
		return false
	}
	if native.Restored && native.Detached {
		return true
	}
	return receipt.Completed && !native.Attached && !native.RegistersSaved && !native.LibraryLoaded && !native.WorkerStarted
}

func (backend *NativeBackend) helperAlive(expected agentwire.Target) (bool, error) {
	actual, err := remoteattach.ReadTarget(backend.options.ProcRoot, expected.PID)
	if os.IsNotExist(err) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	return sameProcess(actual, expected), nil
}

func (backend *NativeBackend) components(target agentwire.Target) (string, string, error) {
	architecture, err := remoteattach.DetectArchitecture(backend.options.ProcRoot, target.PID)
	if err != nil {
		return "", "", err
	}
	if architecture == remoteattach.ARM32 {
		if backend.options.ARM32HelperPath == "" || backend.options.ARM32LibraryPath == "" {
			return "", "", errors.New("ARM32 native components are unavailable")
		}
		return backend.options.ARM32HelperPath, backend.options.ARM32LibraryPath, nil
	}
	return backend.options.HelperPath, backend.options.LibraryPath, nil
}

func (backend *NativeBackend) Attach(ctx context.Context, record Record) (agentwire.Status, error) {
	helperPath, libraryPath, err := backend.components(record.Target)
	if err != nil {
		return agentwire.Status{}, err
	}
	directory, err := backend.processDirectory(record.Target)
	if err != nil {
		return agentwire.Status{}, err
	}
	// Ensure owns the session mutex and has proved that the previous helper has settled.
	request := injecthelper.Request{Schema: 1, RequestID: fmt.Sprintf("attach-%d-%s-%d", record.Target.PID, record.Target.StartTicks, record.Plan.Revision),
		Target: record.Target, LibraryPath: libraryPath, DEXPath: backend.options.DEXPath, DEXSHA256: backend.options.DEXSHA256,
		AcquireTimeoutMillis: 3000, StartupTimeoutMillis: 20000, Plan: record.Plan}
	requestPath := filepath.Join(directory, "request.json")
	receiptPath := filepath.Join(directory, "receipt.json")
	if err := writePrivateJSON(requestPath, request); err != nil {
		return agentwire.Status{}, err
	}
	if err := os.Remove(receiptPath); err != nil && !os.IsNotExist(err) {
		return agentwire.Status{}, err
	}
	log, err := os.OpenFile(filepath.Join(directory, "helper.log"), os.O_CREATE|os.O_WRONLY|os.O_TRUNC|syscall.O_NOFOLLOW, 0600)
	if err != nil {
		return agentwire.Status{}, err
	}
	defer log.Close()
	command := exec.Command(helperPath, "--request", requestPath, "--receipt", receiptPath)
	command.Stdout = log
	command.Stderr = log
	command.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	if err := command.Start(); err != nil {
		return agentwire.Status{}, err
	}
	stopCancellation := context.AfterFunc(ctx, func() { command.Process.Signal(syscall.SIGTERM) })
	waitError := command.Wait()
	stopCancellation()
	var receipt injecthelper.Receipt
	if err := readPrivateJSON(receiptPath, &receipt); err != nil {
		return agentwire.Status{}, err
	}
	if err := correlatedReceipt(request, receipt); err != nil {
		return agentwire.Status{}, err
	}
	attempt := injecthelper.NewAttempt(request)
	exitCode := 0
	if waitError != nil {
		exitCode = command.ProcessState.ExitCode()
	}
	attempt.RecordHelperExit(receipt, command.Process.Pid, exitCode)
	actual, identityError := remoteattach.ReadTarget(backend.options.ProcRoot, record.Target.PID)
	if identityError != nil {
		return agentwire.Status{}, identityError
	}
	attempt.ConfirmTargetIdentity(actual)
	snapshot := attempt.Snapshot()
	if snapshot.State != "active" || ctx.Err() != nil {
		if ctx.Err() != nil {
			return agentwire.Status{}, ctx.Err()
		}
		return agentwire.Status{}, fmt.Errorf("native helper did not confirm active: %s", snapshot.Error)
	}
	return *receipt.Status, nil
}

func (backend *NativeBackend) Apply(ctx context.Context, record Record) (agentwire.Status, error) {
	client, err := backend.client(record.Target)
	if err != nil {
		return agentwire.Status{}, err
	}
	return client.Apply(ctx, record.Plan)
}
func (backend *NativeBackend) Quiesce(ctx context.Context, target agentwire.Target) (agentwire.Status, error) {
	client, err := backend.client(target)
	if err != nil {
		return agentwire.Status{}, err
	}
	return client.Quiesce(ctx)
}

func readPrivateJSON(path string, value any) error {
	file, err := os.OpenFile(path, os.O_RDONLY|syscall.O_NOFOLLOW, 0)
	if err != nil {
		return err
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil {
		return err
	}
	stat, ok := info.Sys().(*syscall.Stat_t)
	if !ok || !info.Mode().IsRegular() || !owned(info) || stat.Nlink != 1 || info.Size() < 1 || info.Size() > maximumRecord {
		return errors.New("helper record must be private, bounded and singly linked")
	}
	data, err := io.ReadAll(io.LimitReader(file, maximumRecord+1))
	if err != nil {
		return err
	}
	if len(data) > maximumRecord {
		return errors.New("helper record exceeds limit")
	}
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(value); err != nil {
		return err
	}
	if err := decoder.Decode(new(any)); err != io.EOF {
		return errors.New("unexpected helper record data")
	}
	return nil
}

func writePrivateJSON(path string, value any) error {
	data, err := json.Marshal(value)
	if err != nil {
		return err
	}
	if len(data) > maximumRecord {
		return errors.New("helper record exceeds limit")
	}
	file, err := os.CreateTemp(filepath.Dir(path), ".request-")
	if err != nil {
		return err
	}
	defer os.Remove(file.Name())
	if _, err = file.Write(append(data, '\n')); err == nil {
		err = file.Sync()
	}
	closeError := file.Close()
	if err != nil {
		return err
	}
	if closeError != nil {
		return closeError
	}
	if err := os.Rename(file.Name(), path); err != nil {
		return err
	}
	directory, err := os.Open(filepath.Dir(path))
	if err != nil {
		return err
	}
	defer directory.Close()
	return directory.Sync()
}
