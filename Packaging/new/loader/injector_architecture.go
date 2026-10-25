package main

import (
	"encoding/binary"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strconv"
)

func injectorExecutable(directory, procRoot string, pid int) (string, error) {
	executable, err := os.Open(filepath.Join(procRoot, strconv.Itoa(pid), "exe"))
	if err != nil {
		return "", fmt.Errorf("read target architecture: %w", err)
	}
	defer executable.Close()
	var header [20]byte
	if _, err := io.ReadFull(executable, header[:]); err != nil {
		return "", fmt.Errorf("read target ELF header: %w", err)
	}
	if string(header[:4]) != "\x7fELF" || header[5] != 1 {
		return "", fmt.Errorf("unsupported target executable format")
	}
	// A same-ABI injector avoids Frida 16's external ARM32 helper shutdown hang.
	machine := binary.LittleEndian.Uint16(header[18:20])
	switch {
	case header[4] == 1 && machine == 40:
		return filepath.Join(directory, "frida-inject-arm"), nil
	case header[4] == 2 && machine == 183:
		return filepath.Join(directory, "frida-inject"), nil
	default:
		return "", fmt.Errorf("unsupported target architecture: class=%d machine=%d", header[4], machine)
	}
}
