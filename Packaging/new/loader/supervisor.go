package main

import (
	"context"
	"crypto/sha256"
	"errors"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

type supervisor struct {
	runtimeDirectory string
	stateDirectory   string
	healthDirectory  string
	procRoot         string
	boot             string
	status           *statusStore
	priorityReady    chan struct{}
	priorityOnce     sync.Once
	dynamicMutex     sync.Mutex
	dynamicDesired   map[string]bool
}

func wait(ctx context.Context, duration time.Duration) bool {
	timer := time.NewTimer(duration)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-timer.C:
		return true
	}
}

func waitPriority(ctx context.Context, ready <-chan struct{}, duration time.Duration) bool {
	timer := time.NewTimer(duration)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-ready:
		return true
	case <-timer.C:
		return true
	}
}

func (manager *supervisor) report(agent agentConfig, identity processIdentity, state, reason string) {
	manager.status.set(agent, identity, state, reason)
	if agent.Priority && state != "waiting" && state != "injecting" {
		manager.priorityOnce.Do(func() { close(manager.priorityReady) })
	}
}

func lockFile(path string) (*os.File, error) {
	file, err := os.OpenFile(path, os.O_CREATE|os.O_RDWR, 0600)
	if err != nil {
		return nil, err
	}
	if err := syscall.Flock(int(file.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
		file.Close()
		return nil, err
	}
	return file, nil
}

func unlockFile(file *os.File) {
	syscall.Flock(int(file.Fd()), syscall.LOCK_UN)
	file.Close()
}

func processUID(procRoot string, identity processIdentity) (int, error) {
	data, err := os.ReadFile(filepath.Join(procRoot, strconv.Itoa(identity.PID), "status"))
	if err != nil {
		return 0, err
	}
	for _, line := range strings.Split(string(data), "\n") {
		fields := strings.Fields(line)
		if len(fields) >= 2 && fields[0] == "Uid:" {
			return strconv.Atoi(fields[1])
		}
	}
	return 0, errors.New("target UID unavailable")
}

func (manager *supervisor) prepareAgentFiles(agent agentConfig, identity processIdentity) error {
	if agent.Health == "" && agent.StatusFile == "" {
		return nil
	}
	uid, err := processUID(manager.procRoot, identity)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(manager.healthDirectory, 0711); err != nil {
		return err
	}
	if err := os.Chmod(manager.healthDirectory, 0711); err != nil {
		return err
	}
	if agent.Health != "" {
		directory := filepath.Join(manager.healthDirectory, "drive_hooks")
		if err := os.MkdirAll(directory, 0711); err != nil {
			return err
		}
		if err := os.Chmod(directory, 0711); err != nil {
			return err
		}
		path := filepath.Join(directory, agent.Health)
		file, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0600)
		if err != nil {
			return err
		}
		file.Close()
		if err := os.Chown(path, uid, uid); err != nil {
			return err
		}
		if err := applyHealthFileContext(agent.Target, path); err != nil {
			return err
		}
		return os.Chmod(path, 0600)
	}
	if agent.StatusFile == "vd" {
		directory := filepath.Join(manager.healthDirectory, "vd_hooks")
		if err := os.MkdirAll(directory, 0700); err != nil {
			return err
		}
		if err := os.Chown(directory, uid, uid); err != nil {
			return err
		}
		return os.Chmod(directory, 0700)
	}
	return nil
}

func (manager *supervisor) pulse(agent agentConfig, identity processIdentity) int64 {
	data, err := os.ReadFile(filepath.Join(manager.healthDirectory, "drive_hooks", agent.Health))
	if err != nil {
		return 0
	}
	fields := strings.Split(strings.TrimSpace(string(data)), "|")
	if len(fields) != 3 || fields[0] != strconv.Itoa(identity.PID) || fields[2] != "v2" {
		return 0
	}
	value, _ := strconv.ParseInt(fields[1], 10, 64)
	return value
}

func (manager *supervisor) windowState(identity processIdentity) (string, string) {
	data, err := os.ReadFile(filepath.Join(manager.healthDirectory, "vd_hooks/status.v1"))
	if err != nil {
		return "injecting", "awaiting window agent status"
	}
	fields := strings.SplitN(strings.TrimSpace(string(data)), "|", 4)
	if len(fields) != 4 || fields[0] != "v2:"+identityToken(manager.boot, identity) {
		return "injecting", "awaiting current window agent identity"
	}
	switch fields[2] {
	case "active":
		return "active", fields[3]
	case "failed":
		return "failed", fields[3]
	default:
		return "injecting", fields[3]
	}
}

func (manager *supervisor) condition(ctx context.Context, agent agentConfig, identity processIdentity) (bool, error) {
	if agent.Dynamic {
		manager.dynamicMutex.Lock()
		enabled := manager.dynamicDesired[agent.ID]
		manager.dynamicMutex.Unlock()
		if !enabled {
			return false, nil
		}
	}
	switch agent.Condition {
	case "apollo":
		data, err := os.ReadFile("/data/user_de/0/ru.big.town.anative/files/apollo_settings_runtime.v1")
		return err == nil && apolloEnabled(string(data), manager.boot), nil
	case "keyboard-en", "keyboard-ru":
		mode, err := keyboardMode(filepath.Join(manager.stateDirectory, "keyboard-choice.json"), manager.boot, identity, func() string { return androidCommand(ctx, "settings", "get", "global", "voyahtune_keyboard_mode") })
		return agent.Condition == "keyboard-"+mode, err
	}
	return true, nil
}

func (manager *supervisor) cycle(ctx context.Context, agent agentConfig, hash string, observation *healthObservation) {
	identity, err := findProcess(manager.procRoot, agent.Target)
	if err != nil {
		manager.report(agent, identity, "failed", err.Error())
		return
	}
	if identity.PID == 0 {
		manager.report(agent, identity, "waiting", "")
		return
	}
	lock, err := lockFile(filepath.Join(manager.stateDirectory, "target."+agent.Target+".lock"))
	if err != nil {
		if !errors.Is(err, syscall.EWOULDBLOCK) {
			manager.report(agent, identity, "failed", err.Error())
		}
		return
	}
	defer unlockFile(lock)
	enabled, err := manager.condition(ctx, agent, identity)
	if err != nil {
		manager.report(agent, identity, "failed", err.Error())
		return
	}
	if !enabled {
		manager.report(agent, identity, "disabled", "")
		return
	}
	if agent.Dynamic || agent.Allow32Bit {
		uid, err := processUID(manager.procRoot, identity)
		executable, linkError := os.Readlink(filepath.Join(manager.procRoot, strconv.Itoa(identity.PID), "exe"))
		abi := filepath.Base(executable)
		if err != nil || uid < 0 || uid >= 100000 || (agent.Dynamic && uid < 10000) || linkError != nil ||
			(abi != "app_process64" && !(agent.Allow32Bit && abi == "app_process32")) {
			manager.report(agent, identity, "unsupported", "target user or runtime ABI is unsupported")
			return
		}
	}
	now, err := uptimeSeconds(manager.procRoot)
	if err != nil {
		manager.report(agent, identity, "failed", err.Error())
		return
	}
	path := filepath.Join(manager.stateDirectory, agent.ID+".attempt.json")
	var record attemptRecord
	if err := readJSON(path, &record); err != nil && !os.IsNotExist(err) {
		manager.report(agent, identity, "failed", "invalid attempt ledger: "+err.Error())
		return
	}
	record = observeTarget(record, manager.boot, identity, hash, now, agent.CrashLimit)
	if record.Blocked {
		if err := writeAtomic(path, record); err != nil {
			log.Printf("persist crash circuit %s: %v", agent.ID, err)
		}
		manager.report(agent, identity, "failed", "rapid process restart circuit is open; requires next boot or updated agent in a restarted target")
		return
	}
	if record.State == "restart-required" {
		manager.report(agent, identity, record.State, "agent source changed inside a live process")
		return
	}
	if record.State == "active" {
		if agent.Health != "" {
			if observation.expired(identity, now, manager.pulse(agent, identity)) {
				record.State = "failed"
			} else {
				if observation.LastHealthy > 0 && record.Attempts != 0 {
					record.Attempts = 0
					if err := writeAtomic(path, record); err != nil {
						manager.report(agent, identity, "failed", err.Error())
						return
					}
				}
				manager.report(agent, identity, "active", "")
				return
			}
		} else if agent.StatusFile == "vd" {
			state, reason := manager.windowState(identity)
			manager.report(agent, identity, state, reason)
			return
		} else if agent.Lifetime == "eternalized" {
			manager.report(agent, identity, "active", "")
			return
		} else {
			record.State = "failed"
		}
	}
	if !record.canAttempt(now, agent.MaxAttempts) {
		manager.report(agent, identity, "failed", record.failureReason())
		return
	}
	// Frida updates a shared SELinux policy during attach, even for different targets.
	injectionLock, err := lockFile(filepath.Join(manager.stateDirectory, "injection.lock"))
	if err != nil {
		if !errors.Is(err, syscall.EWOULDBLOCK) {
			manager.report(agent, identity, "failed", err.Error())
		}
		return
	}
	defer unlockFile(injectionLock)
	if err := manager.prepareAgentFiles(agent, identity); err != nil {
		manager.report(agent, identity, "failed", err.Error())
		return
	}
	record.State = "injecting"
	record.LastError = ""
	record.Attempts++
	record.LastAttach = now
	record.NextAttempt = now + retryDelay(record.Attempts)
	if err := writeAtomic(path, record); err != nil {
		manager.report(agent, identity, "failed", err.Error())
		return
	}
	manager.report(agent, identity, "injecting", "")
	runner := injector{Directory: manager.runtimeDirectory, StateDirectory: manager.stateDirectory, ProcRoot: manager.procRoot, ExtraLocks: []*os.File{injectionLock}}
	err = runner.run(ctx, agent, identity, lock, func(child processIdentity) error {
		record.Injector = child
		return writeAtomic(path, record)
	}, func() error {
		record.State = "active"
		if err := writeAtomic(path, record); err != nil {
			return err
		}
		manager.report(agent, identity, "active", "")
		return nil
	})
	if ctx.Err() != nil {
		return
	}
	record.Injector = processIdentity{}
	if err != nil {
		record.State = "failed"
		record.LastError = err.Error()
	} else {
		record.State = "active"
		record.LastError = ""
	}
	if completed, clockError := uptimeSeconds(manager.procRoot); clockError == nil {
		record.NextAttempt = completed + retryDelay(record.Attempts)
	}
	if persistError := writeAtomic(path, record); persistError != nil {
		manager.report(agent, identity, "failed", persistError.Error())
		return
	}
	reason := ""
	if err != nil {
		reason = err.Error()
	}
	manager.report(agent, identity, record.State, reason)
}

func (manager *supervisor) runAgent(ctx context.Context, agent agentConfig) {
	data, err := os.ReadFile(filepath.Join(manager.runtimeDirectory, agent.Script))
	if err != nil {
		manager.report(agent, processIdentity{}, "failed", err.Error())
		return
	}
	hash := fmt.Sprintf("%x", sha256.Sum256(data))
	var observation healthObservation
	interval := time.Second
	if agent.Priority {
		interval = 100 * time.Millisecond
	}
	for ctx.Err() == nil {
		manager.cycle(ctx, agent, hash, &observation)
		if !wait(ctx, interval) {
			return
		}
	}
}

func (manager *supervisor) run(ctx context.Context, config loaderConfig) error {
	if err := os.MkdirAll(manager.stateDirectory, 0700); err != nil {
		return err
	}
	lock, err := lockFile(filepath.Join(manager.stateDirectory, "loader.lock"))
	if err != nil {
		return fmt.Errorf("supervisor already running or lock unavailable: %w", err)
	}
	defer unlockFile(lock)
	boot, err := bootID(manager.procRoot)
	if err != nil {
		return err
	}
	manager.boot = boot
	command, err := os.ReadFile(filepath.Join(manager.procRoot, strconv.Itoa(os.Getpid()), "cmdline"))
	if err != nil {
		return err
	}
	name, _, _ := strings.Cut(string(command), "\x00")
	self, err := readIdentity(manager.procRoot, os.Getpid(), name)
	if err != nil {
		return err
	}
	workerContext, cancel := context.WithCancel(ctx)
	var workers sync.WaitGroup
	publisher := statusPublisher{path: filepath.Join(manager.stateDirectory, "status.json"), publicPath: filepath.Join(manager.stateDirectory, "hook-status.v1")}
	defer func() {
		cancel()
		workers.Wait()
		now, _ := uptimeSeconds(manager.procRoot)
		stoppedContext, stop := context.WithTimeout(context.Background(), 3*time.Second)
		defer stop()
		if err := publisher.publish(stoppedContext, loaderStatus{1, boot, self, now, "stopped", manager.status.snapshot(manager.procRoot)}); err != nil {
			log.Printf("publish stopped status: %v", err)
		}
	}()
	gate := make(chan struct{})
	workers.Add(1)
	go func() {
		defer workers.Done()
		if waitPriority(workerContext, manager.priorityReady, time.Duration(config.PrioritySeconds)*time.Second) {
			close(gate)
		}
	}()
	start := func(agent agentConfig) {
		manager.status.set(agent, processIdentity{}, "waiting", "")
		workers.Add(1)
		go func() {
			defer workers.Done()
			if !agent.Priority {
				select {
				case <-workerContext.Done():
					return
				case <-gate:
				}
			}
			manager.runAgent(workerContext, agent)
		}()
	}
	known := map[string]bool{}
	for _, agent := range config.Agents {
		known[agent.ID] = true
		start(agent)
	}
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	cycles := 0
	for {
		select {
		case <-ctx.Done():
			return nil
		case <-ticker.C:
			now, err := uptimeSeconds(manager.procRoot)
			if err != nil {
				return err
			}
			snapshot := loaderStatus{1, boot, self, now, "running", manager.status.snapshot(manager.procRoot)}
			if err := publisher.publish(ctx, snapshot); err != nil {
				log.Printf("publish status: %v", err)
			}
			cycles++
			select {
			case <-gate:
			default:
				continue
			}
			if cycles%5 != 0 {
				continue
			}
			agents := dynamicAgents(androidCommand(ctx, "settings", "get", "global", "voyahtune_fullscreen_apps"), androidCommand(ctx, "settings", "get", "global", "voyahtune_dpi_packages"))
			manager.dynamicMutex.Lock()
			manager.dynamicDesired = map[string]bool{}
			for _, agent := range agents {
				manager.dynamicDesired[agent.ID] = true
			}
			manager.dynamicMutex.Unlock()
			for _, agent := range agents {
				if !known[agent.ID] && len(known) < 128 {
					known[agent.ID] = true
					start(agent)
				}
			}
		}
	}
}
