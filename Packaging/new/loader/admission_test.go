package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestAdmissionWaitsForBootChangeAfterInstallation(t *testing.T) {
	directory := t.TempDir()
	block := filepath.Join(directory, "update.block")
	marker := filepath.Join(directory, "migration.boot")
	if err := admissionBlock(block, marker, "boot-1"); err != nil {
		t.Fatal(err)
	}
	testFile(t, marker, "boot-1\n", 0600)
	if err := admissionBlock(block, marker, "boot-1"); err == nil {
		t.Fatal("Same-boot migrated runtime admitted")
	}
	if err := admissionBlock(block, marker, "boot-2"); err != nil {
		t.Fatal(err)
	}
	testFile(t, block, "", 0600)
	if err := admissionBlock(block, marker, "boot-2"); err == nil {
		t.Fatal("Install block ignored after reboot")
	}
}

func TestAdmissionRejectsMalformedAndAliasedMigrationMarker(t *testing.T) {
	for _, value := range []string{"", "boot-1\nboot-2", "boot\x00other"} {
		directory := t.TempDir()
		marker := filepath.Join(directory, "migration.boot")
		testFile(t, marker, value, 0600)
		if err := admissionBlock(filepath.Join(directory, "missing"), marker, "boot-2"); err == nil {
			t.Fatal("Malformed marker accepted", value)
		}
	}
	directory := t.TempDir()
	source := filepath.Join(directory, "source")
	marker := filepath.Join(directory, "migration.boot")
	testFile(t, source, "boot-1", 0600)
	if err := os.Symlink(source, marker); err != nil {
		t.Fatal(err)
	}
	if err := admissionBlock(filepath.Join(directory, "missing"), marker, "boot-2"); err == nil {
		t.Fatal("Marker symlink accepted")
	}
}
