//go:build linux

package main

import (
	"os"
	"runtime"
	"syscall"
)

func watchParent() error {
	runtime.LockOSThread()
	parent := os.Getppid()
	_, _, failure := syscall.RawSyscall6(syscall.SYS_PRCTL, 1, uintptr(syscall.SIGTERM), 0, 0, 0, 0)
	if failure != 0 {
		return failure
	}
	if parent == 1 || os.Getppid() != parent {
		return syscall.Kill(os.Getpid(), syscall.SIGTERM)
	}
	return nil
}
