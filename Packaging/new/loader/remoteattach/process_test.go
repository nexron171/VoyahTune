package remoteattach

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestTargetIdentityUsesKernelBootUIDAndStartTicks(t *testing.T) {
	root := t.TempDir()
	write := func(path, data string) {
		t.Helper()
		path = filepath.Join(root, path)
		if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, []byte(data), 0600); err != nil {
			t.Fatal(err)
		}
	}
	write("sys/kernel/random/boot_id", "kernel-boot\n")
	fields := append([]string{"S"}, strings.Fields(strings.Repeat("0 ", 18))...)
	fields = append(fields, "12345", "0")
	write("41/stat", "41 (name with ) parentheses) "+strings.Join(fields, " "))
	write("41/status", "Name:\towned\nUid:\t2000\t1000\t1000\t1000\n")
	target, err := ReadTarget(root, 41)
	if err != nil {
		t.Fatal(err)
	}
	if target.BootID != "kernel-boot" || target.PID != 41 || target.UID != 1000 || target.StartTicks != "12345" {
		t.Fatalf("wrong kernel identity: %+v", target)
	}
	if err := VerifyTarget(root, target); err != nil {
		t.Fatal(err)
	}
	write("41/status", "Name:\towned\nUid:\t2000\t1001\t1001\t1001\n")
	if err := VerifyTarget(root, target); err == nil || errors.Is(err, os.ErrNotExist) {
		t.Fatalf("credential change was mistaken for a dead process: %v", err)
	}
	write("41/status", "Name:\towned\nUid:\t2000\t1000\t1000\t1000\n")
	fields[19] = "12346"
	write("41/stat", "41 (reused pid) "+strings.Join(fields, " "))
	if err := VerifyTarget(root, target); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("pid reuse accepted: %v", err)
	}
	write("41/status", "Name:\tbroken\n")
	if _, err := ReadTarget(root, 41); err == nil {
		t.Fatal("missing UID was treated as root")
	}
}

func TestDetectTargetArchitectureUsesELFInsteadOfDeviceABI(t *testing.T) {
	for _, scenario := range []struct {
		class   byte
		machine uint16
		want    Architecture
	}{
		{1, 40, ARM32}, {2, 183, ARM64}, {2, 40, ""}, {1, 183, ""}, {2, 62, ""},
	} {
		root := t.TempDir()
		if err := os.MkdirAll(filepath.Join(root, "41"), 0700); err != nil {
			t.Fatal(err)
		}
		header := make([]byte, 20)
		copy(header, "\x7fELF")
		header[4], header[5] = scenario.class, 1
		header[18], header[19] = byte(scenario.machine), byte(scenario.machine>>8)
		if err := os.WriteFile(filepath.Join(root, "41/exe"), header, 0600); err != nil {
			t.Fatal(err)
		}
		actual, err := DetectArchitecture(root, 41)
		if scenario.want == "" {
			if err == nil {
				t.Fatal("unsupported ABI accepted")
			}
			continue
		}
		if err != nil || actual != scenario.want {
			t.Fatal(actual, err)
		}
	}
}
