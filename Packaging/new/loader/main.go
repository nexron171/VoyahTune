package main

import (
	"context"
	"encoding/json"
	"flag"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"

	"voyahtune/unified-loader/lifecycle"
)

func main() {
	runtimeDirectory := flag.String("runtime-dir", "/data/local/bin/voyahtune-new", "Native runtime components and module manifest")
	stateDirectory := flag.String("state-dir", "/data/local/tmp/voyahtune-new", "Private resident journals and control endpoint")
	check := flag.Bool("check", false, "Validate native components without injection")
	health := flag.Bool("health", false, "Check the supervisor heartbeat")
	hooks := flag.Bool("check-hooks", false, "Check current acknowledgements of required hooks and print their identities")
	hookAcks := flag.Bool("check-hook-acks", false, "Verify live acknowledgements from active Java modules")
	quiesce := flag.Bool("quiesce", false, "Close admission and wait for helpers and target workers to drain")
	controlStatus := flag.Bool("control-status", false, "Read authenticated runtime admission state")
	timeout := flag.Duration("control-timeout", 60*time.Second, "Control wait deadline; cancellation does not reopen admission")
	flag.Parse()
	if *quiesce || *controlStatus {
		ctx, cancel := context.WithTimeout(context.Background(), *timeout)
		defer cancel()
		var response lifecycle.ControlResponse
		var err error
		if *quiesce {
			response, err = quiesceNativeRuntime(ctx, *runtimeDirectory, *stateDirectory, "/proc")
		} else {
			response, err = nativeControl(ctx, *stateDirectory, "/proc", "status")
		}
		if err != nil {
			log.Fatal(err)
		}
		if err := writeControlResult(response); err != nil {
			log.Fatal(err)
		}
		return
	}
	if *health && !*hooks && !*hookAcks {
		if err := checkHealth(currentStatusPath(*stateDirectory), "/proc"); err != nil {
			log.Fatal(err)
		}
		return
	}
	config := nativeLoaderConfig{Schema: 2, Agents: compiledAgents(), Code: embeddedNativeCode}
	var configurationError error
	if !*hooks || *check || *hookAcks {
		config, configurationError = loadNativeConfig(*runtimeDirectory)
	}
	if *check || *hooks || *hookAcks {
		if configurationError != nil {
			log.Fatal(configurationError)
		}
		if *hooks {
			ready, err := checkNativeHooks(currentStatusPath(*stateDirectory), "/proc", config)
			if err != nil {
				log.Fatal(err)
			}
			if err := json.NewEncoder(os.Stdout).Encode(ready); err != nil {
				log.Fatal(err)
			}
		}
		if *hookAcks {
			if err := checkNativeHookAcks(currentStatusPath(*stateDirectory), "/proc", config, inspectNativeStatus); err != nil {
				log.Fatal(err)
			}
		}
		return
	}
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	manager := nativeSupervisor{
		runtimeDirectory: *runtimeDirectory,
		stateDirectory:   *stateDirectory,
		procRoot:         "/proc",
		config:           config,
		status:           &statusStore{agents: map[string]agentStatus{}},
	}
	if err := manager.run(ctx, configurationError); err != nil {
		log.Fatal(err)
	}
}
