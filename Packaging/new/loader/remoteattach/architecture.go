package remoteattach

import (
	"encoding/binary"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strconv"
)

type Architecture string

const (
	ARM32 Architecture = "armeabi-v7a"
	ARM64 Architecture = "arm64-v8a"
)

func DetectArchitecture(procRoot string, pid int) (Architecture, error) {
	file, err := os.Open(filepath.Join(procRoot, strconv.Itoa(pid), "exe"))
	if err != nil {
		return "", err
	}
	defer file.Close()
	var header [20]byte
	if _, err := io.ReadFull(file, header[:]); err != nil {
		return "", err
	}
	if string(header[:4]) != "\x7fELF" || header[5] != 1 {
		return "", errors.New("little-endian Android ARM ELF required")
	}
	machine := binary.LittleEndian.Uint16(header[18:])
	if header[4] == 1 && machine == 40 {
		return ARM32, nil
	}
	if header[4] == 2 && machine == 183 {
		return ARM64, nil
	}
	return "", errors.New("Android ARM32 or ARM64 target required")
}
