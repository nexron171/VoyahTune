package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	scriptsDir  = "/data/local/bin"
	tmpDir      = "/data/local/tmp"
	injector    = "frida-inject"
	injectsFile = scriptsDir + "/injects.json"
	maxRepl     = 10
	RetryPause  = 10 * time.Second
)

var (
	MainLog       *os.File
	Debug         bool
	Ctx           map[string]context.CancelFunc
	contextsMutex sync.Mutex
)

// An application may be launched long after boot; only failed attaches consume retries.
type injectionBudget struct {
	pid       int
	remaining int
}

func (b *injectionBudget) observe(pid int) bool {
	if pid == 0 {
		b.pid = 0
		return false
	}
	if b.pid != pid {
		b.pid = pid
		b.remaining = maxRepl
	}
	return b.remaining > 0
}

func checkOrSetPid(pidPath string, pid int) bool {
	data, err := os.ReadFile(pidPath)
	if err == nil {
		recordedPID, parseError := strconv.Atoi(strings.TrimSpace(string(data)))
		if parseError == nil && recordedPID == pid {
			return true
		}
	}
	if err := os.WriteFile(pidPath, []byte(strconv.Itoa(pid)), 0644); err != nil {
		logf(MainLog, "Cannot record process %d: %v", pid, err)
	}
	return false
}

func logf(f *os.File, format string, args ...any) {
	fmt.Fprintf(f, "[%s] %s\n", time.Now().Format("2006-01-02 15:04:05"), fmt.Sprintf(format, args...))
}

// Exact argv[0] matching excludes secondary Android processes with a package-name prefix.
func GetPIDsByName(name string) []int {
	var pids []int

	entries, err := os.ReadDir("/proc")
	if err != nil {
		return []int{0}
	}

	for _, e := range entries {
		pid, err := strconv.Atoi(e.Name())
		if err != nil {
			continue
		}

		data, err := os.ReadFile(filepath.Join("/proc", e.Name(), "cmdline"))
		if err != nil || len(data) == 0 {
			continue
		}

		argv0, _, _ := strings.Cut(string(data), "\x00")
		if argv0 == name || filepath.Base(argv0) == name {
			pids = append(pids, pid)
		}
	}
	if len(pids) == 0 {
		return []int{0}
	}

	return pids
}

func setPidsZero(injects *map[string][]string) {
	for pkg := range *injects {
		logf(MainLog, "setPidsZero %s\n", filepath.Join(tmpDir, pkg+".pid Zero"))
		f, _ := os.Create(filepath.Join(tmpDir, pkg+".pid"))
		f.WriteString("0")
		f.Close()
	}
}

func setPidZero(pkg string) {
	logf(MainLog, "setPidZero %s\n", filepath.Join(tmpDir, pkg+".pid Zero"))
	f, _ := os.Create(filepath.Join(tmpDir, pkg+".pid"))
	f.WriteString("0")
	f.Close()
}

func runInjectCancel(pkg, injector string, pid int, scripts []string) error {
	ctx, cancel := context.WithCancel(context.Background())

	defer cancel()
	cmds := []string{"-R", "v8", "-p", strconv.Itoa(pid)}
	for _, s := range scripts {
		cmds = append(cmds, "-s")
		cmds = append(cmds, filepath.Join(scriptsDir, s))
	}

	logf(MainLog, "runInjectCancel %v\n", cmds)

	cmd := exec.CommandContext(ctx, filepath.Join(scriptsDir, injector), cmds...)
	contextsMutex.Lock()
	Ctx[pkg] = cancel
	contextsMutex.Unlock()
	defer func() {
		contextsMutex.Lock()
		delete(Ctx, pkg)
		contextsMutex.Unlock()
	}()

	err := cmd.Run()
	if err != nil && ctx.Err() == context.Canceled {
		logf(MainLog, "Процесс корректно завершён по отмене контекста %s\n", pkg)
	} else if err != nil {
		logf(MainLog, "Другая ошибка: %v %s\n", err, pkg)
	}

	logf(MainLog, "Процесс успешно завершился %v %s\n", scripts, pkg)
	return err
}

func checkZombi() {
	for {
		contextsMutex.Lock()
		contexts := make(map[string]context.CancelFunc, len(Ctx))
		for pkg, cancel := range Ctx {
			contexts[pkg] = cancel
		}
		contextsMutex.Unlock()
		for pkg, cancel := range contexts {
			pid, _ := os.ReadFile(filepath.Join(tmpDir, pkg) + ".pid")
			p, err := strconv.Atoi(string(pid))

			if err != nil || p == 0 {
				continue
			}

			if sysPid := GetPIDsByName(pkg)[0]; p != sysPid {
				cancel()
				logf(MainLog, "checkZombi p=%d pkg=%s sys pid=%d", p, pkg, sysPid)

			}
		}
		time.Sleep(RetryPause)
	}
}

func main() {
	Ctx = make(map[string]context.CancelFunc)

	Debug = *flag.Bool("d", false, "Режим дебага.")
	flag.Parse()

	MainLog, _ = os.OpenFile("/data/local/tmp/loaderFrida.log", os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
	defer MainLog.Close()

	myPids := GetPIDsByName("loaderFrida")

	logf(MainLog, "My pids %v", myPids)
	if len(myPids) > 1 {
		logf(os.Stderr, "%s\n", "Уже запущен...")
		os.Exit(1)
	}

	// load injects
	data, err := os.ReadFile(injectsFile)
	if err != nil {
		fmt.Fprintf(os.Stderr, "read injects: %v\n", err)
		os.Exit(1)
	}
	var injects map[string][]string
	if err := json.Unmarshal(data, &injects); err != nil {
		fmt.Fprintf(os.Stderr, "parse injects: %v\n", err)
		os.Exit(1)
	}
	var wg sync.WaitGroup

	setPidsZero(&injects)

	for pkg, scripts := range injects {

		wg.Add(1)

		go func(pkg string, scripts []string) {
			logPath := filepath.Join(tmpDir, pkg+".log")
			log, _ := os.OpenFile(logPath, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
			defer log.Close()

			budget := injectionBudget{}
			defer wg.Done()

			for {
				time.Sleep(RetryPause)

				pid := GetPIDsByName(pkg)[0]
				if !budget.observe(pid) {
					continue
				}
				if checkOrSetPid(filepath.Join(tmpDir, pkg+".pid"), pid) {
					if Debug {
						logf(MainLog, "Hooks already attached: package=%s pid=%d", pkg, pid)
					}
					continue
				}
				logf(MainLog, "Attach %s %v: pid=%d package=%s remaining=%d",
					injector, scripts, pid, pkg, budget.remaining)
				err := runInjectCancel(pkg, injector, pid, scripts)
				// Without -e, every injector exit unloads the script and requires a new attach.
				setPidZero(pkg)
				if err != nil {
					log.WriteString(err.Error())
					budget.remaining--
				}
			}
		}(pkg, scripts)
	}
	go checkZombi()
	wg.Wait()
}
