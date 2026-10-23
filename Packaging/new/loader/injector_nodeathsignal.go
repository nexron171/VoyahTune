//go:build !linux && !android

package main

import "syscall"

func configureInjectorParentDeath(attributes *syscall.SysProcAttr) {}
