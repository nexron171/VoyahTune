package resident

import (
	"errors"
	"fmt"
	"io"
	"os"
	"strings"
	"syscall"
)

var ErrInstallBlocked = errors.New("installation has closed runtime admission")
var ErrRebootRequired = errors.New("runtime migration requires an Android reboot")

func CheckAdmission(updatePath, migrationPath, boot string) error {
	if _, err := os.Lstat(updatePath); err == nil {
		return ErrInstallBlocked
	} else if !os.IsNotExist(err) {
		return fmt.Errorf("installation block unavailable: %w", err)
	}
	file, err := os.OpenFile(migrationPath, os.O_RDONLY|syscall.O_NOFOLLOW, 0)
	if os.IsNotExist(err) {
		return nil
	}
	if err != nil {
		return fmt.Errorf("migration boot marker: %w", err)
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil {
		return err
	}
	stat, ok := info.Sys().(*syscall.Stat_t)
	if !ok || !info.Mode().IsRegular() || stat.Uid != uint32(os.Geteuid()) || stat.Nlink != 1 || info.Mode().Perm()&0022 != 0 || info.Size() < 1 || info.Size() > 128 {
		return errors.New("invalid migration boot marker")
	}
	data, err := io.ReadAll(io.LimitReader(file, 129))
	if err != nil {
		return err
	}
	previous := strings.TrimSpace(string(data))
	if previous == "" || strings.ContainsAny(previous, " \t\r\n\x00") || boot == "" {
		return errors.New("invalid migration boot identity")
	}
	if previous == boot {
		return ErrRebootRequired
	}
	return nil
}
