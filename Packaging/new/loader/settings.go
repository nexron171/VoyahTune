package main

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

func androidCommand(ctx context.Context, executable string, arguments ...string) string {
	commandContext, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	command := exec.CommandContext(commandContext, filepath.Join("/system/bin", executable), arguments...)
	command.WaitDelay = time.Second
	output, err := command.Output()
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(output))
}

func apolloEnabled(data, boot string) bool {
	if len(data) == 0 || len(data) > 192 || boot == "" {
		return false
	}
	values := map[string]string{}
	for _, line := range strings.Split(data, "\n") {
		if line == "" {
			continue
		}
		key, value, found := strings.Cut(line, "=")
		_, duplicate := values[key]
		if !found || strings.Contains(value, "=") || duplicate || (key != "v" && key != "boot" && key != "enabled") {
			return false
		}
		values[key] = value
	}
	return len(values) == 3 && values["v"] == "1" && values["boot"] == boot && values["enabled"] == "1"
}

type keyboardSelection struct {
	Boot     string          `json:"boot"`
	Identity processIdentity `json:"identity"`
	Mode     string          `json:"mode"`
}

func keyboardMode(path, boot string, identity processIdentity, readSetting func() string) (string, error) {
	var selection keyboardSelection
	err := readJSON(path, &selection)
	if err != nil && !os.IsNotExist(err) {
		return "", err
	}
	if selection.Boot == boot && selection.Identity == identity {
		return selection.Mode, nil
	}
	mode := readSetting()
	if mode != "en" && mode != "ru" {
		mode = "off"
	}
	selection = keyboardSelection{boot, identity, mode}
	if err := writeAtomic(path, selection); err != nil {
		return "", err
	}
	return mode, nil
}

func dynamicAgents(fullscreen, density string) []agentConfig {
	excluded := map[string]bool{}
	for _, name := range []string{"com.qinggan.keymanager.service", "com.qinggan.systemservice", "com.qinggan.app.launcher", "com.qinggan.canbus.service", "com.qinggan.app.vehiclesetting", "com.qinggan.app.vehicle", "com.qinggan.app.qgime", "com.qinggan.app.hiboard", "com.qinggan.cluster", "com.qinggan.bluetoothphone", "com.qinggan.systemui", "com.pateo.rdsapp", "ru.yandex.yandexnavi", "ru.dublgis.dgismobile"} {
		excluded[name] = true
	}
	packages := map[string]bool{}
	for _, name := range strings.Split(fullscreen, ",") {
		name = strings.TrimSpace(name)
		if safeName(name) && strings.Contains(name, ".") && !strings.HasSuffix(name, ".") && !excluded[name] {
			packages[name] = false
		}
	}
	for _, name := range strings.Split(density, ",") {
		name = strings.TrimSpace(name)
		if name == "ru.yandex.yandexmaps" || name == "com.yango.maps.android" {
			packages[name] = true
		}
	}
	var result []agentConfig
	for name, mapkit := range packages {
		markers := []string{"[app-client] hook ready v1"}
		if mapkit {
			markers = append(markers, "[mapkit-dpi] hook ready v1")
		}
		result = append(result, agentConfig{ID: "client." + name, Target: name, Script: "app-client.js", Ready: markers, Lifetime: "eternalized", MaxAttempts: 1, TimeoutSeconds: 30, CrashLimit: 2, Dynamic: true})
	}
	return result
}
