package main

import (
	"context"
	"testing"
)

func TestOemMediaSelectionDoesNotRequireVehicleIdentity(t *testing.T) {
	for _, identifier := range []string{"cluster-media", "launcher-media", "island-media"} {
		t.Run(identifier, func(t *testing.T) {
			var definition nativeAgentConfig
			for _, agent := range compiledAgents() {
				if agent.ID == identifier {
					definition = agent
					break
				}
			}
			if definition.ID == "" {
				t.Fatal("Missing compiled media agent")
			}
			manager := nativeSupervisor{wanted: map[string]bool{identifier: true}}
			for _, enabled := range []bool{true, false} {
				manager.wanted[identifier] = enabled
				group, _, err := manager.selection(
					context.Background(), []nativeAgentConfig{definition}, processIdentity{})
				if err != nil {
					t.Fatal(err)
				}
				if enabled {
					if len(group.Modules) != 1 || group.Modules[0] != "oem-media" {
						t.Fatalf("Media module was not selected: %+v", group)
					}
				} else if len(group.Modules) != 0 {
					t.Fatalf("Disabled media module was selected: %+v", group)
				}
			}
		})
	}
}
