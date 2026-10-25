//go:build linux || android

package main

import "syscall"

func configureInjectorParentDeath(attributes *syscall.SysProcAttr) {
	// A supervisor crash must not leave a pending RPC and its target lock alive.
	attributes.Pdeathsig = syscall.SIGKILL
}
