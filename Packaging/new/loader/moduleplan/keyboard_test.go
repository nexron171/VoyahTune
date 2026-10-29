package moduleplan

import "testing"

func TestKeyboardSelectionBelongsToProcessIdentity(t *testing.T) {
	current := ProcessIdentity{BootID: "boot", PID: 42, StartTicks: "100"}
	previous := KeyboardSelection{Identity: current, Mode: "ru"}
	reads := 0
	readSetting := func() string { reads++; return "en" }
	if mode := KeyboardMode(&previous, current, readSetting); mode != "ru" || reads != 0 {
		t.Fatalf("resident keyboard changed: mode=%s reads=%d", mode, reads)
	}
	for _, identity := range []ProcessIdentity{
		{BootID: "next-boot", PID: 42, StartTicks: "100"},
		{BootID: "boot", PID: 43, StartTicks: "100"},
		{BootID: "boot", PID: 42, StartTicks: "101"},
	} {
		if mode := KeyboardMode(&previous, identity, readSetting); mode != "en" {
			t.Fatalf("new target retained old selection: %+v %s", identity, mode)
		}
	}
	if reads != 3 {
		t.Fatalf("setting reads=%d", reads)
	}
}

func TestKeyboardSelectionRejectsUnknownModes(t *testing.T) {
	identity := ProcessIdentity{BootID: "boot", PID: 42, StartTicks: "100"}
	if mode := KeyboardMode(nil, identity, func() string { return "other" }); mode != "off" {
		t.Fatalf("unknown setting enabled mode %s", mode)
	}
	previous := KeyboardSelection{Identity: identity, Mode: "corrupt"}
	if mode := KeyboardMode(&previous, identity, func() string { t.Fatal("resident selection was reopened"); return "ru" }); mode != "off" {
		t.Fatalf("unknown saved mode accepted: %s", mode)
	}
}
