package main

import (
	"context"
	"testing"
)

func TestOemMediaSelectionLeavesOtherVehiclesUntouched(t *testing.T) {
	for _, test := range []struct {
		name, product, thirdParty string
		enabled                   bool
	}{
		{"sport-plus", "f6811", "", true},
		{"sport-edition", "f6805h", "", true},
		{"third-party-product", "generic", "f6811", true},
		{"parallel-import", "f6802h", "", false},
		{"unknown", "", "", false},
		{"similar-name", "f6811-other", "", false},
	} {
		t.Run(test.name, func(t *testing.T) {
			manager := nativeSupervisor{
				wanted: map[string]bool{"cluster-media": true},
				readProduct: func(_ context.Context, name string) string {
					if name == "ro.build.product" {
						return test.product
					}
					return test.thirdParty
				},
			}
			agent := nativeAgentConfig{ID: "cluster-media", Target: "com.qinggan.instrumentcard",
				Condition: "oem-media-od", Modules: []string{"oem-media"}}
			group, _, err := manager.selection(context.Background(), []nativeAgentConfig{agent}, processIdentity{})
			if err != nil || (len(group.Modules) == 1) != test.enabled {
				t.Fatalf("modules=%v enabled=%v error=%v", group.Modules, test.enabled, err)
			}
			manager.wanted[agent.ID] = false
			group, _, err = manager.selection(context.Background(), []nativeAgentConfig{agent}, processIdentity{})
			if err != nil || len(group.Modules) != 0 {
				t.Fatalf("disabled module was selected: %+v, %v", group, err)
			}
		})
	}
}
