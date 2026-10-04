// PI keeps its Frida sessions attached. Apollo alone uses the shared OD lifecycle.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"log"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

type processIdentity struct {
	PID        int
	StartTicks string
}
type targetStatus struct {
	PID         int    `json:"pid"`
	StartTicks  string `json:"startTicks"`
	State       string `json:"state"`
	InjectorPID int    `json:"injectorPid,omitempty"`
	Error       string `json:"error,omitempty"`
}
type loaderStatus struct {
	Schema               int                     `json:"schema"`
	Infrastructure       string                  `json:"infrastructure"`
	LoaderPID            int                     `json:"loaderPid"`
	LoaderStartTicks     string                  `json:"loaderStartTicks"`
	BootID               string                  `json:"bootId"`
	UpdatedUptimeSeconds int64                   `json:"updatedUptimeSeconds"`
	State                string                  `json:"state"`
	Targets              map[string]targetStatus `json:"targets"`
}
type statusStore struct {
	mu    sync.Mutex
	value loaderStatus
}

func (s *statusStore) set(name string, value targetStatus) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.value.Targets[name] = value
}
func (s *statusStore) publish(path string, uptime int64) error {
	s.mu.Lock()
	s.value.UpdatedUptimeSeconds = uptime
	data, err := json.Marshal(s.value)
	s.mu.Unlock()
	if err != nil {
		return err
	}
	return writeAtomic(path, append(data, '\n'))
}
func writeAtomic(path string, data []byte) error {
	f, err := os.CreateTemp(filepath.Dir(path), ".pi-status-")
	if err != nil {
		return err
	}
	name := f.Name()
	defer os.Remove(name)
	if _, err = f.Write(data); err != nil {
		f.Close()
		return err
	}
	if err = f.Chmod(0644); err != nil {
		f.Close()
		return err
	}
	if err = f.Close(); err != nil {
		return err
	}
	return os.Rename(name, path)
}
func safeName(name string) bool {
	if name == "" || strings.HasPrefix(name, ".") {
		return false
	}
	for _, c := range name {
		if !(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '.' || c == '_' || c == '-') {
			return false
		}
	}
	return true
}
func readConfig(scriptsDir string) (map[string][]string, error) {
	data, err := os.ReadFile(filepath.Join(scriptsDir, "injects.json"))
	if err != nil {
		return nil, err
	}
	var cfg map[string][]string
	if err := json.Unmarshal(data, &cfg); err != nil {
		return nil, err
	}
	if len(cfg) == 0 {
		return nil, errors.New("empty injection config")
	}
	for name, scripts := range cfg {
		if !safeName(name) || len(scripts) == 0 {
			return nil, fmt.Errorf("invalid target %q", name)
		}
		seen := map[string]bool{}
		for _, script := range scripts {
			if script == "apollo_tech.js" && (name != apolloTarget || len(scripts) != 1) {
				return nil, errors.New("Apollo must use its dedicated OD lifecycle target")
			}
			if !safeName(script) || !strings.HasSuffix(script, ".js") || seen[script] {
				return nil, fmt.Errorf("invalid script %q for %s", script, name)
			}
			seen[script] = true
			info, err := os.Stat(filepath.Join(scriptsDir, script))
			if err != nil {
				return nil, err
			}
			if !info.Mode().IsRegular() || info.Size() == 0 {
				return nil, fmt.Errorf("empty/nonregular script %s", script)
			}
		}
	}
	info, err := os.Stat(filepath.Join(scriptsDir, "frida-inject"))
	if err != nil {
		return nil, err
	}
	if !info.Mode().IsRegular() || info.Size() == 0 || info.Mode().Perm()&0111 == 0 {
		return nil, errors.New("frida-inject is not an executable regular file")
	}
	return cfg, nil
}
func readIdentity(procRoot string, pid int, name string) (processIdentity, error) {
	dir := filepath.Join(procRoot, strconv.Itoa(pid))
	cmdline, err := os.ReadFile(filepath.Join(dir, "cmdline"))
	if err != nil {
		return processIdentity{}, err
	}
	argv0, _, _ := strings.Cut(string(cmdline), "\x00")
	// Package targets must match the exact main process. Only an executable target
	// such as system_server/loaderFrida may use a path basename.
	if argv0 != name && !(strings.IndexByte(name, '.') < 0 && filepath.Base(argv0) == name) {
		return processIdentity{}, errors.New("different process name")
	}
	stat, err := os.ReadFile(filepath.Join(dir, "stat"))
	if err != nil {
		return processIdentity{}, err
	}
	// comm may contain spaces and ')': split at the last closing parenthesis.
	end := strings.LastIndexByte(string(stat), ')')
	if end < 0 {
		return processIdentity{}, errors.New("invalid proc stat")
	}
	fields := strings.Fields(string(stat[end+1:]))
	if len(fields) <= 19 {
		return processIdentity{}, errors.New("short proc stat")
	}
	if _, err := strconv.ParseUint(fields[19], 10, 64); err != nil {
		return processIdentity{}, err
	}
	return processIdentity{PID: pid, StartTicks: fields[19]}, nil
}
func findProcess(procRoot, name string) (processIdentity, error) {
	entries, err := os.ReadDir(procRoot)
	if err != nil {
		return processIdentity{}, err
	}
	var matches []processIdentity
	for _, entry := range entries {
		pid, err := strconv.Atoi(entry.Name())
		if err != nil || pid <= 0 {
			continue
		}
		identity, err := readIdentity(procRoot, pid, name)
		if err == nil {
			matches = append(matches, identity)
		}
	}
	if len(matches) == 0 {
		return processIdentity{}, nil
	}
	sort.Slice(matches, func(i, j int) bool { return matches[i].PID < matches[j].PID })
	return matches[0], nil
}
func readUptime(procRoot string) (int64, error) {
	data, err := os.ReadFile(filepath.Join(procRoot, "uptime"))
	if err != nil {
		return 0, err
	}
	fields := strings.Fields(string(data))
	if len(fields) == 0 {
		return 0, errors.New("empty uptime")
	}
	value, err := strconv.ParseFloat(fields[0], 64)
	if err != nil || value < 0 {
		return 0, errors.New("invalid uptime")
	}
	return int64(value), nil
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

type retryState struct {
	identity processIdentity
	failures int
}

func (r *retryState) observe(identity processIdentity) {
	if r.identity != identity {
		r.identity = identity
		r.failures = 0
	}
}

const maxFailures = 10

func (r *retryState) allowed() bool { return r.identity.PID > 0 && r.failures < maxFailures }
func (r *retryState) finished(lifetime time.Duration) {
	if lifetime >= time.Minute {
		r.failures = 0
	}
	r.failures++
}

type worker struct {
	scriptsDir, stateDir, procRoot string
	apolloFlag                     string
	poll, retry                    time.Duration
	status                         *statusStore
	// Function boundaries allow host tests to exercise actual session cleanup.
	find func(string, string) (processIdentity, error)
	read func(string, int, string) (processIdentity, error)
}

func injectorArgs(identity processIdentity, scriptsDir string, scripts []string) []string {
	args := []string{"-R", "v8", "-p", strconv.Itoa(identity.PID)}
	for _, script := range scripts {
		args = append(args, "-s", filepath.Join(scriptsDir, script))
	}
	return args
}
func (w worker) run(ctx context.Context, name string, scripts []string) {
	if name == apolloTarget && len(scripts) == 1 && scripts[0] == "apollo_tech.js" {
		w.runApollo(ctx)
		return
	}
	var retry retryState
	for ctx.Err() == nil {
		identity, err := w.find(w.procRoot, name)
		if err != nil {
			w.status.set(name, targetStatus{State: "failed", Error: err.Error()})
			if !wait(ctx, w.retry) {
				return
			}
			continue
		}
		retry.observe(identity)
		if identity.PID == 0 {
			// A target that starts hours after boot still gets its complete retry budget.
			w.status.set(name, targetStatus{State: "waiting"})
			if !wait(ctx, w.poll) {
				return
			}
			continue
		}
		state := targetStatus{PID: identity.PID, StartTicks: identity.StartTicks, State: "failed"}
		if !retry.allowed() {
			state.Error = "injector retry limit reached for this process identity"
			w.status.set(name, state)
			if !wait(ctx, w.poll) {
				return
			}
			continue
		}
		started := time.Now()
		changed, err := w.session(ctx, name, scripts, identity)
		if ctx.Err() != nil {
			return
		}
		if changed {
			continue
		}
		retry.finished(time.Since(started))
		if err != nil {
			state.Error = err.Error()
		} else {
			state.Error = "injector exited; attached hooks are no longer retained"
		}
		w.status.set(name, state)
		if !wait(ctx, w.retry) {
			return
		}
	}
}
func (w worker) session(ctx context.Context, name string, scripts []string, identity processIdentity) (bool, error) {
	logFile, err := os.OpenFile(filepath.Join(w.stateDir, name+".log"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0600)
	if err != nil {
		return false, err
	}
	defer logFile.Close()
	// Recheck after opening files so an old PID cannot receive a new session.
	before, err := w.read(w.procRoot, identity.PID, name)
	if err != nil || before != identity {
		return true, nil
	}
	childCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	cmd := exec.CommandContext(childCtx, filepath.Join(w.scriptsDir, "frida-inject"), injectorArgs(identity, w.scriptsDir, scripts)...)
	cmd.Stdout, cmd.Stderr = logFile, logFile
	cmd.Cancel = func() error { return cmd.Process.Signal(syscall.SIGTERM) }
	cmd.WaitDelay = 2 * time.Second
	if err := cmd.Start(); err != nil {
		return false, err
	}
	w.status.set(name, targetStatus{PID: identity.PID, StartTicks: identity.StartTicks, State: "injecting", InjectorPID: cmd.Process.Pid})
	finished := make(chan error, 1)
	go func() { finished <- cmd.Wait() }()
	ticker := time.NewTicker(w.poll)
	defer ticker.Stop()
	for {
		select {
		case err := <-finished:
			return false, err
		case <-ctx.Done():
			cancel()
			<-finished
			return true, ctx.Err()
		case <-ticker.C:
			current, err := w.read(w.procRoot, identity.PID, name)
			if err != nil || current != identity {
				cancel()
				<-finished
				w.status.set(name, targetStatus{State: "waiting"})
				return true, nil
			}
		}
	}
}
func run(ctx context.Context, scriptsDir, stateDir, statusPath, procRoot string) error {
	cfg, err := readConfig(scriptsDir)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(stateDir, 0700); err != nil {
		return err
	}
	lock, err := os.OpenFile(filepath.Join(stateDir, "loader.lock"), os.O_CREATE|os.O_RDWR, 0600)
	if err != nil {
		return err
	}
	defer lock.Close()
	if err := syscall.Flock(int(lock.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
		return fmt.Errorf("PI loader already running or lock unavailable: %w", err)
	}
	defer syscall.Flock(int(lock.Fd()), syscall.LOCK_UN)
	selfName, err := os.ReadFile(filepath.Join(procRoot, strconv.Itoa(os.Getpid()), "cmdline"))
	if err != nil {
		return err
	}
	selfArgv0, _, _ := strings.Cut(string(selfName), "\x00")
	self, err := readIdentity(procRoot, os.Getpid(), selfArgv0)
	if err != nil {
		return err
	}
	boot, err := os.ReadFile(filepath.Join(procRoot, "sys/kernel/random/boot_id"))
	if err != nil {
		return err
	}
	if strings.TrimSpace(string(boot)) == "" {
		return errors.New("empty boot identity")
	}
	status := &statusStore{value: loaderStatus{Schema: 1, Infrastructure: "pi", LoaderPID: self.PID, LoaderStartTicks: self.StartTicks, BootID: strings.TrimSpace(string(boot)), State: "running", Targets: map[string]targetStatus{}}}
	for name := range cfg {
		status.set(name, targetStatus{State: "waiting"})
	}
	uptime, err := readUptime(procRoot)
	if err != nil {
		return err
	}
	if err := status.publish(statusPath, uptime); err != nil {
		return err
	}
	defer os.Remove(statusPath)
	workerCtx, cancel := context.WithCancel(ctx)
	var workers sync.WaitGroup
	defer func() { cancel(); workers.Wait() }()
	w := worker{scriptsDir: scriptsDir, stateDir: stateDir, procRoot: procRoot, poll: time.Second, retry: 10 * time.Second, status: status, find: findProcess, read: readIdentity}
	for name, scripts := range cfg {
		workers.Add(1)
		go func(name string, scripts []string) { defer workers.Done(); w.run(workerCtx, name, scripts) }(name, scripts)
	}
	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return nil
		case <-ticker.C:
			uptime, err := readUptime(procRoot)
			if err != nil {
				return err
			}
			if err := status.publish(statusPath, uptime); err != nil {
				return err
			}
		}
	}
}

// health checks the live watchdog, never the effects of an agent inside an OEM process.
func health(statusPath, procRoot string) error {
	data, err := os.ReadFile(statusPath)
	if err != nil {
		return err
	}
	var status loaderStatus
	if err := json.Unmarshal(data, &status); err != nil {
		return err
	}
	if status.Schema != 1 || status.Infrastructure != "pi" || status.State != "running" || status.LoaderPID <= 0 {
		return errors.New("invalid PI watchdog heartbeat")
	}
	boot, err := os.ReadFile(filepath.Join(procRoot, "sys/kernel/random/boot_id"))
	if err != nil {
		return err
	}
	if status.BootID != strings.TrimSpace(string(boot)) {
		return errors.New("heartbeat belongs to a previous boot")
	}
	uptime, err := readUptime(procRoot)
	if err != nil {
		return err
	}
	if status.UpdatedUptimeSeconds > uptime || uptime-status.UpdatedUptimeSeconds > 15 {
		return errors.New("stale PI watchdog heartbeat")
	}
	identity, err := readIdentity(procRoot, status.LoaderPID, "loaderFrida")
	if err != nil {
		return err
	}
	if identity.StartTicks != status.LoaderStartTicks {
		return errors.New("PI watchdog PID was reused")
	}
	return nil
}

func main() {
	scripts := flag.String("scripts-dir", "/data/local/bin", "Directory containing injects.json, agents and frida-inject")
	state := flag.String("state-dir", "/data/local/tmp/voyahtune-pi", "Private runtime state and injector logs")
	status := flag.String("status", "/data/local/tmp/voyahtune-pi-loader-status.json", "Watchdog heartbeat (does not confirm OEM hooks)")
	check := flag.Bool("check", false, "Validate config and files without starting injection")
	healthCheck := flag.Bool("health", false, "Check live PI watchdog heartbeat; does not confirm OEM hooks")
	flag.Parse()
	if *healthCheck {
		if err := health(*status, "/proc"); err != nil {
			log.Fatal(err)
		}
		return
	}
	if *check {
		if _, err := readConfig(*scripts); err != nil {
			log.Fatal(err)
		}
		return
	}
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	if err := run(ctx, *scripts, *state, *status, "/proc"); err != nil {
		log.Fatal(err)
	}
}
