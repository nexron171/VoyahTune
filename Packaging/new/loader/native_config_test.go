package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func nativeRuntimeFixture(t *testing.T) string {
	t.Helper()
	directory := t.TempDir()
	manifest := nativeLoaderConfig{
		Schema: 2,
		Agents: []nativeAgentConfig{{
			ID: "acc", Target: "com.qinggan.canbus.service", Modules: []string{"acc"},
			MaxAttempts: 4, TimeoutSeconds: 35, CrashLimit: 3,
		}},
	}
	data, _ := json.Marshal(manifest)
	testFile(t, filepath.Join(directory, "agents.json"), string(data), 0644)
	files := map[string]string{}
	for name, body := range map[string]string{"agents.dex": "dex fixture", "libvoyahtune-agent.so": "native fixture", "voyahtune-inject": "helper fixture", "voyahtune-inject-arm": "ARM32 helper fixture", "libvoyahtune-agent-arm.so": "ARM32 native fixture"} {
		mode := os.FileMode(0644)
		if name == "voyahtune-inject" || name == "voyahtune-inject-arm" {
			mode = 0755
		}
		testFile(t, filepath.Join(directory, name), body, mode)
		digest := sha256.Sum256([]byte(body))
		files[name] = hex.EncodeToString(digest[:])
	}
	code := map[string]any{"schema": 2, "runtime": "unified", "androidApi": 30, "nativeId": "native-test", "runtimeId": "java-test", "sha256": files}
	data, _ = json.Marshal(code)
	testFile(t, filepath.Join(directory, "runtime-manifest.json"), string(data), 0644)
	return directory
}

func TestNativeManifestLoadsTypedModuleConfiguration(t *testing.T) {
	directory := nativeRuntimeFixture(t)
	config, err := loadNativeConfig(directory)
	if err != nil {
		t.Fatal(err)
	}
	if len(config.Agents) != 1 || config.Agents[0].Modules[0] != "acc" || config.Code.RuntimeID != "java-test" {
		t.Fatal(config)
	}
	if err := os.WriteFile(filepath.Join(directory, "agents.dex"), []byte("changed bytes"), 0644); err != nil {
		t.Fatal(err)
	}
	if _, err := loadNativeConfig(directory); err == nil {
		t.Fatal("mismatched DEX allowed into bootstrap")
	}
}

func TestNativeManifestRejectsLegacyAndUnsafeInputs(t *testing.T) {
	for _, change := range []func(map[string]any){
		func(value map[string]any) { value["schema"] = 1 },
		func(value map[string]any) { value["prioritySeconds"] = 45 },
		func(value map[string]any) { value["agents"].([]any)[0].(map[string]any)["priority"] = true },
		func(value map[string]any) { value["agents"].([]any)[0].(map[string]any)["earlyStart"] = true },
		func(value map[string]any) { value["agents"].([]any)[0].(map[string]any)["script"] = "acc.js" },
		func(value map[string]any) {
			value["agents"].([]any)[0].(map[string]any)["modules"] = []string{"unknown"}
		},
		func(value map[string]any) { value["agents"].([]any)[0].(map[string]any)["target"] = "../escape" },
		func(value map[string]any) { value["agents"].([]any)[0].(map[string]any)["timeoutSeconds"] = 0 },
	} {
		directory := nativeRuntimeFixture(t)
		var manifest map[string]any
		data, err := os.ReadFile(filepath.Join(directory, "agents.json"))
		if err != nil {
			t.Fatal(err)
		}
		if err := json.Unmarshal(data, &manifest); err != nil {
			t.Fatal(err)
		}
		change(manifest)
		data, _ = json.Marshal(manifest)
		if err := os.WriteFile(filepath.Join(directory, "agents.json"), data, 0644); err != nil {
			t.Fatal(err)
		}
		if _, err := loadNativeConfig(directory); err == nil {
			t.Fatal("unsafe manifest accepted", manifest)
		}
	}
}

func TestNativeManifestSupportsIndependentAgents(t *testing.T) {
	directory := nativeRuntimeFixture(t)
	manifest := `{"schema":2,"agents":[{"id":"launcher","target":"com.qinggan.app.launcher","modules":["launcher"],"maxAttempts":1,"timeoutSeconds":35}]}`
	testFile(t, filepath.Join(directory, "agents.json"), manifest, 0644)
	config, err := loadNativeConfig(directory)
	if err != nil {
		t.Fatal("independent agent configuration requires ACC", err)
	}
	if len(config.Agents) != 1 || config.Agents[0].ID != "launcher" {
		t.Fatal(config.Agents)
	}
}

func TestNativeManifestLoadsPackagedAgents(t *testing.T) {
	directory := nativeRuntimeFixture(t)
	manifest, err := os.ReadFile(filepath.Join("..", "config", "agents.json"))
	if err != nil {
		t.Fatal(err)
	}
	testFile(t, filepath.Join(directory, "agents.json"), string(manifest), 0644)
	if _, err := loadNativeConfig(directory); err != nil {
		t.Fatal("packaged agent configuration cannot be loaded", err)
	}
}
