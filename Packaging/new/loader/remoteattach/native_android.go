//go:build android && (arm || arm64) && cgo

package remoteattach

/*
#cgo CFLAGS: -std=c17 -I../../native
#include <stdlib.h>
#include "remote_injector.h"
*/
import "C"

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"os"
	"runtime"
	"sync"
	"syscall"
	"unsafe"
)

var nativeOperation sync.Mutex

func copyRecord(destination unsafe.Pointer, capacity int, value string) {
	buffer := unsafe.Slice((*byte)(destination), capacity)
	copy(buffer, value)
}

func injectNative(ctx context.Context, request Request, functions Functions, progress func(Stage)) (NativeResult, error) {
	nativeOperation.Lock()
	defer nativeOperation.Unlock()
	if err := ctx.Err(); err != nil {
		return NativeResult{Restored: true, Detached: true}, err
	}
	reader, writer, err := os.Pipe()
	if err != nil {
		return NativeResult{}, err
	}
	defer reader.Close()
	defer writer.Close()
	eventsDone := make(chan struct{})
	go func() {
		defer close(eventsDone)
		var record [4]byte
		for {
			if _, err := io.ReadFull(reader, record[:]); err != nil {
				return
			}
			progress(Stage(binary.LittleEndian.Uint32(record[:])))
		}
	}()
	library := C.CString(request.LibraryPath)
	defer C.free(unsafe.Pointer(library))
	var native C.VoyahtuneRemoteRequest
	native.bootstrap.schema = C.VOYAHTUNE_BOOTSTRAP_SCHEMA
	native.bootstrap.size = C.uint32_t(C.sizeof_VoyahtuneBootstrapArguments)
	native.bootstrap.pid = C.int32_t(request.Target.PID)
	native.bootstrap.tracee_tid = native.bootstrap.pid
	native.bootstrap.uid = C.uint32_t(request.Target.UID)
	native.bootstrap.startup_timeout_millis = C.uint32_t(request.StartupTimeout.Milliseconds())
	copyRecord(unsafe.Pointer(&native.bootstrap.boot_id[0]), len(native.bootstrap.boot_id), request.Target.BootID)
	copyRecord(unsafe.Pointer(&native.bootstrap.start_ticks[0]), len(native.bootstrap.start_ticks), request.Target.StartTicks)
	copyRecord(unsafe.Pointer(&native.bootstrap.native_id[0]), len(native.bootstrap.native_id), request.Target.NativeID)
	copyRecord(unsafe.Pointer(&native.bootstrap.runtime_id[0]), len(native.bootstrap.runtime_id), request.Target.RuntimeID)
	copyRecord(unsafe.Pointer(&native.bootstrap.dex_sha256[0]), len(native.bootstrap.dex_sha256), request.DEXSHA256)
	copyRecord(unsafe.Pointer(&native.bootstrap.dex_path[0]), len(native.bootstrap.dex_path), request.DEXPath)
	native.functions.dlopen = C.uint64_t(functions.Dlopen)
	native.functions.dlsym = C.uint64_t(functions.Dlsym)
	native.functions.dlclose = C.uint64_t(functions.Dlclose)
	native.functions.dlerror = C.uint64_t(functions.Dlerror)
	native.functions.mmap = C.uint64_t(functions.Mmap)
	native.functions.munmap = C.uint64_t(functions.Munmap)
	native.functions.breakpoint = C.uint64_t(functions.Breakpoint)
	native.functions.caller = C.uint64_t(functions.Caller)
	native.library_path = library
	native.acquire_timeout_millis = C.uint32_t(request.AcquireTimeout.Milliseconds())
	native.progress_descriptor = C.int(writer.Fd())
	C.VoyahtuneResetInjectionCancellation()
	cancellationDone := make(chan struct{})
	cancelWorkerDone := make(chan struct{})
	go func() {
		defer close(cancelWorkerDone)
		select {
		case <-ctx.Done():
			C.VoyahtuneCancelInjection()
		case <-cancellationDone:
		}
	}()
	var receipt C.VoyahtuneRemoteResult
	var diagnostic [1024]C.char
	runtime.LockOSThread()
	code := C.VoyahtuneRemoteInject(&native, &receipt, &diagnostic[0], C.size_t(len(diagnostic)))
	runtime.UnlockOSThread()
	close(cancellationDone)
	<-cancelWorkerDone
	writer.Close()
	<-eventsDone
	result := NativeResult{Attached: receipt.attached != 0, RegistersSaved: receipt.registers_saved != 0,
		Restored: receipt.restored != 0, Detached: receipt.detached != 0, LibraryLoaded: receipt.library_loaded != 0,
		WorkerStarted: receipt.worker_started != 0, Uncertain: receipt.uncertain != 0,
		TargetExited: receipt.target_exited != 0, SafePC: uint64(receipt.safe_pc), BootstrapResult: int32(receipt.bootstrap_result)}
	if code != 0 {
		return result, fmt.Errorf("native attach: %s: %w", C.GoString(&diagnostic[0]), syscall.Errno(code))
	}
	if !result.Restored || !result.Detached || !result.WorkerStarted || result.Uncertain {
		return result, errors.New("native attach did not confirm restored/detached worker startup")
	}
	return result, nil
}
