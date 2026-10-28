package lifecycle

import (
	"context"
	"encoding/json"
	"net"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func rootPeer(*net.UnixConn) (uint32, error) { return 0, nil }

func shortSocketDirectory(t *testing.T) string {
	t.Helper()
	directory, err := os.MkdirTemp("", "vtc-")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.RemoveAll(directory) })
	return directory
}

func TestControlSocketQuiesceAndCorrelation(t *testing.T) {
	identity := ControlIdentity{BootID: "boot", PID: 123, StartTicks: "456"}
	coordinator := &Coordinator{}
	path := filepath.Join(shortSocketDirectory(t), "control.sock")
	server, err := ListenControl(path, NewControl(identity, coordinator))
	if err != nil {
		t.Fatal(err)
	}
	server.peerUID = rootPeer
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	finished := make(chan error, 1)
	go func() { finished <- server.Serve(ctx) }()
	request := ControlRequest{Schema: 1, RequestID: "request-123", Identity: identity, Command: "quiesce", TimeoutMillis: 100}
	response, err := exchangeControl(ctx, path, request, rootPeer)
	if err != nil || response.State.Phase != Quiesced {
		t.Fatalf("RPC failed: %+v %v", response, err)
	}
	info, err := os.Stat(path)
	if err != nil || info.Mode().Perm() != 0600 {
		t.Fatalf("control socket permissions: %v %v", info, err)
	}
	cancel()
	if err := <-finished; err != nil {
		t.Fatal(err)
	}
	if _, err := os.Lstat(path); !os.IsNotExist(err) {
		t.Fatalf("owned socket was not removed: %v", err)
	}
}

func TestControlClientRejectsStaleReply(t *testing.T) {
	for _, field := range []string{"request", "identity", "state"} {
		t.Run(field, func(t *testing.T) {
			path := filepath.Join(shortSocketDirectory(t), "stale.sock")
			listener, err := net.ListenUnix("unix", &net.UnixAddr{Name: path, Net: "unix"})
			if err != nil {
				t.Fatal(err)
			}
			defer listener.Close()
			request := ControlRequest{Schema: 1, RequestID: "request-123", Identity: ControlIdentity{BootID: "boot", PID: 123, StartTicks: "456"}, Command: "quiesce", TimeoutMillis: 100}
			finished := make(chan struct{})
			go func() {
				defer close(finished)
				connection, err := listener.AcceptUnix()
				if err != nil {
					return
				}
				defer connection.Close()
				var received ControlRequest
				if json.NewDecoder(connection).Decode(&received) != nil {
					return
				}
				response := ControlResponse{Schema: 1, RequestID: received.RequestID, Identity: received.Identity, State: State{Phase: Quiesced}}
				switch field {
				case "request":
					response.RequestID = "request-old"
				case "identity":
					response.Identity.StartTicks = "old"
				case "state":
					response.State.InFlight = 1
				}
				json.NewEncoder(connection).Encode(response)
			}()
			ctx, cancel := context.WithTimeout(context.Background(), time.Second)
			defer cancel()
			if _, err := exchangeControl(ctx, path, request, rootPeer); err == nil {
				t.Fatal("stale or contradictory acknowledgement was accepted")
			}
			<-finished
		})
	}
}
