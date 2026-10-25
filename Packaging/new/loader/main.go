package main

import (
	"context"
	"flag"
	"log"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
)

func main() {
	runtimeDirectory := flag.String("runtime-dir", "/data/local/bin/voyahtune-new", "Agent manifest, scripts and injector")
	stateDirectory := flag.String("state-dir", "/data/local/tmp/voyahtune-new", "Private supervisor records")
	healthDirectory := flag.String("health-dir", "/data/local/open_voyah/new", "OEM-owned agent health files")
	check := flag.Bool("check", false, "Validate runtime without starting injection")
	health := flag.Bool("health", false, "Check the supervisor heartbeat; does not prove OEM hook effects")
	checkAgentHealth := flag.Bool("check-hooks", false, "Check supervisor and configured hooks in available target processes")
	flag.Parse()
	if *health && !*checkAgentHealth {
		if err := checkHealth(filepath.Join(*stateDirectory, "status.json"), "/proc"); err != nil {
			log.Fatal(err)
		}
		return
	}
	config, err := loadConfig(*runtimeDirectory)
	if err != nil {
		log.Fatal(err)
	}
	if *checkAgentHealth {
		if err := checkHooks(filepath.Join(*stateDirectory, "status.json"), "/proc", config); err != nil {
			log.Fatal(err)
		}
		return
	}
	if *check {
		return
	}
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	manager := supervisor{runtimeDirectory: *runtimeDirectory, stateDirectory: *stateDirectory, healthDirectory: *healthDirectory, procRoot: "/proc", status: &statusStore{agents: map[string]agentStatus{}}, priorityReady: make(chan struct{}), dynamicDesired: map[string]bool{}}
	if err := manager.run(ctx, config); err != nil {
		log.Fatal(err)
	}
}
