package main

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"

	"voyahtune/unified-loader/moduleplan"
)

func androidCommand(ctx context.Context, executable string, arguments ...string) string {
	commandContext, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	command := exec.CommandContext(commandContext, filepath.Join("/system/bin", executable), arguments...)
	command.WaitDelay = time.Second
	output, err := command.Output()
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(output))
}

func apolloEnabled(data, boot string) bool {
	if len(data) == 0 || len(data) > 192 || boot == "" {
		return false
	}
	values := map[string]string{}
	for _, line := range strings.Split(data, "\n") {
		if line == "" {
			continue
		}
		key, value, found := strings.Cut(line, "=")
		_, duplicate := values[key]
		if !found || strings.Contains(value, "=") || duplicate || (key != "v" && key != "boot" && key != "enabled") {
			return false
		}
		values[key] = value
	}
	return len(values) == 3 && values["v"] == "1" && values["boot"] == boot && values["enabled"] == "1"
}

type keyboardSelection struct {
	Boot     string          `json:"boot"`
	Identity processIdentity `json:"identity"`
	Mode     string          `json:"mode"`
}

func keyboardMode(path, boot string, identity processIdentity, readSetting func() string) (string, error) {
	var selection keyboardSelection
	err := readJSON(path, &selection)
	if err != nil && !os.IsNotExist(err) {
		return "", err
	}
	previous := moduleplan.KeyboardSelection{
		Identity: moduleplan.ProcessIdentity{BootID: selection.Boot, PID: selection.Identity.PID, StartTicks: selection.Identity.StartTicks},
		Mode:     selection.Mode,
	}
	current := moduleplan.ProcessIdentity{BootID: boot, PID: identity.PID, StartTicks: identity.StartTicks}
	mode := moduleplan.KeyboardMode(&previous, current, readSetting)
	if previous.Identity == current {
		return mode, nil
	}
	selection = keyboardSelection{boot, identity, mode}
	if err := writeAtomic(path, selection); err != nil {
		return "", err
	}
	return mode, nil
}
