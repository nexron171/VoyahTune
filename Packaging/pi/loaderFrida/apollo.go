package main

// Apollo deliberately follows the OD lifecycle. Its agent is eternalized (-e),
// because the OD Java sentinel is not cleared when an attached session closes.
// No other PI agent uses this path or changes its configured session lifetime.
import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"
)

const apolloTarget = "com.qinggan.app.vehiclesetting"
const apolloFlagPath = "/data/user_de/0/ru.big.town.anative/files/apollo_settings_runtime.v1"
const apolloReadyMarker = "[apollo] hook ready"

type apolloAttempt struct {
	BootID     string `json:"bootId"`
	PID        int    `json:"pid"`
	StartTicks string `json:"startTicks"`
	State      string `json:"state"`
}

func apolloEnabled(payload, boot string) bool {
	if len(payload) == 0 || len(payload) > 192 || boot == "" {
		return false
	}
	values := map[string]string{}
	for _, line := range strings.Split(payload, "\n") {
		if line == "" {
			continue
		}
		key, value, found := strings.Cut(line, "=")
		if !found || strings.Contains(value, "=") {
			return false
		}
		if key != "v" && key != "boot" && key != "enabled" {
			return false
		}
		if _, duplicate := values[key]; duplicate {
			return false
		}
		values[key] = value
	}
	return len(values) == 3 && values["v"] == "1" && values["enabled"] == "1" && values["boot"] == boot
}
func apolloOutputReady(data []byte) bool {
	lower := strings.ToLower(string(data))
	for _, failure := range []string{"unable to find", "not found", "failed to", "cannot parse", "no such process", "error resolving"} {
		if strings.Contains(lower, failure) {
			return false
		}
	}
	return strings.Contains(string(data), apolloReadyMarker)
}
func (w worker) runApollo(ctx context.Context) {
	flagPath := w.apolloFlag
	if flagPath == "" {
		flagPath = apolloFlagPath
	}
	for ctx.Err() == nil {
		bootData, bootErr := os.ReadFile(filepath.Join(w.procRoot, "sys/kernel/random/boot_id"))
		flag, flagErr := os.ReadFile(flagPath)
		boot := strings.TrimSpace(string(bootData))
		if bootErr != nil || flagErr != nil || !apolloEnabled(string(flag), boot) {
			w.status.set(apolloTarget, targetStatus{State: "disabled"})
		} else {
			identity, err := w.find(w.procRoot, apolloTarget)
			if err != nil {
				w.status.set(apolloTarget, targetStatus{State: "failed", Error: err.Error()})
			} else if identity.PID == 0 {
				w.status.set(apolloTarget, targetStatus{State: "waiting"})
			} else {
				w.apolloCycle(ctx, boot, identity)
			}
		}
		if !wait(ctx, w.poll) {
			return
		}
	}
}
func (w worker) apolloCycle(ctx context.Context, boot string, identity processIdentity) {
	state := targetStatus{PID: identity.PID, StartTicks: identity.StartTicks, State: "failed"}
	path := filepath.Join(w.stateDir, "apollo.attempt.json")
	previous, err := os.ReadFile(path)
	if err == nil {
		var record apolloAttempt
		if json.Unmarshal(previous, &record) == nil && record.BootID == boot && record.PID == identity.PID && record.StartTicks == identity.StartTicks {
			if record.State == "active" {
				state.State = "active"
			} else {
				state.Error = "Apollo attempt already consumed for this process identity"
			}
			w.status.set(apolloTarget, state)
			return
		}
	} else if !os.IsNotExist(err) {
		state.Error = err.Error()
		w.status.set(apolloTarget, state)
		return
	}
	record := apolloAttempt{BootID: boot, PID: identity.PID, StartTicks: identity.StartTicks, State: "attempted"}
	data, _ := json.Marshal(record)
	// Reserve before starting the injector, including across watchdog restarts.
	if err := writeAtomic(path, data); err != nil {
		state.Error = err.Error()
		w.status.set(apolloTarget, state)
		return
	}
	if err := w.injectApollo(ctx, identity); err != nil {
		state.Error = err.Error()
	} else {
		record.State = "active"
		data, _ = json.Marshal(record)
		if err := writeAtomic(path, data); err != nil {
			state.Error = err.Error()
		} else {
			state.State = "active"
		}
	}
	w.status.set(apolloTarget, state)
}
func (w worker) injectApollo(ctx context.Context, identity processIdentity) error {
	before, err := w.read(w.procRoot, identity.PID, apolloTarget)
	if err != nil || before != identity {
		return errors.New("Apollo target changed before injection")
	}
	attemptPath := filepath.Join(w.stateDir, "apollo.try.log")
	output, err := os.OpenFile(attemptPath, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0600)
	if err != nil {
		return err
	}
	childCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	cmd := exec.CommandContext(childCtx, filepath.Join(w.scriptsDir, "frida-inject"), "-p", strconv.Itoa(identity.PID), "-s", filepath.Join(w.scriptsDir, "apollo_tech.js"), "-e")
	cmd.Stdout, cmd.Stderr = output, output
	cmd.Cancel = func() error { return cmd.Process.Signal(syscall.SIGTERM) }
	cmd.WaitDelay = 5 * time.Second
	if err := cmd.Start(); err != nil {
		output.Close()
		return err
	}
	w.status.set(apolloTarget, targetStatus{PID: identity.PID, StartTicks: identity.StartTicks, State: "injecting", InjectorPID: cmd.Process.Pid})
	runErr := cmd.Wait()
	closeErr := output.Close()
	if ctx.Err() != nil {
		return ctx.Err()
	}
	if closeErr != nil {
		return closeErr
	}
	data, err := os.ReadFile(attemptPath)
	if err != nil {
		return err
	}
	// As on OD, timeout/exit code alone cannot confirm or disprove installation.
	// Require the agent's exact marker plus an unchanged process identity.
	if !apolloOutputReady(data) {
		return fmt.Errorf("Apollo ready marker missing or injector error (exit: %v)", runErr)
	}
	after, err := w.read(w.procRoot, identity.PID, apolloTarget)
	if err != nil || after != identity {
		return errors.New("Apollo target changed after injection")
	}
	return nil
}
