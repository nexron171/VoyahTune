package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"syscall"

	"voyahtune/unified-loader/agentwire"
)

const nativeLibraryName = "libvoyahtune-agent.so"
const agentDEXName = "agents.dex"
const nativeHelperName = "voyahtune-inject"
const nativeARM32HelperName = "voyahtune-inject-arm"
const nativeARM32LibraryName = "libvoyahtune-agent-arm.so"

type nativeAgentConfig struct {
	ID             string   `json:"id"`
	Target         string   `json:"target"`
	Modules        []string `json:"modules"`
	Condition      string   `json:"condition,omitempty"`
	MaxAttempts    int      `json:"maxAttempts"`
	TimeoutSeconds int      `json:"timeoutSeconds"`
	CrashLimit     int      `json:"crashLimit,omitempty"`
	Dynamic        bool     `json:"-"`
}

type nativeCode struct {
	Schema     int               `json:"schema"`
	Runtime    string            `json:"runtime"`
	AndroidAPI int               `json:"androidApi"`
	NativeID   string            `json:"nativeId"`
	RuntimeID  string            `json:"runtimeId"`
	SHA256     map[string]string `json:"sha256"`
}

type nativeLoaderConfig struct {
	Schema int                 `json:"schema"`
	Agents []nativeAgentConfig `json:"agents"`
	Code   nativeCode          `json:"-"`
}

func readRuntimeJSON(path string, value any) error {
	file, err := os.OpenFile(path, os.O_RDONLY|syscall.O_NOFOLLOW, 0)
	if err != nil {
		return err
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil {
		return err
	}
	if !info.Mode().IsRegular() || info.Size() < 1 || info.Size() > 1024*1024 {
		return errors.New("bounded regular runtime JSON required")
	}
	stat, ok := info.Sys().(*syscall.Stat_t)
	if !ok || stat.Uid != uint32(os.Geteuid()) || stat.Nlink != 1 || info.Mode().Perm()&0022 != 0 {
		return errors.New("runtime JSON must be owned, immutable and singly linked")
	}
	data, err := io.ReadAll(io.LimitReader(file, 1024*1024+1))
	if err != nil {
		return err
	}
	if len(data) > 1024*1024 {
		return errors.New("runtime JSON exceeds size limit")
	}
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(value); err != nil {
		return err
	}
	if err := decoder.Decode(new(any)); err != io.EOF {
		return errors.New("unexpected runtime JSON data")
	}
	return nil
}

func loadNativeConfig(directory string) (nativeLoaderConfig, error) {
	return loadNativeConfigWithCode(directory, embeddedNativeCode)
}

func loadNativeConfigWithCode(directory string, code nativeCode) (nativeLoaderConfig, error) {
	config := nativeLoaderConfig{Schema: 2, Agents: compiledAgents(), Code: code}
	info, err := os.Lstat(directory)
	if err != nil {
		return config, err
	}
	stat, ok := info.Sys().(*syscall.Stat_t)
	if !ok || stat.Uid != uint32(os.Geteuid()) || !info.IsDir() || info.Mode().Perm()&0022 != 0 {
		return config, errors.New("runtime directory must not be a symlink or writable by other users")
	}
	if code.Schema != 2 || code.Runtime != "unified" || code.AndroidAPI != 30 || len(code.SHA256) < 3 || len(code.SHA256) > 64 {
		return config, errors.New("invalid native runtime component metadata")
	}
	if _, err := agentwire.NewClient(agentwire.Target{BootID: "validation", PID: 2, StartTicks: "1", NativeID: code.NativeID, RuntimeID: code.RuntimeID}); err != nil {
		return config, err
	}
	for _, name := range []string{nativeLibraryName, nativeARM32LibraryName, agentDEXName, nativeHelperName, nativeARM32HelperName} {
		if code.SHA256[name] == "" {
			return config, fmt.Errorf("missing native component %s", name)
		}
	}
	for name, expected := range code.SHA256 {
		digest, err := hex.DecodeString(expected)
		if !safeName(name) || err != nil || len(digest) != 32 {
			return config, errors.New("invalid runtime component digest")
		}
		path := filepath.Join(directory, name)
		file, err := os.OpenFile(path, os.O_RDONLY|syscall.O_NOFOLLOW, 0)
		if err != nil {
			return config, err
		}
		info, statError := file.Stat()
		if statError != nil {
			file.Close()
			return config, statError
		}
		stat, ok := info.Sys().(*syscall.Stat_t)
		if !ok || stat.Uid != uint32(os.Geteuid()) || stat.Nlink != 1 || !info.Mode().IsRegular() || info.Mode().Perm()&0022 != 0 || info.Size() < 1 || info.Size() > 64*1024*1024 {
			file.Close()
			return config, fmt.Errorf("unsafe runtime component %s", name)
		}
		if (name == nativeHelperName || name == nativeARM32HelperName) && info.Mode().Perm() != 0755 {
			file.Close()
			return config, errors.New("native helper must have mode755")
		}
		hash := sha256.New()
		_, readError := io.Copy(hash, file)
		closeError := file.Close()
		if readError != nil {
			return config, readError
		}
		if closeError != nil {
			return config, closeError
		}
		if hex.EncodeToString(hash.Sum(nil)) != expected {
			return config, fmt.Errorf("runtime component digest mismatch: %s", name)
		}
	}
	return config, nil
}

func safeName(value string) bool {
	if value == "" || strings.HasPrefix(value, ".") || strings.Contains(value, "..") {
		return false
	}
	for _, character := range value {
		if !(character >= 'a' && character <= 'z' || character >= 'A' && character <= 'Z' || character >= '0' && character <= '9' || character == '.' || character == '_' || character == '-') {
			return false
		}
	}
	return true
}
