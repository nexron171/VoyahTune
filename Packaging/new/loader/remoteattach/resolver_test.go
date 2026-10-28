package remoteattach

import (
	"bytes"
	"debug/elf"
	"encoding/binary"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestResolveTargetAddressesFromLoadSegments(t *testing.T) {
	name := filepath.Join(t.TempDir(), "libc.so")
	if err := os.WriteFile(name, testELF(t), 0600); err != nil {
		t.Fatal(err)
	}
	image, err := openImage(name)
	if err != nil {
		t.Fatal(err)
	}
	defer image.Close()
	mappings, err := parseMappings(strings.NewReader("70001000-70002000 r--p 00000000 08:01 42 " + name + "\n70008000-70009000 r-xp 00002000 08:01 42 " + name + "\n"))
	if err != nil {
		t.Fatal(err)
	}
	address, err := image.Symbol("mmap", mappings, 4096)
	if err != nil || address != 0x70008010 {
		t.Fatalf("symbol=%x err=%v", address, err)
	}
	trap, err := image.Breakpoint(mappings, 4096)
	if err != nil || trap != 0x70008004 {
		t.Fatalf("trap=%x err=%v", trap, err)
	}
	if _, err := image.Symbol("not_exported", mappings, 4096); err == nil {
		t.Fatal("missing symbol was accepted")
	}
	mappings[1].Offset = 0x3000
	if _, err := image.Symbol("mmap", mappings, 4096); err == nil {
		t.Fatal("incompatible segment offset was accepted")
	}
}

func TestMappingsRejectMalformedOrAmbiguousTargetData(t *testing.T) {
	for _, input := range []string{"bad\n", "10-01 r-xp 0 08:01 1 /lib.so\n", "10-20 r-xp nope 08:01 1 /lib.so\n", "10-20 bogus 0 08:01 1 /lib.so\n"} {
		if _, err := parseMappings(strings.NewReader(input)); err == nil {
			t.Fatalf("accepted malformed maps %q", input)
		}
	}
	mappings, err := parseMappings(strings.NewReader("10-20 r-xp 0 08:01 1 /system/lib64/a library.so\n"))
	if err != nil || mappings[0].Path != "/system/lib64/a library.so" {
		t.Fatalf("path parsing: %+v %v", mappings, err)
	}
}

func TestImageRejectsWrongArchitectureAndTruncatedELF(t *testing.T) {
	for _, mutate := range []func([]byte) []byte{
		func(data []byte) []byte { return data[:50] },
		func(data []byte) []byte { binary.LittleEndian.PutUint16(data[18:], uint16(elf.EM_X86_64)); return data },
	} {
		path := filepath.Join(t.TempDir(), "image.so")
		if err := os.WriteFile(path, mutate(testELF(t)), 0600); err != nil {
			t.Fatal(err)
		}
		image, err := openImage(path)
		if image != nil {
			image.Close()
		}
		if err == nil {
			t.Fatal("invalid target ELF accepted")
		}
	}
}

func testELF(t *testing.T) []byte {
	t.Helper()
	data := make([]byte, 0x2400)
	put := func(offset int, value any) {
		var output bytes.Buffer
		if err := binary.Write(&output, binary.LittleEndian, value); err != nil {
			t.Fatal(err)
		}
		copy(data[offset:], output.Bytes())
	}
	var identity [16]byte
	copy(identity[:], []byte{0x7f, 'E', 'L', 'F', byte(elf.ELFCLASS64), byte(elf.ELFDATA2LSB), 1})
	put(0, elf.Header64{Ident: identity, Type: uint16(elf.ET_DYN), Machine: uint16(elf.EM_AARCH64), Version: 1,
		Phoff: 64, Shoff: 0x400, Ehsize: 64, Phentsize: 56, Phnum: 2, Shentsize: 64, Shnum: 4})
	put(64, elf.Prog64{Type: uint32(elf.PT_LOAD), Flags: uint32(elf.PF_R), Off: 0, Vaddr: 0x1000, Filesz: 0x600, Memsz: 0x600, Align: 0x1000})
	put(120, elf.Prog64{Type: uint32(elf.PT_LOAD), Flags: uint32(elf.PF_R | elf.PF_X), Off: 0x2000, Vaddr: 0x8000, Filesz: 0x100, Memsz: 0x100, Align: 0x1000})
	put(0x400+64, elf.Section64{Type: uint32(elf.SHT_DYNSYM), Off: 0x200, Size: 48, Link: 2, Addralign: 8, Entsize: 24})
	put(0x400+128, elf.Section64{Type: uint32(elf.SHT_STRTAB), Off: 0x300, Size: 6, Addralign: 1})
	put(0x400+192, elf.Section64{Type: uint32(elf.SHT_PROGBITS), Flags: uint64(elf.SHF_ALLOC | elf.SHF_EXECINSTR), Addr: 0x8000, Off: 0x2000, Size: 0x100, Addralign: 4})
	put(0x200+24, elf.Sym64{Name: 1, Info: byte(elf.STB_GLOBAL)<<4 | byte(elf.STT_FUNC), Shndx: 3, Value: 0x8010, Size: 4})
	copy(data[0x300:], []byte("\x00mmap\x00"))
	put(0x2004, uint32(0xd4200020))
	return data
}

func TestARM32SymbolsPreserveThumbStateAndUseExistingTraps(t *testing.T) {
	for _, trap := range []struct {
		name        string
		instruction uint32
		address     uint64
	}{
		{"thumb-bkpt", 0x0000be01, 0x70008005},
		{"thumb-udf", 0x0000de01, 0x70008005},
		{"thumb-segment-end", 0x0000de01, 0x700080ff},
		{"arm-bkpt", 0xe1200071, 0x70008004},
		{"arm-udf", 0xe7f001f0, 0x70008004},
	} {
		t.Run(trap.name, func(t *testing.T) {
			data := testELF32(t, trap.instruction)
			if trap.name == "thumb-segment-end" {
				binary.LittleEndian.PutUint32(data[0x2004:], 0)
				binary.LittleEndian.PutUint16(data[0x20fe:], uint16(trap.instruction))
			}
			path := filepath.Join(t.TempDir(), "libc.so")
			if err := os.WriteFile(path, data, 0600); err != nil {
				t.Fatal(err)
			}
			image, err := openImage(path)
			if err != nil {
				t.Fatal(err)
			}
			defer image.Close()
			mappings := []mapping{{Start: 0x70008000, End: 0x70009000, Offset: 0x2000, Permissions: "r-xp", Path: path}}
			symbol, err := image.Symbol("mmap", mappings, 4096)
			if err != nil || symbol != 0x70008011 {
				t.Fatalf("Thumb symbol=%x err=%v", symbol, err)
			}
			breakpoint, err := image.Breakpoint(mappings, 4096)
			if err != nil || breakpoint != trap.address {
				t.Fatalf("trap=%x err=%v", breakpoint, err)
			}
		})
	}
}

func testELF32(t *testing.T, trap uint32) []byte {
	t.Helper()
	data := make([]byte, 0x2400)
	put := func(offset int, value any) {
		var output bytes.Buffer
		if err := binary.Write(&output, binary.LittleEndian, value); err != nil {
			t.Fatal(err)
		}
		copy(data[offset:], output.Bytes())
	}
	var identity [16]byte
	copy(identity[:], []byte{0x7f, 'E', 'L', 'F', byte(elf.ELFCLASS32), byte(elf.ELFDATA2LSB), 1})
	put(0, elf.Header32{Ident: identity, Type: uint16(elf.ET_DYN), Machine: uint16(elf.EM_ARM), Version: 1,
		Phoff: 52, Shoff: 0x400, Ehsize: 52, Phentsize: 32, Phnum: 1, Shentsize: 40, Shnum: 4})
	put(52, elf.Prog32{Type: uint32(elf.PT_LOAD), Flags: uint32(elf.PF_R | elf.PF_X), Off: 0x2000,
		Vaddr: 0x8000, Filesz: 0x100, Memsz: 0x100, Align: 0x1000})
	put(0x400+40, elf.Section32{Type: uint32(elf.SHT_DYNSYM), Off: 0x200, Size: 32, Link: 2, Addralign: 4, Entsize: 16})
	put(0x400+80, elf.Section32{Type: uint32(elf.SHT_STRTAB), Off: 0x300, Size: 6, Addralign: 1})
	put(0x400+120, elf.Section32{Type: uint32(elf.SHT_PROGBITS), Flags: uint32(elf.SHF_ALLOC | elf.SHF_EXECINSTR),
		Addr: 0x8000, Off: 0x2000, Size: 0x100, Addralign: 4})
	put(0x210, elf.Sym32{Name: 1, Info: byte(elf.STB_GLOBAL)<<4 | byte(elf.STT_FUNC), Shndx: 3, Value: 0x8011, Size: 4})
	copy(data[0x300:], []byte("\x00mmap\x00"))
	put(0x2004, trap)
	return data
}
