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
	MainLog *os.File
	Debug   bool
	Ctx     map[string]context.CancelFunc
)

func checkOrSetPid(pidPath string, pid int) bool {
	f, err := os.OpenFile(pidPath, os.O_RDONLY, 0644)
	defer f.Close()
	if err != nil {
		return false
	}

	buf := make([]byte, 10)
	n, _ := f.Read(buf)

	f_pid, _ := strconv.Atoi(string(buf[0:n]))
	//fmt.Println("*** pid f_pid n", pid, f_pid, n, err)

	if n == 0 || f_pid == 0 || int(f_pid) != pid {
		f, err = os.OpenFile(pidPath, os.O_TRUNC|os.O_WRONLY, 0644)
		f.WriteString(strconv.Itoa(pid))
		return false
	}

	return int(f_pid) == pid
}

func logf(f *os.File, format string, args ...any) {
	fmt.Fprintf(f, "[%s] %s\n", time.Now().Format("2006-01-02 15:04:05"), fmt.Sprintf(format, args...))
}

func GetPIDsByCmdlineSubstring(substring string) []int {
	var pids []int

	entries, err := os.ReadDir("/proc")
	if err != nil {
		return []int{0}
	}

	for _, e := range entries {
		name := e.Name()
		pid, err := strconv.Atoi(name)
		if err != nil {
			// Пропускаем всё, что не PID (например, "self", "thread-self")
			continue
		}

		cmdlinePath := filepath.Join("/proc", name, "cmdline")
		data, err := os.ReadFile(cmdlinePath)
		if err != nil {
			// Часто нет прав на чтение cmdline чужого процесса — просто пропускаем
			continue
		}

		// В cmdline аргументы разделены NUL (\x00)
		args := strings.Split(string(data), "\x00")
		full := strings.Join(args, " ")
		//fmt.Println("&&&&&", full, substring)
		if strings.Contains(full, substring) {
			pids = append(pids, pid)
		}
	}
	if len(pids) == 0 {
		return []int{0}
	}

	return pids
}

func setPidsZero(injects *map[string][]string) {
	for pkg, _ := range *injects {
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

	//out, err := exec.Command(filepath.Join(scriptsDir, injector), cmds...).Output()
	cmd := exec.CommandContext(ctx, filepath.Join(scriptsDir, injector), cmds...)
	Ctx[pkg] = cancel

	err := cmd.Run()
	if err != nil && ctx.Err() == context.Canceled {
		logf(MainLog, "Процесс корректно завершён по отмене контекста %s\n", pkg)
	} else if err != nil {
		logf(MainLog, "Другая ошибка: %v %s\n", err, pkg)
	}

	logf(MainLog, "Процесс успешно завершился %v %s\n", scripts, pkg)
	//fmt.Println(cmds)
	return err
}

func checkZombi() {
	for {
		for pkg, cancel := range Ctx {
			pid, _ := os.ReadFile(filepath.Join(tmpDir, pkg) + ".pid")
			p, err := strconv.Atoi(string(pid))

			if err != nil || p == 0 {
				continue
			}

			if p != GetPIDsByCmdlineSubstring(pkg)[0] {
				cancel()
				logf(MainLog, "checkZombi p=%d pkg=%s sys pid=%d", p, pkg, GetPIDsByCmdlineSubstring(pkg)[0])

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

	myPids := GetPIDsByCmdlineSubstring("loaderFrida")

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

			repl := maxRepl
			defer wg.Done()

			for {
				time.Sleep(RetryPause)

				pid := GetPIDsByCmdlineSubstring(pkg)[0]
				if pid == 0 {
					repl--
					continue
				}
				//pid = 14
				if checkOrSetPid(filepath.Join(tmpDir, pkg+".pid"), pid) && repl > 0 {
					if Debug {
						logf(MainLog, "Пропускаем пид есть в файле! %s\n", pkg)
					}
				} else {
					if repl > 0 {
						logf(MainLog, "Стартуем! %s  %v pid=%d(%s) repl=%d\n", injector, scripts, pid, pkg, repl)

						err := runInjectCancel(pkg, injector, pid, scripts)
						//log.Write(out)

						if err != nil {
							log.Write([]byte(err.Error()))
							setPidZero(pkg)
							//logf(MainLog, "Ошибка при запуске %s %s\n", string(out), err)
							repl--
						}

					} else {
						logf(MainLog, "Лимит попыток достигнут %s  pid=%d  repl=%d!\n", pkg, pid, repl)
					}

				}
			}
		}(pkg, scripts)
	}
	go checkZombi()
	wg.Wait()
}
