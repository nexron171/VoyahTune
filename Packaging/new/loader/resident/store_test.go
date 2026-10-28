package resident

import (
	"os"
	"path/filepath"
	"testing"
)

func TestFileStoreRetainsPendingPlanAcrossManagerRestart(t *testing.T) {
	path := filepath.Join(t.TempDir(), "resident")
	store, err := NewFileStore(path)
	if err != nil {
		t.Fatal(err)
	}
	record := Record{Schema: 1, Target: target(), Plan: plan("launcher"), Phase: "pending"}
	record.Plan.Revision = 7
	if err := store.Save(record); err != nil {
		t.Fatal(err)
	}
	reopened, err := NewFileStore(path)
	if err != nil {
		t.Fatal(err)
	}
	records, err := reopened.Load()
	if err != nil || len(records) != 1 || records[0].Plan.Revision != 7 || records[0].Phase != "pending" {
		t.Fatal(records, err)
	}
	record.Plan.Revision = 8
	record.Phase = "active"
	if err := reopened.Save(record); err != nil {
		t.Fatal(err)
	}
	records, err = store.Load()
	if err != nil || len(records) != 1 || records[0].Plan.Revision != 8 {
		t.Fatal(records, err)
	}
	if err := store.Remove(record.Target); err != nil {
		t.Fatal(err)
	}
	if records, err := reopened.Load(); err != nil || len(records) != 0 {
		t.Fatal(records, err)
	}
}

func TestFileStoreRejectsAliasesAndUntrustedDirectory(t *testing.T) {
	root := t.TempDir()
	outside := filepath.Join(root, "outside")
	if err := os.WriteFile(outside, []byte("unchanged"), 0600); err != nil {
		t.Fatal(err)
	}
	store, err := NewFileStore(filepath.Join(root, "resident"))
	if err != nil {
		t.Fatal(err)
	}
	record := Record{Schema: 1, Target: target(), Plan: plan("phone"), Phase: "pending"}
	if err := os.Symlink(outside, store.path(record.Target)); err != nil {
		t.Fatal(err)
	}
	if err := store.Save(record); err == nil {
		t.Fatal("symlink destination accepted")
	}
	if _, err := store.Load(); err == nil {
		t.Fatal("symlink record loaded")
	}
	data, _ := os.ReadFile(outside)
	if string(data) != "unchanged" {
		t.Fatal("outside file overwritten")
	}
	alias := filepath.Join(root, "alias")
	if err := os.Symlink(store.directory, alias); err != nil {
		t.Fatal(err)
	}
	if _, err := NewFileStore(alias); err == nil {
		t.Fatal("symlink directory accepted")
	}
	if err := os.Chmod(store.directory, 0777); err != nil {
		t.Fatal(err)
	}
	if _, err := store.Load(); err == nil {
		t.Fatal("untrusted writable ledger accepted")
	}
}

func TestFileStoreRejectsCorruptAndOversizedRecords(t *testing.T) {
	for _, value := range []string{"{broken", `{"schema":1,"extra":"unknown"}`, string(make([]byte, maximumRecord+1))} {
		store, err := NewFileStore(filepath.Join(t.TempDir(), "resident"))
		if err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(store.path(target()), []byte(value), 0600); err != nil {
			t.Fatal(err)
		}
		if _, err := store.Load(); err == nil {
			t.Fatal("corrupt journal treated as empty")
		}
	}
}
