package remoteattach

import (
	"bufio"
	"debug/elf"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

type mapping struct {
	Start       uint64
	End         uint64
	Offset      uint64
	Permissions string
	Path        string
}

func parseMappings(reader io.Reader) ([]mapping, error) {
	var result []mapping
	scanner := bufio.NewScanner(reader)
	for scanner.Scan() {
		fields := strings.Fields(scanner.Text())
		if len(fields) < 5 {
			return nil, errors.New("short target maps record")
		}
		first, last, found := strings.Cut(fields[0], "-")
		start, firstError := strconv.ParseUint(first, 16, 64)
		end, lastError := strconv.ParseUint(last, 16, 64)
		offset, offsetError := strconv.ParseUint(fields[2], 16, 64)
		permissions := fields[1]
		if !found || firstError != nil || lastError != nil || offsetError != nil || start >= end || len(permissions) != 4 ||
			!strings.ContainsRune("r-", rune(permissions[0])) || !strings.ContainsRune("w-", rune(permissions[1])) ||
			!strings.ContainsRune("x-", rune(permissions[2])) || !strings.ContainsRune("ps", rune(permissions[3])) {
			return nil, errors.New("invalid target maps record")
		}
		path := ""
		if len(fields) > 5 {
			path = strings.Join(fields[5:], " ")
		}
		result = append(result, mapping{start, end, offset, permissions, path})
	}
	if err := scanner.Err(); err != nil {
		return nil, err
	}
	return result, nil
}

type image struct {
	*elf.File
	path string
}

func openImage(path string) (*image, error) {
	file, err := elf.Open(path)
	if err != nil {
		return nil, err
	}
	validARM := file.Class == elf.ELFCLASS32 && file.Machine == elf.EM_ARM
	validARM64 := file.Class == elf.ELFCLASS64 && file.Machine == elf.EM_AARCH64
	if (!validARM && !validARM64) || file.Data != elf.ELFDATA2LSB || file.Type != elf.ET_DYN {
		file.Close()
		return nil, errors.New("Android ARM/ARM64 shared ELF required")
	}
	return &image{file, path}, nil
}

func (image *image) loadBias(mappings []mapping, pageSize uint64) (uint64, error) {
	if pageSize == 0 || pageSize&(pageSize-1) != 0 {
		return 0, errors.New("invalid target page size")
	}
	var bias uint64
	found := false
	for _, region := range mappings {
		if region.Path != image.path || region.Permissions[2] != 'x' {
			continue
		}
		for _, segment := range image.Progs {
			if segment.Type != elf.PT_LOAD || segment.Flags&elf.PF_X == 0 || segment.Off&^(pageSize-1) != region.Offset {
				continue
			}
			virtual := segment.Vaddr & ^(pageSize - 1)
			if region.Start < virtual {
				return 0, errors.New("invalid target load bias")
			}
			candidate := region.Start - virtual
			if found && candidate != bias {
				return 0, errors.New("ambiguous target ELF mappings")
			}
			bias, found = candidate, true
		}
	}
	if !found {
		return 0, errors.New("target executable ELF segment was not found")
	}
	return bias, nil
}

func (image *image) executable(address uint64, mappings []mapping) bool {
	for _, region := range mappings {
		if region.Path == image.path && region.Permissions[2] == 'x' && address >= region.Start && address < region.End {
			return true
		}
	}
	return false
}

func (image *image) Symbol(name string, mappings []mapping, pageSize uint64) (uint64, error) {
	bias, err := image.loadBias(mappings, pageSize)
	if err != nil {
		return 0, err
	}
	for _, symbols := range []func() ([]elf.Symbol, error){image.DynamicSymbols, image.Symbols} {
		entries, err := symbols()
		if err != nil && !errors.Is(err, elf.ErrNoSymbols) {
			return 0, err
		}
		for _, entry := range entries {
			if entry.Name == name && entry.Section != elf.SHN_UNDEF && elf.ST_TYPE(entry.Info) == elf.STT_FUNC {
				address := bias + entry.Value
				if address < bias || !image.executable(address, mappings) {
					return 0, errors.New("target symbol is outside executable mappings")
				}
				return address, nil
			}
		}
	}
	return 0, fmt.Errorf("target ELF symbol not found: %s", name)
}

func (image *image) Breakpoint(mappings []mapping, pageSize uint64) (uint64, error) {
	bias, err := image.loadBias(mappings, pageSize)
	if err != nil {
		return 0, err
	}
	for _, program := range image.Progs {
		if program.Type != elf.PT_LOAD || program.Flags&elf.PF_X == 0 || program.Filesz > 64*1024*1024 {
			continue
		}
		data, err := io.ReadAll(program.Open())
		if err != nil {
			return 0, err
		}
		for offset := 0; offset+2 <= len(data); offset += 2 {
			address := bias + program.Vaddr + uint64(offset)
			if !image.executable(address, mappings) {
				continue
			}
			if image.Class == elf.ELFCLASS32 {
				thumb := binary.LittleEndian.Uint16(data[offset:])
				if thumb&0xff00 == 0xbe00 || thumb&0xff00 == 0xde00 {
					return address | 1, nil
				}
			}
			if offset+4 > len(data) || address%4 != 0 {
				continue
			}
			instruction := binary.LittleEndian.Uint32(data[offset:])
			if image.Class == elf.ELFCLASS64 && instruction&0xffe0001f == 0xd4200000 {
				return address, nil
			}
			if image.Class == elf.ELFCLASS32 && (instruction&0xfff000f0 == 0xe1200070 || instruction&0xfff000f0 == 0xe7f000f0) {
				return address, nil
			}
		}
	}
	return 0, errors.New("no existing ARM breakpoint in target ELF")
}

type Functions struct {
	Dlopen     uint64
	Dlsym      uint64
	Dlclose    uint64
	Dlerror    uint64
	Mmap       uint64
	Munmap     uint64
	Breakpoint uint64
	Caller     uint64
}

func ResolveFunctions(procRoot string, pid int) (Functions, error) {
	var result Functions
	architecture, err := DetectArchitecture(procRoot, pid)
	if err != nil {
		return result, err
	}
	linker, process := "linker64", "app_process64"
	if architecture == ARM32 {
		linker, process = "linker", "app_process32"
	}
	directory := filepath.Join(procRoot, strconv.Itoa(pid))
	data, err := os.ReadFile(filepath.Join(directory, "maps"))
	if err != nil {
		return result, err
	}
	mappings, err := parseMappings(strings.NewReader(string(data)))
	if err != nil {
		return result, err
	}
	paths := map[string]string{}
	for _, region := range mappings {
		name := filepath.Base(region.Path)
		if region.Permissions[2] == 'x' && (name == linker || name == "libc.so") {
			if previous := paths[name]; previous != "" && previous != region.Path {
				return result, errors.New("multiple target linker/libc images")
			}
			paths[name] = region.Path
		}
		if region.Permissions[2] == 'x' && (name == process || name == process+"_real") {
			result.Caller = region.Start + 4
		}
	}
	pageSize := uint64(os.Getpagesize())
	for _, name := range []string{linker, "libc.so"} {
		path := paths[name]
		if !strings.HasPrefix(path, "/") {
			return result, fmt.Errorf("target %s image is missing", name)
		}
		image, err := openImage(filepath.Join(directory, "root") + path)
		if err != nil {
			return result, err
		}
		if (architecture == ARM32) != (image.Class == elf.ELFCLASS32) {
			image.Close()
			return result, errors.New("target executable and library ABI differ")
		}
		image.path = path
		err = func() error {
			defer image.Close()
			entries := map[string]*uint64{"mmap": &result.Mmap, "munmap": &result.Munmap}
			if name == linker {
				entries = map[string]*uint64{"__loader_dlopen": &result.Dlopen, "__loader_dlsym": &result.Dlsym,
					"__loader_dlclose": &result.Dlclose, "__loader_dlerror": &result.Dlerror}
			} else {
				address, err := image.Breakpoint(mappings, pageSize)
				if err != nil {
					return err
				}
				result.Breakpoint = address
			}
			for symbol, destination := range entries {
				address, err := image.Symbol(symbol, mappings, pageSize)
				if err != nil {
					return err
				}
				*destination = address
			}
			return nil
		}()
		if err != nil {
			return result, err
		}
	}
	if result.Caller == 0 {
		return result, errors.New("target app_process namespace address is missing")
	}
	return result, nil
}
