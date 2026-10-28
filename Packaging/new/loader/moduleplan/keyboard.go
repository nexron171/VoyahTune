package moduleplan

type ProcessIdentity struct {
	BootID     string `json:"bootId"`
	PID        int    `json:"pid"`
	StartTicks string `json:"startTicks"`
}

type KeyboardSelection struct {
	Identity ProcessIdentity `json:"identity"`
	Mode     string          `json:"mode"`
}

func KeyboardMode(previous *KeyboardSelection, current ProcessIdentity, readSetting func() string) string {
	if previous != nil && previous.Identity == current {
		return validKeyboardMode(previous.Mode)
	}
	return validKeyboardMode(readSetting())
}

func validKeyboardMode(mode string) string {
	if mode == "en" || mode == "ru" {
		return mode
	}
	return "off"
}
