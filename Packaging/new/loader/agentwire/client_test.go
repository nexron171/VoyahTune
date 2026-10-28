package agentwire

import (
	"context"
	"encoding/json"
	"net"
	"os"
	"path/filepath"
	"testing"
	"time"

	"voyahtune/unified-loader/unixpeer"
)

func TestAgentClientRejectsUnrelatedOrIncompleteReadiness(t *testing.T) {
	for _, fault := range []string{"", "nonce", "peer-pid", "boot", "runtime", "revision", "module", "idle"} {
		t.Run(fault, func(t *testing.T) {
			directory, err := os.MkdirTemp("", "vta-")
			if err != nil {
				t.Fatal(err)
			}
			defer os.RemoveAll(directory)
			path := filepath.Join(directory, "s")
			listener, err := net.ListenUnix("unix", &net.UnixAddr{Name: path, Net: "unix"})
			if err != nil {
				t.Fatal(err)
			}
			defer listener.Close()
			target := Target{BootID: "boot", PID: 123, UID: 1000, StartTicks: "456", NativeID: "native", RuntimeID: "runtime"}
			client, err := NewClient(target)
			if err != nil {
				t.Fatal(err)
			}
			client.address = path
			client.peerCredentials = func(*net.UnixConn) (unixpeer.Credentials, error) {
				pid := target.PID
				if fault == "peer-pid" {
					pid++
				}
				return unixpeer.Credentials{PID: pid, UID: target.UID}, nil
			}
			finished := make(chan struct{})
			go func() {
				defer close(finished)
				connection, err := listener.AcceptUnix()
				if err != nil {
					return
				}
				defer connection.Close()
				frame, err := readFrame(connection)
				if err != nil {
					return
				}
				status := Status{Schema: 1, Target: target, Revision: 2, State: "ready", Modules: map[string]string{"phone": "active"}}
				envelope := response{Schema: 1, WorkerIdle: true, Status: &status}
				switch fault {
				case "nonce":
					frame.token[0] ^= 1
				case "boot":
					status.BootID = "old"
				case "runtime":
					status.RuntimeID = "old"
				case "revision":
					status.Revision--
				case "module":
					status.Modules["phone"] = "failed"
				case "idle":
					envelope.WorkerIdle = false
				}
				frame.command |= 0x80
				frame.payload, _ = json.Marshal(envelope)
				writeFrame(connection, frame)
			}()
			ctx, cancel := context.WithTimeout(context.Background(), time.Second)
			defer cancel()
			_, err = client.Apply(ctx, Plan{Schema: 1, RuntimeID: target.RuntimeID, Revision: 2, Modules: []string{"phone"}, Configuration: map[string]string{}})
			if (err == nil) != (fault == "") {
				t.Fatalf("fault=%s error=%v", fault, err)
			}
			<-finished
		})
	}
}
