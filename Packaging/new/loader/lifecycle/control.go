package lifecycle

import (
	"context"
	"errors"
	"time"
)

const ControlSchema = 1

type ControlIdentity struct {
	BootID     string `json:"bootId"`
	PID        int    `json:"pid"`
	StartTicks string `json:"startTicks"`
}

type ControlRequest struct {
	Schema        int             `json:"schema"`
	RequestID     string          `json:"requestId"`
	Identity      ControlIdentity `json:"identity"`
	Command       string          `json:"command"`
	TimeoutMillis int             `json:"timeoutMillis"`
}

type ControlResponse struct {
	Schema    int             `json:"schema"`
	RequestID string          `json:"requestId"`
	Identity  ControlIdentity `json:"identity"`
	State     State           `json:"state"`
	Error     string          `json:"error,omitempty"`
}

type Control struct {
	identity    ControlIdentity
	coordinator Quiescer
}

type Quiescer interface {
	Quiesce(context.Context) error
	Snapshot() State
}

func NewControl(identity ControlIdentity, coordinator Quiescer) *Control {
	return &Control{identity: identity, coordinator: coordinator}
}

func validRequestID(value string) bool {
	if len(value) < 8 || len(value) > 128 {
		return false
	}
	for _, character := range value {
		if character != '-' && character != '_' &&
			!(character >= 'a' && character <= 'z') &&
			!(character >= 'A' && character <= 'Z') &&
			!(character >= '0' && character <= '9') {
			return false
		}
	}
	return true
}

func (control *Control) Execute(ctx context.Context, peerUID uint32, request ControlRequest) ControlResponse {
	response := ControlResponse{Schema: ControlSchema, RequestID: request.RequestID, Identity: control.identity}
	switch {
	case peerUID != 0:
		response.Error = "unauthorized"
	case request.Schema != ControlSchema:
		response.Error = "unsupported-schema"
	case !validRequestID(request.RequestID):
		response.Error = "invalid-request-id"
	case control.identity.BootID == "" || control.identity.PID <= 0 || control.identity.StartTicks == "" || request.Identity != control.identity:
		response.Error = "identity-mismatch"
	case request.Command != "status" && request.Command != "quiesce":
		response.Error = "unsupported-command"
	case request.TimeoutMillis < 1 || request.TimeoutMillis > 60000:
		response.Error = "invalid-deadline"
	default:
		if request.Command == "quiesce" {
			deadline, cancel := context.WithTimeout(ctx, time.Duration(request.TimeoutMillis)*time.Millisecond)
			err := control.coordinator.Quiesce(deadline)
			cancel()
			if errors.Is(err, context.DeadlineExceeded) || errors.Is(err, context.Canceled) {
				response.Error = "deadline-exceeded"
			}
		}
	}
	response.State = control.coordinator.Snapshot()
	return response
}
