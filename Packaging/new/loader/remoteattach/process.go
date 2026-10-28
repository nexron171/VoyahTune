package remoteattach

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"voyahtune/unified-loader/agentwire"
)

func ReadTarget(procRoot string, pid int) (agentwire.Target, error) {
	var target agentwire.Target
	if pid <= 1 {
		return target, errors.New("invalid target pid")
	}
	directory := filepath.Join(procRoot, strconv.Itoa(pid))
	boot, err := os.ReadFile(filepath.Join(procRoot, "sys/kernel/random/boot_id"))
	if err != nil {
		return target, err
	}
	stat, err := os.ReadFile(filepath.Join(directory, "stat"))
	if err != nil {
		return target, err
	}
	end := strings.LastIndexByte(string(stat), ')')
	if end < 0 {
		return target, errors.New("invalid process stat")
	}
	fields := strings.Fields(string(stat[end+1:]))
	if len(fields) <= 19 {
		return target, errors.New("short process stat")
	}
	if _, err := strconv.ParseUint(fields[19], 10, 64); err != nil {
		return target, err
	}
	status, err := os.ReadFile(filepath.Join(directory, "status"))
	if err != nil {
		return target, err
	}
	uidFound := false
	for _, line := range strings.Split(string(status), "\n") {
		if strings.HasPrefix(line, "Uid:") {
			ids := strings.Fields(line)
			if len(ids) != 5 {
				return target, errors.New("invalid process uid record")
			}
			uid, err := strconv.ParseUint(ids[2], 10, 32)
			if err != nil {
				return target, err
			}
			target.UID = uint32(uid)
			uidFound = true
		}
	}
	if !uidFound {
		return target, errors.New("target uid is unavailable")
	}
	target.PID = pid
	target.BootID = strings.TrimSpace(string(boot))
	target.StartTicks = fields[19]
	if target.BootID == "" {
		return target, errors.New("empty boot identity")
	}
	return target, nil
}

func VerifyTarget(procRoot string, expected agentwire.Target) error {
	actual, err := ReadTarget(procRoot, expected.PID)
	if err != nil {
		return err
	}
	if actual.BootID != expected.BootID || actual.PID != expected.PID || actual.StartTicks != expected.StartTicks {
		return fmt.Errorf("target process identity changed: %w", os.ErrNotExist)
	}
	if actual.UID != expected.UID {
		return errors.New("target process credentials changed")
	}
	return nil
}
