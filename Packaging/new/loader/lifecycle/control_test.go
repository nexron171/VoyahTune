package lifecycle

import (
	"context"
	"testing"
	"time"
)

func TestControlRejectsUnauthenticatedRequestsBeforeQuiesce(t *testing.T) {
	identity := ControlIdentity{BootID: "boot", PID: 123, StartTicks: "456"}
	tests := []struct {
		name   string
		uid    uint32
		mutate func(*ControlRequest)
	}{
		{"uid", 10001, func(*ControlRequest) {}},
		{"boot", 0, func(request *ControlRequest) { request.Identity.BootID = "old" }},
		{"loader", 0, func(request *ControlRequest) { request.Identity.StartTicks = "old" }},
		{"schema", 0, func(request *ControlRequest) { request.Schema = 99 }},
		{"command", 0, func(request *ControlRequest) { request.Command = "resume" }},
		{"request-id", 0, func(request *ControlRequest) { request.RequestID = "../unsafe" }},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			coordinator := &Coordinator{}
			control := NewControl(identity, coordinator)
			request := ControlRequest{Schema: 1, RequestID: "request-123", Identity: identity, Command: "quiesce", TimeoutMillis: 100}
			test.mutate(&request)
			response := control.Execute(context.Background(), test.uid, request)
			if response.Error == "" || coordinator.Snapshot().Phase != Running {
				t.Fatalf("rejected request changed admission: response=%+v state=%+v", response, coordinator.Snapshot())
			}
		})
	}
}

func TestControlDeadlineNeverReopensAdmission(t *testing.T) {
	identity := ControlIdentity{BootID: "boot", PID: 123, StartTicks: "456"}
	coordinator := &Coordinator{}
	operation, accepted := coordinator.Begin()
	if !accepted {
		t.Fatal("initial operation rejected")
	}
	control := NewControl(identity, coordinator)
	request := ControlRequest{Schema: 1, RequestID: "request-123", Identity: identity, Command: "quiesce", TimeoutMillis: 5}
	response := control.Execute(context.Background(), 0, request)
	if response.Error != "deadline-exceeded" || response.State.Phase != Quiescing || response.State.InFlight != 1 {
		t.Fatalf("unexpected deadline result: %+v", response)
	}
	if _, accepted := coordinator.Begin(); accepted {
		t.Fatal("deadline reopened admission")
	}
	operation.Complete()
	request.RequestID = "request-456"
	request.TimeoutMillis = 100
	response = control.Execute(context.Background(), 0, request)
	if response.Error != "" || response.State.Phase != Quiesced || response.Identity != identity || response.RequestID != request.RequestID {
		t.Fatalf("drained response lost correlation: %+v", response)
	}
}

func TestControlStatusDoesNotCloseAdmission(t *testing.T) {
	identity := ControlIdentity{BootID: "boot", PID: 123, StartTicks: "456"}
	coordinator := &Coordinator{}
	request := ControlRequest{Schema: 1, RequestID: "request-123", Identity: identity, Command: "status", TimeoutMillis: 100}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	response := NewControl(identity, coordinator).Execute(ctx, 0, request)
	if response.Error != "" || response.State.Phase != Running {
		t.Fatalf("unexpected status: %+v", response)
	}
	operation, accepted := coordinator.Begin()
	if !accepted {
		t.Fatal("status closed admission")
	}
	operation.Complete()
}
