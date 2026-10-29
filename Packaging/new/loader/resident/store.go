package resident

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
	"syscall"

	"voyahtune/unified-loader/agentwire"
)

const maximumRecord = 1024 * 1024

type FileStore struct {
	directory string
	identity  os.FileInfo
}

func NewFileStore(directory string) (*FileStore, error) {
	if !filepath.IsAbs(directory) || filepath.Clean(directory) != directory {
		return nil, errors.New("absolute normalized resident directory required")
	}
	if err := os.Mkdir(directory, 0700); err != nil && !os.IsExist(err) {
		return nil, err
	}
	identity, err := os.Lstat(directory)
	if err != nil {
		return nil, err
	}
	store := &FileStore{directory: directory, identity: identity}
	if err := store.verifyDirectory(); err != nil {
		return nil, err
	}
	return store, nil
}

func owned(info os.FileInfo) bool {
	stat, ok := info.Sys().(*syscall.Stat_t)
	return ok && stat.Uid == uint32(os.Geteuid()) && info.Mode().Perm()&0077 == 0
}

func (store *FileStore) verifyDirectory() error {
	current, err := os.Lstat(store.directory)
	if err != nil {
		return err
	}
	if !current.IsDir() || !owned(current) || !os.SameFile(current, store.identity) {
		return errors.New("resident directory is not private, owned and unchanged")
	}
	return nil
}

func (store *FileStore) path(target agentwire.Target) string {
	digest := sha256.Sum256([]byte(key(target)))
	return filepath.Join(store.directory, hex.EncodeToString(digest[:])+".json")
}

func (store *FileStore) Load() ([]Record, error) {
	if err := store.verifyDirectory(); err != nil {
		return nil, err
	}
	entries, err := os.ReadDir(store.directory)
	if err != nil {
		return nil, err
	}
	if len(entries) > 512 {
		return nil, errors.New("resident journal entry limit exceeded")
	}
	records := []Record{}
	for _, entry := range entries {
		if strings.HasPrefix(entry.Name(), ".resident-") {
			continue
		}
		if !strings.HasSuffix(entry.Name(), ".json") {
			return nil, errors.New("unexpected resident journal file")
		}
		record, err := store.read(filepath.Join(store.directory, entry.Name()))
		if err != nil {
			return nil, err
		}
		if filepath.Base(store.path(record.Target)) != entry.Name() {
			return nil, errors.New("resident filename does not match process identity")
		}
		records = append(records, record)
		if len(records) > 256 {
			return nil, errors.New("resident process limit exceeded")
		}
	}
	return records, nil
}

func (store *FileStore) read(path string) (Record, error) {
	var record Record
	file, err := os.OpenFile(path, os.O_RDONLY|syscall.O_NOFOLLOW, 0)
	if err != nil {
		return record, err
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil {
		return record, err
	}
	stat, ok := info.Sys().(*syscall.Stat_t)
	if !info.Mode().IsRegular() || !owned(info) || !ok || stat.Nlink != 1 || info.Size() < 1 || info.Size() > maximumRecord {
		return record, errors.New("resident record is not bounded, private and singly linked")
	}
	data, err := io.ReadAll(io.LimitReader(file, maximumRecord+1))
	if err != nil {
		return record, err
	}
	if len(data) > maximumRecord {
		return record, errors.New("resident record exceeds size limit")
	}
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&record); err != nil {
		return record, err
	}
	if err := decoder.Decode(new(any)); err != io.EOF {
		return record, errors.New("unexpected resident record data")
	}
	return record, validateRecord(record)
}

func (store *FileStore) Save(record Record) error {
	if err := validateRecord(record); err != nil {
		return err
	}
	if err := store.verifyDirectory(); err != nil {
		return err
	}
	path := store.path(record.Target)
	if info, err := os.Lstat(path); err == nil {
		if !info.Mode().IsRegular() || !owned(info) {
			return errors.New("unsafe resident destination")
		}
	} else if !os.IsNotExist(err) {
		return err
	}
	data, err := json.Marshal(record)
	if err != nil {
		return err
	}
	if len(data) > maximumRecord {
		return errors.New("resident record exceeds size limit")
	}
	file, err := os.CreateTemp(store.directory, ".resident-")
	if err != nil {
		return err
	}
	defer os.Remove(file.Name())
	if _, err = file.Write(append(data, '\n')); err == nil {
		err = file.Sync()
	}
	closeError := file.Close()
	if err != nil {
		return err
	}
	if closeError != nil {
		return closeError
	}
	if err := store.verifyDirectory(); err != nil {
		return err
	}
	if err := os.Rename(file.Name(), path); err != nil {
		return err
	}
	return store.sync()
}

func (store *FileStore) Remove(target agentwire.Target) error {
	if err := store.verifyDirectory(); err != nil {
		return err
	}
	if err := os.Remove(store.path(target)); err != nil && !os.IsNotExist(err) {
		return err
	}
	return store.sync()
}

func (store *FileStore) sync() error {
	directory, err := os.Open(store.directory)
	if err != nil {
		return err
	}
	defer directory.Close()
	return directory.Sync()
}
