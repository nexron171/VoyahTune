package main

import (
	"context"
	"testing"
)

func TestPassengerHeadphonesSelection(t *testing.T) {
	var definition nativeAgentConfig
	for _, agent := range compiledAgents() {
		if agent.ID == "passenger-headphones" {
			definition = agent
			break
		}
	}
	if definition.Target != "com.qinggan.audiopolicy.service" {
		t.Fatalf("Headphone agent targets the wrong process: %+v", definition)
	}
	manager := nativeSupervisor{wanted: map[string]bool{definition.ID: true}}
	for _, enabled := range []bool{true, false} {
		manager.wanted[definition.ID] = enabled
		group, _, err := manager.selection(context.Background(), []nativeAgentConfig{definition}, processIdentity{})
		if err != nil {
			t.Fatal(err)
		}
		if enabled {
			if len(group.Modules) != 1 || group.Modules[0] != "passenger-headphones" {
				t.Fatalf("Headphone module not selected: %+v", group)
			}
		} else if len(group.Modules) != 0 {
			t.Fatalf("Disabled headphone module selected: %+v", group)
		}
	}
}
