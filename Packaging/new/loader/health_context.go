package main

import (
	"context"
	"fmt"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"time"
)

func applyHealthFileContext(target, path string) error {
	if runtime.GOOS != "android" {
		return nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	// Copy the target application's label, including any per-user MLS categories.
	for _, root := range []string{"/data/user_de/0", "/data/user/0"} {
		fields := strings.Fields(androidCommand(ctx, "ls", "-Zd", filepath.Join(root, target)))
		if len(fields) == 0 || !strings.HasPrefix(fields[0], "u:object_r:") {
			continue
		}
		output, err := exec.CommandContext(ctx, "/system/bin/chcon", fields[0], path).CombinedOutput()
		if err != nil {
			return fmt.Errorf("health file context: %w: %s", err, strings.TrimSpace(string(output)))
		}
		return nil
	}
	if androidCommand(ctx, "getenforce") == "Permissive" {
		return nil
	}
	return fmt.Errorf("health file context: application data directory unavailable for %s", target)
}
