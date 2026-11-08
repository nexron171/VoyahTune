package main

import (
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"
)

func TestProcessCatalogSharesSnapshotAndRefreshes(t *testing.T) {
	root := t.TempDir()
	fakeProcess(t, root, 42, "com.example.first", "100")
	now := time.Now()
	scans := 0
	catalog := processCatalog{procRoot: root, now: func() time.Time { return now }, scan: func(root string) (map[string][]int, error) { scans++; return scanProcesses(root) }}
	first, err := catalog.find("com.example.first")
	if err != nil || first != (processIdentity{42, "100"}) {
		t.Fatal(first, err)
	}
	fakeProcess(t, root, 43, "com.example.second", "200")
	second, err := catalog.find("com.example.second")
	if err != nil || second.PID != 0 || scans != 1 {
		t.Fatal(second, err, scans)
	}
	now = now.Add(time.Second)
	second, err = catalog.find("com.example.second")
	if err != nil || second.PID != 43 || scans != 2 {
		t.Fatal(second, err, scans)
	}
	if err := os.RemoveAll(filepath.Join(root, "42")); err != nil {
		t.Fatal(err)
	}
	first, err = catalog.find("com.example.first")
	if err != nil || first.PID != 0 {
		t.Fatal(first, err)
	}
	fakeProcess(t, root, 43, "com.example.second", "250")
	second, err = catalog.find("com.example.second")
	if err != nil || second.StartTicks != "250" {
		t.Fatal("same-name PID reuse retained stale start ticks", second, err)
	}
	fakeProcess(t, root, 43, "com.example.reused", "300")
	second, err = catalog.find("com.example.second")
	if err != nil || second.PID != 0 {
		t.Fatal("reused PID accepted", second, err)
	}
	now = now.Add(time.Second)
	reused, err := catalog.find("com.example.reused")
	if err != nil || reused != (processIdentity{43, "300"}) {
		t.Fatal(reused, err)
	}
}

func TestProcessCatalogSelectsLowestPIDAndExecutableBasename(t *testing.T) {
	root := t.TempDir()
	fakeProcess(t, root, 100, "com.example.app", "10")
	fakeProcess(t, root, 42, "com.example.app", "20")
	fakeProcess(t, root, 50, "/system/bin/service", "30")
	catalog := processCatalog{procRoot: root}
	for name, expected := range map[string]int{"com.example.app": 42, "service": 50} {
		actual, err := catalog.find(name)
		if err != nil || actual.PID != expected {
			t.Fatal(name, actual, err)
		}
	}
}

func TestProcessCatalogConcurrentReadersShareScan(t *testing.T) {
	root := t.TempDir()
	fakeProcess(t, root, 42, "com.example.app", "100")
	scans := 0
	catalog := processCatalog{procRoot: root, scan: func(root string) (map[string][]int, error) { scans++; return scanProcesses(root) }}
	var readers sync.WaitGroup
	for range 21 {
		readers.Add(1)
		go func() {
			defer readers.Done()
			identity, err := catalog.find("com.example.app")
			if err != nil || identity.PID != 42 {
				t.Error(identity, err)
			}
		}()
	}
	readers.Wait()
	if scans != 1 {
		t.Fatal("concurrent readers repeated scan", scans)
	}
}
