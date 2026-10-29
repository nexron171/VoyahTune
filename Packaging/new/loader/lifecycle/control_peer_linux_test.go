package lifecycle

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestControlSocketUsesActualPeerCredentials(t *testing.T) {
	identity := ControlIdentity{BootID: "boot", PID: os.Getpid(), StartTicks: "456"}
	coordinator := &Coordinator{}
	path := filepath.Join(shortSocketDirectory(t), "peer.sock")
	server, err := ListenControl(path, NewControl(identity, coordinator))
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	finished := make(chan error, 1)
	go func() { finished <- server.Serve(ctx) }()
	response, err := RequestControl(ctx, path, identity, "quiesce")
	if os.Geteuid() == 0 {
		if err != nil || response.State.Phase != Quiesced {
			t.Fatalf("root peer failed: %+v %v", response, err)
		}
	} else if err == nil || coordinator.Snapshot().Phase != Running {
		t.Fatalf("unprivileged peer was accepted: %+v %v", response, err)
	}
	cancel()
	if err := <-finished; err != nil {
		t.Fatal(err)
	}
}
