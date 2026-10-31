package main

import (
	"crypto/sha256"
	"encoding/hex"
	"os"
	"path/filepath"
	"testing"
)

func runtimeFixture(t *testing.T) (string, nativeCode) {
	t.Helper()
	directory := t.TempDir()
	code := nativeCode{Schema: 2, Runtime: "unified", AndroidAPI: 30,
		NativeID: "native-test", RuntimeID: "java-test", SHA256: map[string]string{}}
	for name, body := range map[string]string{
		"agents.dex": "dex fixture", "libvoyahtune-agent.so": "native fixture",
		"voyahtune-inject": "helper fixture", "voyahtune-inject-arm": "ARM32 helper fixture",
		"libvoyahtune-agent-arm.so": "ARM32 native fixture",
	} {
		mode := os.FileMode(0644)
		if name == nativeHelperName || name == nativeARM32HelperName {
			mode = 0755
		}
		testFile(t, filepath.Join(directory, name), body, mode)
		digest := sha256.Sum256([]byte(body))
		code.SHA256[name] = hex.EncodeToString(digest[:])
	}
	return directory, code
}

func TestCompiledRuntimeStartsWithoutExternalManifests(t *testing.T) {
	directory, code := runtimeFixture(t)
	config, err := loadNativeConfigWithCode(directory, code)
	if err != nil {
		t.Fatal(err)
	}
	targets := map[string]nativeAgentConfig{}
	for _, agent := range config.Agents {
		targets[agent.ID] = agent
	}
	if config.Code.RuntimeID != "java-test" || len(targets) != 21 ||
		targets["launcher"].Target != "com.qinggan.app.launcher" ||
		targets["acc"].MaxAttempts != 4 || targets["cluster"].TimeoutSeconds != 45 ||
		targets["keyboard-ru"].Condition != "keyboard-ru" || len(targets["rds"].Modules) != 2 ||
		targets["cluster-media"].Condition != "oem-media-od" ||
		targets["cluster-media"].Target != "com.qinggan.instrumentcard" {
		t.Fatal(config)
	}
	for _, name := range []string{"agents.json", "runtime-manifest.json"} {
		if _, err := os.Stat(filepath.Join(directory, name)); !os.IsNotExist(err) {
			t.Fatal(name, err)
		}
	}
}

func TestCompiledRuntimeRejectsDamagedOrUnsafeComponents(t *testing.T) {
	for _, change := range []string{"content", "missing", "permissions", "symlink", "hardlink"} {
		t.Run(change, func(t *testing.T) {
			directory, code := runtimeFixture(t)
			path := filepath.Join(directory, "agents.dex")
			var err error
			switch change {
			case "content":
				err = os.WriteFile(path, []byte("changed"), 0644)
			case "missing":
				err = os.Remove(path)
			case "permissions":
				err = os.Chmod(path, 0666)
			case "symlink":
				err = os.Rename(path, path+".original")
				if err == nil {
					err = os.Symlink(path+".original", path)
				}
			case "hardlink":
				err = os.Link(path, path+".link")
			}
			if err != nil {
				t.Fatal(err)
			}
			if _, err := loadNativeConfigWithCode(directory, code); err == nil {
				t.Fatal("unsafe component accepted")
			}
		})
	}
}

func TestCompiledRegistryReturnsIndependentCopies(t *testing.T) {
	directory, code := runtimeFixture(t)
	first, err := loadNativeConfigWithCode(directory, code)
	if err != nil {
		t.Fatal(err)
	}
	first.Agents[0].Modules[0] = "changed"
	second, err := loadNativeConfigWithCode(directory, code)
	if err != nil || second.Agents[0].Modules[0] != "acc" {
		t.Fatal(second, err)
	}
}

func TestEmbeddedRuntimeStartsFromBuildOverlay(t *testing.T) {
	directory := os.Getenv("VOYAHTUNE_TEST_RUNTIME")
	if directory == "" {
		t.Skip("requires generated build overlay")
	}
	config, err := loadNativeConfig(directory)
	if err != nil {
		t.Fatal(err)
	}
	if len(config.Agents) != 16 || config.Code.SHA256[agentDEXName] == "" {
		t.Fatal(config)
	}
}
