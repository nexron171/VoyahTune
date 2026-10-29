package injecthelper

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"time"

	"voyahtune/unified-loader/agentwire"
	"voyahtune/unified-loader/remoteattach"
)

type Request struct {
	Schema               int              `json:"schema"`
	RequestID            string           `json:"requestId"`
	Target               agentwire.Target `json:"target"`
	LibraryPath          string           `json:"libraryPath"`
	DEXPath              string           `json:"dexPath"`
	DEXSHA256            string           `json:"dexSha256"`
	AcquireTimeoutMillis int64            `json:"acquireTimeoutMillis"`
	StartupTimeoutMillis int64            `json:"startupTimeoutMillis"`
	Plan                 agentwire.Plan   `json:"plan"`
}

type Receipt struct {
	Schema    int                       `json:"schema"`
	RequestID string                    `json:"requestId"`
	Target    agentwire.Target          `json:"target"`
	Helper    agentwire.Target          `json:"helper"`
	Stage     string                    `json:"stage"`
	Native    remoteattach.NativeResult `json:"native"`
	Status    *agentwire.Status         `json:"status,omitempty"`
	Reused    bool                      `json:"reused"`
	Completed bool                      `json:"completed"`
	Error     string                    `json:"error,omitempty"`
}

type journal struct {
	mutex sync.Mutex
	path  string
	value Receipt
}

func (journal *journal) update(change func(*Receipt)) error {
	journal.mutex.Lock()
	defer journal.mutex.Unlock()
	change(&journal.value)
	data, err := json.Marshal(journal.value)
	if err != nil {
		return err
	}
	file, err := os.CreateTemp(filepath.Dir(journal.path), ".inject-receipt-")
	if err != nil {
		return err
	}
	name := file.Name()
	defer os.Remove(name)
	if _, err = file.Write(data); err == nil {
		err = file.Sync()
	}
	closeError := file.Close()
	if err != nil {
		return err
	}
	if closeError != nil {
		return closeError
	}
	if err := os.Rename(name, journal.path); err != nil {
		return err
	}
	directory, err := os.Open(filepath.Dir(journal.path))
	if err != nil {
		return err
	}
	defer directory.Close()
	return directory.Sync()
}

func ReadRequest(path string) (Request, error) {
	var request Request
	file, err := os.OpenFile(path, os.O_RDONLY|syscall.O_NOFOLLOW, 0)
	if err != nil {
		return request, err
	}
	defer file.Close()
	information, err := file.Stat()
	if err != nil {
		return request, err
	}
	stat, ok := information.Sys().(*syscall.Stat_t)
	if !ok || stat.Uid != 0 || !information.Mode().IsRegular() || information.Mode().Perm()&0022 != 0 || information.Size() > 1024*1024 {
		return request, errors.New("request must be a bounded root-owned immutable file")
	}
	return decodeRequest(file)
}

func decodeRequest(reader io.Reader) (Request, error) {
	var request Request
	data, err := io.ReadAll(io.LimitReader(reader, 1024*1024+1))
	if err != nil {
		return request, err
	}
	if len(data) > 1024*1024 {
		return request, errors.New("helper request is too large")
	}
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&request); err != nil {
		return request, err
	}
	if err := decoder.Decode(new(any)); err != io.EOF {
		return request, errors.New("trailing helper request data")
	}
	if request.Schema != 1 || len(request.RequestID) < 8 || len(request.RequestID) > 128 ||
		strings.IndexFunc(request.RequestID, func(value rune) bool {
			return !(value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z' || value >= '0' && value <= '9' || strings.ContainsRune("._:-", value))
		}) >= 0 {
		return request, errors.New("invalid helper request schema/id")
	}
	if request.AcquireTimeoutMillis < 1 || request.AcquireTimeoutMillis > 60000 || request.StartupTimeoutMillis < 1 || request.StartupTimeoutMillis > 60000 {
		return request, errors.New("helper deadline must be 1..60000 ms")
	}
	if err := request.Plan.Validate(request.Target.RuntimeID); err != nil {
		return request, err
	}
	if err := request.nativeRequest().Validate(); err != nil {
		return request, err
	}
	return request, nil
}

func (request Request) nativeRequest() remoteattach.Request {
	return remoteattach.Request{Target: request.Target, LibraryPath: request.LibraryPath, DEXPath: request.DEXPath,
		DEXSHA256: request.DEXSHA256, AcquireTimeout: time.Duration(request.AcquireTimeoutMillis) * time.Millisecond,
		StartupTimeout: time.Duration(request.StartupTimeoutMillis) * time.Millisecond}
}

func Run(ctx context.Context, request Request, receiptPath string) (Receipt, error) {
	return (Engine{Platform: nativePlatform{}}).Run(ctx, request, receiptPath)
}

func (engine Engine) Run(ctx context.Context, request Request, receiptPath string) (Receipt, error) {
	if engine.Platform == nil {
		return Receipt{}, errors.New("helper platform required")
	}
	journal := &journal{path: receiptPath, value: Receipt{Schema: 1, RequestID: request.RequestID, Target: request.Target, Stage: "starting"}}
	helper, err := engine.Platform.Self()
	if err != nil {
		return journal.value, err
	}
	journal.value.Helper = helper
	if err := journal.update(func(*Receipt) {}); err != nil {
		return journal.value, err
	}
	client, err := engine.Platform.Connect(request.Target)
	if err != nil {
		return finish(journal, "failed", err)
	}
	if err := engine.Platform.Verify(request.Target); err != nil {
		return finish(journal, "failed", err)
	}
	for _, path := range []string{request.LibraryPath, request.DEXPath} {
		if err := engine.Platform.ValidateFile(path); err != nil {
			return finish(journal, "failed", err)
		}
	}
	lockPath := filepath.Join(filepath.Dir(receiptPath), fmt.Sprintf("target-%d-%s.lock", request.Target.PID, request.Target.StartTicks))
	lock, err := os.OpenFile(lockPath, os.O_CREATE|os.O_RDWR|syscall.O_NOFOLLOW, 0600)
	if err != nil {
		return finish(journal, "failed", err)
	}
	defer lock.Close()
	if err := syscall.Flock(int(lock.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
		return finish(journal, "failed", err)
	}
	status, statusError := client.Status(ctx)
	if statusError == nil {
		journal.value.Reused = true
		journal.value.Native = remoteattach.NativeResult{Restored: true, Detached: true, WorkerStarted: true}
	} else if !errors.Is(statusError, syscall.ECONNREFUSED) && !errors.Is(statusError, syscall.ENOENT) {
		journal.value.Status = &status
		return finish(journal, "restart-required", statusError)
	} else {
		functions, err := engine.Platform.Resolve(request.Target)
		if err != nil {
			return finish(journal, "failed", err)
		}
		native := request.nativeRequest()
		var journalFailure error
		nativeResult, attachError := engine.Platform.Inject(ctx, native, functions, func(stage remoteattach.Stage) {
			if err := journal.update(func(receipt *Receipt) { receipt.Stage = stage.String() }); err != nil {
				journalFailure = err
			}
		})
		journal.value.Native = nativeResult
		if attachError == nil {
			attachError = journalFailure
		}
		if attachError != nil {
			if nativeResult.WorkerStarted {
				engine.drain(client, request.Target, journal)
			}
			return finish(journal, "failed", attachError)
		}
	}
	if err := journal.update(func(receipt *Receipt) { receipt.Stage = "java-init" }); err != nil {
		engine.drain(client, request.Target, journal)
		return finish(journal, "failed", err)
	}
	status, err = client.Apply(ctx, request.Plan)
	journal.value.Status = &status
	if err == nil {
		err = engine.Platform.Verify(request.Target)
	}
	if err == nil {
		err = ctx.Err()
	}
	if err != nil {
		engine.drain(client, request.Target, journal)
		return finish(journal, "failed", err)
	}
	return finish(journal, "active", nil)
}

func validateRuntimeFile(path string) error {
	file, err := os.OpenFile(path, os.O_RDONLY|syscall.O_NOFOLLOW, 0)
	if err != nil {
		return err
	}
	defer file.Close()
	information, err := file.Stat()
	if err != nil {
		return err
	}
	stat, ok := information.Sys().(*syscall.Stat_t)
	if !ok || stat.Uid != 0 || stat.Nlink != 1 || !information.Mode().IsRegular() || information.Mode().Perm()&0022 != 0 ||
		information.Size() < 1 || information.Size() > 64*1024*1024 {
		return errors.New("runtime file must be bounded, root-owned, immutable and singly linked")
	}
	return nil
}

func (engine Engine) drain(client Agent, target agentwire.Target, journal *journal) {
	for {
		if err := engine.Platform.Verify(target); err != nil {
			if errors.Is(err, os.ErrNotExist) {
				return
			}
		} else {
			status, err := client.Quiesce(context.Background())
			journal.value.Status = &status
			if err == nil {
				return
			}
			journal.update(func(receipt *Receipt) { receipt.Stage = "quarantined"; receipt.Error = err.Error() })
		}
		time.Sleep(250 * time.Millisecond)
	}
}

func finish(journal *journal, stage string, failure error) (Receipt, error) {
	err := journal.update(func(receipt *Receipt) {
		receipt.Stage = stage
		receipt.Completed = true
		if failure != nil {
			receipt.Error = failure.Error()
		}
	})
	if err != nil {
		return journal.value, errors.Join(failure, err)
	}
	return journal.value, failure
}
