package main

import (
	"context"
	"sync"
	"time"

	"voyahtune/unified-loader/lifecycle"
)

type agentWorkers struct {
	manager *nativeSupervisor
	context context.Context
	group   *sync.WaitGroup
	targets map[string]bool
}

func (workers *agentWorkers) watchDynamic() {
	for workers.context.Err() == nil {
		if workers.manager.engine.Snapshot().Phase == lifecycle.Running && workers.manager.admissionBlock() == nil {
			workers.refreshDynamic()
		}
		if !wait(workers.context, 5*time.Second) {
			return
		}
	}
}

func (workers *agentWorkers) refreshDynamic() {
	manager := workers.manager
	dynamic := nativeDynamicAgents(
		manager.readSetting(workers.context, "voyahtune_fullscreen_apps"),
	)
	audio := navigationAudioAgents(manager.readSetting(workers.context, "voyahtune_navigation_audio_apps"))
	dynamic = append(dynamic, navigationAudioProcesses(manager.procRoot, audio)...)
	wanted := make(map[string]bool, len(dynamic))
	for _, agent := range dynamic {
		wanted[agent.ID] = true
	}
	manager.mutex.Lock()
	for id, agent := range manager.agents {
		if agent.Dynamic {
			manager.wanted[id] = wanted[id]
		}
	}
	var added []nativeAgentConfig
	for _, agent := range dynamic {
		if _, known := manager.agents[agent.ID]; !known && len(manager.agents)+len(added) < 128 {
			added = append(added, agent)
		}
	}
	manager.mutex.Unlock()
	workers.start(added)
}

func (workers *agentWorkers) start(agents []nativeAgentConfig) {
	if workers.context.Err() != nil {
		return
	}
	manager := workers.manager
	manager.mutex.Lock()
	for _, agent := range agents {
		manager.agents[agent.ID] = agent
		manager.wanted[agent.ID] = true
	}
	manager.mutex.Unlock()
	for _, agent := range agents {
		manager.report(agent, processIdentity{}, "waiting", "", nil, nil)
	}
	for _, agent := range agents {
		if workers.targets[agent.Target] {
			continue
		}
		workers.targets[agent.Target] = true
		workers.group.Add(1)
		go func() {
			defer workers.group.Done()
			for workers.context.Err() == nil {
				manager.cycle(workers.context, agent.Target)
				if !wait(workers.context, time.Second) {
					return
				}
			}
		}()
	}
}
