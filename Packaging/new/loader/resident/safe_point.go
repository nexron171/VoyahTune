package resident

import (
	"strings"

	"voyahtune/unified-loader/injecthelper"
)

// SafePointNotReadyError requires a settled attempt that never loaded target code.
type SafePointNotReadyError struct {
	Reason string
}

func (failure *SafePointNotReadyError) Error() string {
	return failure.Reason
}

func safePointAcquisitionTimeout(receipt injecthelper.Receipt, exitCode int) bool {
	native := receipt.Native
	return exitCode != 0 && receipt.Completed && receipt.Stage == "failed" &&
		strings.HasPrefix(receipt.Error, "native attach: wait-safe-syscall:") &&
		native.Attached && native.Restored && native.Detached &&
		!native.RegistersSaved && !native.LibraryLoaded && !native.WorkerStarted &&
		!native.Uncertain && !native.TargetExited && native.SafePC == 0 && native.BootstrapResult == 0
}
