//go:build !linux

package main

import "errors"

func watchParent() error { return errors.New("native injection helper requires Android Linux") }
