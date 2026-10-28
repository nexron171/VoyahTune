package remoteattach

import (
	"context"
	"encoding/hex"
	"errors"
	"path/filepath"
	"strings"
	"time"

	"voyahtune/unified-loader/agentwire"
)

type Stage uint32

const (
	Waiting Stage = iota + 1
	Attached
	Saved
	Loading
	WorkerStarting
	Restored
	Detached
	Quarantined
)

func (stage Stage) String() string {
	switch stage {
	case Waiting:
		return "waiting"
	case Attached:
		return "attached"
	case Saved:
		return "registers-saved"
	case Loading:
		return "remote-load"
	case WorkerStarting:
		return "worker-start"
	case Restored:
		return "restored"
	case Detached:
		return "detached"
	case Quarantined:
		return "quarantined"
	default:
		return "invalid"
	}
}

type Request struct {
	Target         agentwire.Target
	LibraryPath    string
	DEXPath        string
	DEXSHA256      string
	AcquireTimeout time.Duration
	StartupTimeout time.Duration
}

type NativeResult struct {
	Attached        bool   `json:"attached"`
	RegistersSaved  bool   `json:"registersSaved"`
	Restored        bool   `json:"restored"`
	Detached        bool   `json:"detached"`
	LibraryLoaded   bool   `json:"libraryLoaded"`
	WorkerStarted   bool   `json:"workerStarted"`
	Uncertain       bool   `json:"uncertain"`
	TargetExited    bool   `json:"targetExited"`
	SafePC          uint64 `json:"safePc"`
	BootstrapResult int32  `json:"bootstrapResult"`
}

func (request Request) Validate() error {
	if _, err := agentwire.NewClient(request.Target); err != nil {
		return err
	}
	if request.Target.PID <= 1 || strings.ContainsRune(request.Target.BootID, '\x00') ||
		strings.ContainsRune(request.Target.NativeID, '\x00') || strings.ContainsRune(request.Target.RuntimeID, '\x00') {
		return errors.New("invalid attach target identity")
	}
	for _, path := range []string{request.LibraryPath, request.DEXPath} {
		if !filepath.IsAbs(path) || filepath.Clean(path) != path || len(path) >= 4096 || strings.ContainsRune(path, '\x00') {
			return errors.New("absolute normalized runtime paths required")
		}
	}
	digest, err := hex.DecodeString(request.DEXSHA256)
	if err != nil || len(digest) != 32 || strings.ToLower(request.DEXSHA256) != request.DEXSHA256 {
		return errors.New("lowercase DEX SHA256 required")
	}
	for _, timeout := range []time.Duration{request.AcquireTimeout, request.StartupTimeout} {
		if timeout < time.Millisecond || timeout > time.Minute {
			return errors.New("attach deadline must be 1..60000 ms")
		}
	}
	return nil
}

func Inject(ctx context.Context, request Request, functions Functions, progress func(Stage)) (NativeResult, error) {
	if err := request.Validate(); err != nil {
		return NativeResult{}, err
	}
	if progress == nil {
		progress = func(Stage) {}
	}
	return injectNative(ctx, request, functions, progress)
}
