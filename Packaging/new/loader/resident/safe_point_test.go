package resident

import (
	"testing"

	"voyahtune/unified-loader/injecthelper"
	"voyahtune/unified-loader/remoteattach"
)

func safePointFailureFixture() injecthelper.Receipt {
	return injecthelper.Receipt{
		Stage: "failed", Completed: true, Error: "native attach: wait-safe-syscall: Connection timed out: connection timed out",
		Native: remoteattach.NativeResult{Attached: true, Restored: true, Detached: true},
	}
}

func TestSafePointRetryRequiresAnUntouchedRestoredTarget(t *testing.T) {
	receipt := safePointFailureFixture()
	if !safePointAcquisitionTimeout(receipt, 1) {
		t.Fatal("settled acquire timeout was not retryable")
	}
	if safePointAcquisitionTimeout(receipt, 0) {
		t.Fatal("successful helper was classified as a timeout")
	}
	mutations := []func(*injecthelper.Receipt){
		func(r *injecthelper.Receipt) { r.Completed = false },
		func(r *injecthelper.Receipt) { r.Stage = "active" },
		func(r *injecthelper.Receipt) { r.Error = "native attach: remote-dlopen: denied" },
		func(r *injecthelper.Receipt) { r.Error = "native attach: attach-cancelled" },
		func(r *injecthelper.Receipt) { r.Native.Attached = false },
		func(r *injecthelper.Receipt) { r.Native.RegistersSaved = true },
		func(r *injecthelper.Receipt) { r.Native.Restored = false },
		func(r *injecthelper.Receipt) { r.Native.Detached = false },
		func(r *injecthelper.Receipt) { r.Native.LibraryLoaded = true },
		func(r *injecthelper.Receipt) { r.Native.WorkerStarted = true },
		func(r *injecthelper.Receipt) { r.Native.Uncertain = true },
		func(r *injecthelper.Receipt) { r.Native.TargetExited = true },
		func(r *injecthelper.Receipt) { r.Native.SafePC = 4096 },
		func(r *injecthelper.Receipt) { r.Native.BootstrapResult = 1 },
	}
	for index, change := range mutations {
		altered := safePointFailureFixture()
		change(&altered)
		if safePointAcquisitionTimeout(altered, 1) {
			t.Fatalf("unsafe failure %d was retryable: %+v", index, altered)
		}
	}
}
