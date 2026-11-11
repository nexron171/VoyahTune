package main

import (
	"context"
	"encoding/json"
	"errors"
	"log"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"

	"voyahtune/unified-loader/agentwire"
	"voyahtune/unified-loader/lifecycle"
	"voyahtune/unified-loader/moduleplan"
	"voyahtune/unified-loader/remoteattach"
	"voyahtune/unified-loader/resident"
)

type residentControl interface {
	Ensure(context.Context, agentwire.Target, agentwire.Plan) (agentwire.Status, error)
	Quiesce(context.Context) error
	Snapshot() lifecycle.State
	Sweep() error
}

type nativeSupervisor struct {
	runtimeDirectory       string
	stateDirectory         string
	procRoot               string
	boot                   string
	config                 nativeLoaderConfig
	status                 *statusStore
	engine                 residentControl
	quiesceOnce            sync.Once
	mutex                  sync.Mutex
	agents                 map[string]nativeAgentConfig
	wanted                 map[string]bool
	activeAttempts         map[string]processIdentity
	apolloPath             string
	readSetting            func(context.Context, string) string
	restartLauncherProcess func(agentwire.Target) error
	updateBlock            string
	migrationBoot          string
}

func (manager *nativeSupervisor) report(agent nativeAgentConfig, identity processIdentity, state, reason string, ack *agentwire.Status, modules []string, architecture remoteattach.Architecture) {
	value := agentStatus{Identity: identity, Target: agent.Target, State: state, Reason: reason}
	value.UpdatedUptime, _ = uptimeSeconds(manager.procRoot)
	value.Architecture = string(architecture)
	if ack != nil {
		value.UID = ack.UID
		value.NativeID = ack.NativeID
		value.RuntimeID = ack.RuntimeID
		value.Revision = ack.Revision
		value.Modules = append([]string{}, modules...)
	}
	manager.status.report(agent, value)
}

func (manager *nativeSupervisor) definitions(target string) []nativeAgentConfig {
	manager.mutex.Lock()
	defer manager.mutex.Unlock()
	result := []nativeAgentConfig{}
	for _, agent := range manager.agents {
		if agent.Target == target {
			result = append(result, agent)
		}
	}
	return result
}

func (manager *nativeSupervisor) enabled(ctx context.Context, agent nativeAgentConfig, identity processIdentity) (bool, error) {
	manager.mutex.Lock()
	wanted := manager.wanted[agent.ID]
	manager.mutex.Unlock()
	if !wanted {
		return false, nil
	}
	switch agent.Condition {
	case "apollo":
		data, err := os.ReadFile(manager.apolloPath)
		return err == nil && apolloEnabled(string(data), manager.boot), nil
	case "keyboard-en", "keyboard-ru":
		mode, err := keyboardMode(filepath.Join(manager.stateDirectory, "keyboard-choice.json"), manager.boot, identity, func() string { return manager.readSetting(ctx, "voyahtune_keyboard_mode") })
		return agent.Condition == "keyboard-"+mode, err
	default:
		return true, nil
	}
}

func (manager *nativeSupervisor) selection(ctx context.Context, definitions []nativeAgentConfig, identity processIdentity) (moduleplan.TargetGroup, map[string]string, error) {
	selections := []moduleplan.Selection{}
	configuration := map[string]string{}
	for _, agent := range definitions {
		enabled, err := manager.enabled(ctx, agent, identity)
		if err != nil {
			return moduleplan.TargetGroup{}, nil, err
		}
		selections = append(selections, moduleplan.Selection{ID: agent.ID, Target: agent.Target, Modules: agent.Modules, Enabled: enabled})
		if enabled && agent.ID == "apollo" {
			configuration["apollo.enabled"] = "1"
			configuration["apollo.bootId"] = manager.boot
		}
	}
	groups, err := moduleplan.Group(selections)
	if err != nil {
		return moduleplan.TargetGroup{}, nil, err
	}
	if len(groups) != 1 {
		return moduleplan.TargetGroup{}, nil, errors.New("one target group required")
	}
	return groups[0], configuration, nil
}

func (manager *nativeSupervisor) cycle(ctx context.Context, name string) {
	identity, err := findProcess(manager.procRoot, name)
	manager.cycleProcess(ctx, name, identity, err)
}

func (manager *nativeSupervisor) cycleProcess(ctx context.Context, name string, identity processIdentity, err error) {
	definitions := manager.definitions(name)
	byID := map[string]nativeAgentConfig{}
	for _, agent := range definitions {
		byID[agent.ID] = agent
	}
	var architecture remoteattach.Architecture
	reportAll := func(identity processIdentity, state, reason string) {
		for _, agent := range definitions {
			manager.report(agent, identity, state, reason, nil, nil, architecture)
		}
	}
	if err != nil {
		reportAll(identity, "failed", err.Error())
		return
	}
	if identity.PID == 0 {
		reportAll(identity, "waiting", "")
		return
	}
	if manager.engine.Snapshot().Phase != lifecycle.Running {
		reportAll(identity, "stopping", "runtime admission closed")
		return
	}
	group, configuration, err := manager.selection(ctx, definitions, identity)
	if err != nil {
		reportAll(identity, "failed", err.Error())
		return
	}
	target, err := remoteattach.ReadTarget(manager.procRoot, identity.PID)
	if err != nil {
		reportAll(identity, "failed", err.Error())
		return
	}
	if target.BootID != manager.boot || target.StartTicks != identity.StartTicks || target.UID >= 100000 {
		reportAll(identity, "unsupported", "target identity or Android user changed")
		return
	}
	architecture, err = remoteattach.DetectArchitecture(manager.procRoot, identity.PID)
	if err != nil {
		reportAll(identity, "unsupported", err.Error())
		return
	}
	for _, member := range group.Members {
		if member.Enabled && byID[member.ID].Dynamic && target.UID < 10000 {
			reportAll(identity, "unsupported", "dynamic clients require an application UID")
			return
		}
	}
	target.NativeID = manager.config.Code.NativeID
	target.RuntimeID = manager.config.Code.RuntimeID
	for _, member := range group.Members {
		if member.Enabled {
			manager.status.mutex.Lock()
			previous := manager.status.agents[member.ID]
			manager.status.mutex.Unlock()
			if previous.Identity != identity || previous.State != "active" || !slices.Equal(previous.Modules, group.Modules) {
				manager.report(byID[member.ID], identity, "injecting", "", nil, nil, architecture)
			}
		}
	}
	timeout := time.Second
	for _, agent := range definitions {
		if candidate := time.Duration(agent.TimeoutSeconds) * time.Second; candidate > timeout {
			timeout = candidate
		}
	}
	operation, cancel := context.WithTimeout(ctx, timeout)
	ack, err := manager.engine.Ensure(operation, target, agentwire.Plan{Schema: 1, RuntimeID: target.RuntimeID, Modules: group.Modules, Configuration: configuration})
	cancel()
	if err != nil {
		if state, reason, handled := manager.recoverLauncher(ctx, name, target, ack); handled {
			manager.recordLauncherFailure(name, identity, reason)
			reportAll(identity, state, reason)
			return
		}
		state := "failed"
		if errors.Is(err, agentwire.ErrRuntimeChanged) || errors.Is(err, resident.ErrRebootRequired) || errors.Is(err, resident.ErrRestartRequired) {
			state = "restart-required"
		}
		if errors.Is(err, resident.ErrQuiescing) {
			state = "stopping"
		}
		if manager.recordAttemptResult(name, identity, false, err) {
			state = "waiting"
		}
		reportAll(identity, state, err.Error())
		return
	}
	if manager.engine.Snapshot().Phase != lifecycle.Running {
		reportAll(identity, "stopping", "runtime admission closed")
		return
	}
	for _, member := range group.Members {
		state := "disabled"
		if member.Enabled {
			state = "active"
		}
		manager.report(byID[member.ID], identity, state, "", &ack, group.Modules, architecture)
	}
	if len(group.Modules) > 0 {
		manager.recordAttemptResult(name, identity, true, nil)
	}
}

func (manager *nativeSupervisor) attemptPath(name string) string {
	return filepath.Join(manager.stateDirectory, "native."+name+".attempt.json")
}

func (manager *nativeSupervisor) admissionBlock() error {
	update := manager.updateBlock
	if update == "" {
		update = updateBlockPath
	}
	migration := manager.migrationBoot
	if migration == "" {
		migration = migrationBootPath
	}
	return admissionBlock(update, migration, manager.boot)
}

func (manager *nativeSupervisor) admit(target agentwire.Target) error {
	if err := manager.admissionBlock(); err != nil {
		return err
	}
	command, err := os.ReadFile(filepath.Join(manager.procRoot, strconv.Itoa(target.PID), "cmdline"))
	if err != nil {
		return err
	}
	name, _, _ := strings.Cut(string(command), "\x00")
	if !strings.Contains(name, ".") {
		name = filepath.Base(name)
	}
	definitions := manager.definitions(name)
	if len(definitions) == 0 {
		return errors.New("target is no longer managed")
	}
	maximum, crashLimit := 1, 0
	for _, agent := range definitions {
		if agent.MaxAttempts > maximum {
			maximum = agent.MaxAttempts
		}
		if agent.CrashLimit > 0 && (crashLimit == 0 || agent.CrashLimit < crashLimit) {
			crashLimit = agent.CrashLimit
		}
	}
	now, err := uptimeSeconds(manager.procRoot)
	if err != nil {
		return err
	}
	var previous attemptRecord
	if err := readJSON(manager.attemptPath(name), &previous); err != nil && !os.IsNotExist(err) {
		return err
	}
	code := target.NativeID + ":" + target.RuntimeID
	record := observeTarget(previous, manager.boot, processIdentity{target.PID, target.StartTicks}, code, now, crashLimit)
	if !record.canAttempt(now, maximum) {
		if record.State == "waiting" && record.SafePointFailures > 0 && now < record.SafePointWaitUntil {
			return &resident.SafePointNotReadyError{Reason: record.failureReason()}
		}
		return errors.New(record.failureReason())
	}
	record.State = "injecting"
	record.LastError = ""
	record.Attempts++
	record.LastAttach = now
	record.NextAttempt = now + retryDelay(record.Attempts)
	return writeAtomic(manager.attemptPath(name), record)
}

func (manager *nativeSupervisor) recordAttemptResult(name string, identity processIdentity, active bool, failure error) bool {
	manager.mutex.Lock()
	recorded := active && failure == nil && manager.activeAttempts[name] == identity
	if !active || failure != nil {
		delete(manager.activeAttempts, name)
	}
	manager.mutex.Unlock()
	if recorded {
		return false
	}
	var record attemptRecord
	if err := readJSON(manager.attemptPath(name), &record); err != nil || record.Boot != manager.boot || record.Identity != identity {
		return false
	}
	previous := record
	state := "failed"
	reason := ""
	deferred := false
	if active {
		state = "active"
	}
	if failure != nil {
		reason = failure.Error()
	}
	var waiting *resident.SafePointNotReadyError
	if errors.As(failure, &waiting) {
		if now, err := uptimeSeconds(manager.procRoot); err == nil && record.deferSafePoint(now) {
			state = "waiting"
			deferred = true
		}
	}
	if record.State == "active" || record.State == "restart-required" || record.Blocked {
		state = record.State
	}
	record.State = state
	record.LastError = reason
	if record != previous {
		if err := writeAtomic(manager.attemptPath(name), record); err != nil {
			log.Printf("persist native attempt: %v", err)
			return false
		}
	}
	if active && failure == nil && record.State == "active" {
		manager.mutex.Lock()
		if manager.activeAttempts == nil {
			manager.activeAttempts = make(map[string]processIdentity)
		}
		manager.activeAttempts[name] = identity
		manager.mutex.Unlock()
	}
	return deferred
}

func requireAndroidARM(procRoot string, pid int) error {
	_, err := remoteattach.DetectArchitecture(procRoot, pid)
	return err
}

func nativeDynamicAgents(fullscreen string) []nativeAgentConfig {
	excluded := map[string]bool{"com.qinggan.app.hiboard": true}
	for _, agent := range compiledAgents() {
		excluded[agent.Target] = true
	}
	packages := map[string]bool{}
	for _, name := range strings.Split(fullscreen, ",") {
		name = strings.TrimSpace(name)
		if safeName(name) && strings.Contains(name, ".") && !strings.HasSuffix(name, ".") && !excluded[name] {
			packages[name] = true
		}
	}
	result := []nativeAgentConfig{}
	for name := range packages {
		result = append(result, nativeAgentConfig{ID: "client." + name, Target: name, Modules: []string{"client." + name}, MaxAttempts: 1, TimeoutSeconds: 35, CrashLimit: 2, Dynamic: true})
	}
	return result
}

func (manager *nativeSupervisor) startQuiesce() {
	manager.quiesceOnce.Do(func() { go manager.engine.Quiesce(context.Background()) })
}

func (manager *nativeSupervisor) run(ctx context.Context, configurationError error) error {
	lock, err := lockNativeState(manager.stateDirectory)
	if err != nil {
		return err
	}
	defer unlockFile(lock)
	manager.boot, err = bootID(manager.procRoot)
	if err != nil {
		return err
	}
	command, err := os.ReadFile(filepath.Join(manager.procRoot, strconv.Itoa(os.Getpid()), "cmdline"))
	if err != nil {
		return err
	}
	executable, _, _ := strings.Cut(string(command), "\x00")
	self, err := readIdentity(manager.procRoot, os.Getpid(), executable)
	if err != nil {
		return err
	}
	manager.engine, err = openNativeResidents(manager.runtimeDirectory, manager.stateDirectory, manager.procRoot,
		manager.config.Code.SHA256[agentDEXName], resident.WithAttachAdmission(manager.admit))
	if err != nil {
		return err
	}
	if err := manager.engine.Sweep(); err != nil {
		return err
	}
	identity := lifecycle.ControlIdentity{BootID: manager.boot, PID: self.PID, StartTicks: self.StartTicks}
	if err := writeAtomic(filepath.Join(manager.stateDirectory, "control-identity.json"), identity); err != nil {
		return err
	}
	socket := filepath.Join(manager.stateDirectory, "control.sock")
	if info, err := os.Lstat(socket); err == nil {
		if info.Mode()&os.ModeSocket == 0 {
			return errors.New("unexpected control socket destination")
		}
		if err := os.Remove(socket); err != nil {
			return err
		}
	} else if !os.IsNotExist(err) {
		return err
	}
	control, err := lifecycle.ListenControl(socket, lifecycle.NewControl(identity, manager.engine))
	if err != nil {
		return err
	}
	workerContext, cancel := context.WithCancel(ctx)
	var workers sync.WaitGroup
	failures := make(chan error, 1)
	workers.Add(1)
	go func() { defer workers.Done(); failures <- control.Serve(workerContext) }()
	defer func() { manager.engine.Quiesce(context.Background()); cancel(); workers.Wait() }()
	manager.agents = map[string]nativeAgentConfig{}
	manager.wanted = map[string]bool{}
	if manager.apolloPath == "" {
		manager.apolloPath = "/data/user_de/0/ru.big.town.anative/files/apollo_settings_runtime.v1"
	}
	if manager.readSetting == nil {
		manager.readSetting = func(ctx context.Context, key string) string {
			return androidCommand(ctx, "settings", "get", "global", key)
		}
	}
	targetWorkers := agentWorkers{manager: manager, context: workerContext, group: &workers, targets: map[string]bool{}}
	blocked := func() bool { return manager.admissionBlock() != nil }
	if blocked() || configurationError != nil {
		manager.startQuiesce()
	}
	if configurationError == nil && !blocked() {
		targetWorkers.start(manager.config.Agents)
		workers.Add(1)
		go func() {
			defer workers.Done()
			targetWorkers.watchDynamic()
		}()
	}
	publisher := statusPublisher{path: filepath.Join(manager.stateDirectory, "status.json"), publicPath: filepath.Join(manager.stateDirectory, "hook-status.v1")}
	diagnostics := diagnosticsPublisher{command: androidCommand}
	publish := func(state string) {
		now, _ := uptimeSeconds(manager.procRoot)
		agents, changes := manager.status.snapshot(manager.procRoot)
		snapshot := loaderStatus{Schema: 1, Boot: manager.boot, Loader: self, UpdatedUptime: now, State: state, Agents: agents, RequiredChanges: changes}
		if err := publisher.publish(context.Background(), snapshot); err != nil {
			log.Printf("publish native status: %v", err)
		}
		reason := ""
		if configurationError != nil {
			reason = configurationError.Error()
		}
		if err := diagnostics.publish(context.Background(), snapshot, manager.config.Code, reason); err != nil {
			log.Printf("publish runtime diagnostics: %v", err)
		}
	}
	if configurationError != nil {
		log.Printf("native configuration unavailable: %v", configurationError)
	}
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	for {
		state := "running"
		if configurationError != nil {
			state = "failed"
		}
		if manager.engine.Snapshot().Phase != lifecycle.Running {
			state = string(manager.engine.Snapshot().Phase)
		}
		publish(state)
		select {
		case <-ctx.Done():
			manager.engine.Quiesce(context.Background())
			publish("stopped")
			return nil
		case err := <-failures:
			return err
		case <-ticker.C:
		}
		if blocked() {
			manager.startQuiesce()
		}
		if err := manager.engine.Sweep(); err != nil {
			log.Printf("native process reconciliation: %v", err)
		}
	}
}

func nativeControl(ctx context.Context, stateDirectory, procRoot, command string) (lifecycle.ControlResponse, error) {
	var identity lifecycle.ControlIdentity
	if err := readRuntimeJSON(filepath.Join(stateDirectory, "control-identity.json"), &identity); err != nil {
		return lifecycle.ControlResponse{}, err
	}
	boot, err := bootID(procRoot)
	if err != nil || boot != identity.BootID {
		return lifecycle.ControlResponse{}, errors.New("control boot identity changed")
	}
	data, err := os.ReadFile(filepath.Join(procRoot, strconv.Itoa(identity.PID), "cmdline"))
	if err != nil {
		return lifecycle.ControlResponse{}, err
	}
	executable, _, _ := strings.Cut(string(data), "\x00")
	if filepath.Base(executable) != "voyahtune-loader" {
		return lifecycle.ControlResponse{}, errors.New("control identity belongs to another executable")
	}
	actual, err := readIdentity(procRoot, identity.PID, executable)
	if err != nil || actual.StartTicks != identity.StartTicks {
		return lifecycle.ControlResponse{}, errors.New("control loader process changed")
	}
	return lifecycle.RequestControl(ctx, filepath.Join(stateDirectory, "control.sock"), identity, command)
}

func writeControlResult(response lifecycle.ControlResponse) error {
	return json.NewEncoder(os.Stdout).Encode(response)
}

func (manager *nativeSupervisor) recordLauncherFailure(name string, identity processIdentity, reason string) {
	var record attemptRecord
	if err := readJSON(manager.attemptPath(name), &record); err != nil || record.Boot != manager.boot || record.Identity != identity {
		return
	}
	record.State = "restart-required"
	record.LastError = reason
	if err := writeAtomic(manager.attemptPath(name), record); err != nil {
		log.Printf("persist launcher recovery: %v", err)
	}
}
