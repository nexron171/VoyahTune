package main

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

type injectionOutput struct {
	mutex  sync.Mutex
	buffer bytes.Buffer
	file   *os.File
}

func (output *injectionOutput) Write(data []byte) (int, error) {
	output.mutex.Lock()
	defer output.mutex.Unlock()
	if output.buffer.Len() < 512*1024 {
		output.buffer.Write(data)
	}
	if output.file != nil {
		return output.file.Write(data)
	}
	return len(data), nil
}

func (output *injectionOutput) text() string {
	output.mutex.Lock()
	defer output.mutex.Unlock()
	return output.buffer.String()
}

func outputFailure(output string) string {
	lower := strings.ToLower(output)
	for _, failure := range []string{"unable to find", "failed to attach", "cannot parse", "no such process", "hook failed", `"type":"error"`} {
		if index := strings.Index(lower, failure); index >= 0 {
			line := strings.SplitN(output[index:], "\n", 2)[0]
			if len(line) > 240 {
				line = line[:240]
			}
			return line
		}
	}
	return ""
}

func outputReady(output string, markers []string) bool {
	if outputFailure(output) != "" {
		return false
	}
	for _, marker := range markers {
		if !strings.Contains(output, marker) {
			return false
		}
	}
	return len(markers) > 0
}

func injectorArguments(agent agentConfig, identity processIdentity, directory string) []string {
	arguments := []string{"-R", "v8", "-p", strconv.Itoa(identity.PID), "-s", filepath.Join(directory, agent.Script)}
	if agent.Lifetime == "eternalized" {
		arguments = append(arguments, "-e")
	}
	return arguments
}

type injector struct {
	Directory      string
	StateDirectory string
	ProcRoot       string
	ReadIdentity   func(string, int, string) (processIdentity, error)
	ExtraLocks     []*os.File
}

func (runner injector) run(ctx context.Context, agent agentConfig, identity processIdentity, lock *os.File, onStarted func(processIdentity) error, onReady func() error) error {
	read := runner.ReadIdentity
	if read == nil {
		read = readIdentity
	}
	before, err := read(runner.ProcRoot, identity.PID, agent.Target)
	if err != nil || before != identity {
		return errors.New("target changed before injection")
	}
	file, err := os.OpenFile(filepath.Join(runner.StateDirectory, agent.ID+".log"), os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0600)
	if err != nil {
		return err
	}
	defer file.Close()
	output := &injectionOutput{file: file}
	childContext, cancel := context.WithCancel(ctx)
	defer cancel()
	executable, err := injectorExecutable(runner.Directory, runner.ProcRoot, identity.PID)
	if err != nil {
		return err
	}
	arguments := injectorArguments(agent, identity, runner.Directory)
	processName := filepath.Base(executable)
	command := exec.CommandContext(childContext, executable, arguments...)
	command.Stdout, command.Stderr = output, output
	command.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	configureInjectorParentDeath(command.SysProcAttr)
	command.Cancel = func() error {
		// Kill the injector and any descendants without an Android timeout wrapper.
		err := syscall.Kill(-command.Process.Pid, syscall.SIGKILL)
		if errors.Is(err, syscall.ESRCH) {
			return os.ErrProcessDone
		}
		return err
	}
	command.WaitDelay = 5 * time.Second
	// A surviving injector keeps the target lock after a supervisor crash.
	if lock != nil {
		command.ExtraFiles = []*os.File{lock}
	}
	command.ExtraFiles = append(command.ExtraFiles, runner.ExtraLocks...)
	if err := command.Start(); err != nil {
		return err
	}
	defer command.Cancel()
	finished := make(chan error, 1)
	go func() { finished <- command.Wait() }()
	injectorIdentity, err := read(runner.ProcRoot, command.Process.Pid, processName)
	if err != nil {
		injectorIdentity = processIdentity{PID: command.Process.Pid}
	}
	if err := onStarted(injectorIdentity); err != nil {
		cancel()
		<-finished
		return err
	}
	ticker := time.NewTicker(100 * time.Millisecond)
	defer ticker.Stop()
	timeout := time.NewTimer(time.Duration(agent.TimeoutSeconds) * time.Second)
	defer timeout.Stop()
	ready := false
	checkReady := func() error {
		if failure := outputFailure(output.text()); failure != "" {
			return errors.New(failure)
		}
		current, err := read(runner.ProcRoot, identity.PID, agent.Target)
		if err != nil || current != identity {
			return errors.New("target changed during injection")
		}
		if !ready && outputReady(output.text(), agent.Ready) {
			if err := onReady(); err != nil {
				return err
			}
			ready = true
		}
		return nil
	}
	for {
		select {
		case <-ctx.Done():
			cancel()
			<-finished
			return ctx.Err()
		case err := <-finished:
			if checkErr := checkReady(); checkErr != nil {
				return checkErr
			}
			if err == nil && ready && outputReady(output.text(), agent.Ready) && agent.Lifetime == "eternalized" {
				return nil
			}
			return fmt.Errorf("injector ended without retained ready agent: %v", err)
		case <-ticker.C:
			if err := checkReady(); err != nil {
				cancel()
				<-finished
				return err
			}
		case <-timeout.C:
			if agent.Lifetime == "attached" && ready {
				continue
			}
			cancel()
			<-finished
			if err := checkReady(); err != nil {
				return err
			}
			return errors.New("agent readiness timeout")
		}
	}
}
