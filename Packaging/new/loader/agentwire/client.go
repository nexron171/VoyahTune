package agentwire

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"
	"time"

	"voyahtune/unified-loader/unixpeer"
)

const maximumFrame = 1024 * 1024
const statusCommand byte = 1
const applyCommand byte = 2
const quiesceCommand byte = 3

var ErrRuntimeChanged = errors.New("resident runtime differs; process restart required")

type Target struct {
	BootID     string `json:"bootId"`
	PID        int    `json:"pid"`
	UID        uint32 `json:"uid"`
	StartTicks string `json:"startTicks"`
	NativeID   string `json:"nativeId"`
	RuntimeID  string `json:"runtimeId"`
}

type Plan struct {
	Schema        int               `json:"schema"`
	RuntimeID     string            `json:"runtimeId"`
	Revision      int64             `json:"revision"`
	Modules       []string          `json:"modules"`
	Configuration map[string]string `json:"configuration"`
}

type Status struct {
	Schema int `json:"schema"`
	Target
	Revision int64             `json:"revision"`
	State    string            `json:"state"`
	Modules  map[string]string `json:"modules"`
	Error    string            `json:"error,omitempty"`
}

type response struct {
	Schema     int     `json:"schema"`
	WorkerIdle bool    `json:"workerIdle"`
	Status     *Status `json:"status"`
	Error      string  `json:"error,omitempty"`
}

type Client struct {
	target          Target
	address         string
	peerCredentials func(*net.UnixConn) (unixpeer.Credentials, error)
}

func NewClient(target Target) (*Client, error) {
	if target.PID <= 0 || target.BootID == "" || len(target.BootID) >= 64 || target.NativeID == "" || len(target.NativeID) >= 80 || target.RuntimeID == "" || len(target.RuntimeID) >= 80 {
		return nil, errors.New("complete target/runtime identity required")
	}
	if _, err := strconv.ParseUint(target.StartTicks, 10, 64); err != nil || len(target.StartTicks) >= 32 {
		return nil, errors.New("invalid target start ticks")
	}
	return &Client{target: target, address: fmt.Sprintf("@voyahtune.agent.v1.%d.%s", target.PID, target.StartTicks), peerCredentials: unixpeer.Read}, nil
}

func (client *Client) Status(ctx context.Context) (Status, error) {
	return client.exchange(ctx, statusCommand, nil)
}

func (client *Client) Apply(ctx context.Context, plan Plan) (Status, error) {
	if err := plan.Validate(client.target.RuntimeID); err != nil {
		return Status{}, err
	}
	if plan.Modules == nil {
		plan.Modules = []string{}
	}
	if plan.Configuration == nil {
		plan.Configuration = map[string]string{}
	}
	payload, err := json.Marshal(plan)
	if err != nil {
		return Status{}, err
	}
	status, err := client.exchange(ctx, applyCommand, payload)
	if err != nil {
		return status, err
	}
	if status.Error != "" || status.State != "ready" || status.Revision != plan.Revision || len(status.Modules) != len(plan.Modules) {
		return status, fmt.Errorf("agent did not confirm the complete module plan: state=%s error=%s", status.State, status.Error)
	}
	for _, id := range plan.Modules {
		if status.Modules[id] != "active" {
			return status, fmt.Errorf("module %s is not active", id)
		}
	}
	return status, nil
}

func (plan Plan) Validate(runtimeID string) error {
	if plan.Schema != 1 || plan.RuntimeID != runtimeID || plan.Revision < 0 || len(plan.Modules) > 64 {
		return errors.New("invalid module plan")
	}
	seen := map[string]bool{}
	for _, id := range plan.Modules {
		if id == "" || seen[id] {
			return errors.New("invalid or duplicate module ID")
		}
		seen[id] = true
	}
	return nil
}

func (client *Client) Quiesce(ctx context.Context) (Status, error) {
	status, err := client.exchange(ctx, quiesceCommand, nil)
	if err == nil && (status.Error != "" || status.State != "quiesced" || len(status.Modules) != 0) {
		err = errors.New("agent cleanup has not quiesced")
	}
	return status, err
}

func (client *Client) exchange(ctx context.Context, command byte, payload []byte) (Status, error) {
	if len(payload)+17 > maximumFrame {
		return Status{}, errors.New("agent plan exceeds frame limit")
	}
	ctx, cancel := context.WithTimeout(ctx, 60*time.Second)
	defer cancel()
	connection, err := (&net.Dialer{}).DialContext(ctx, "unix", client.address)
	if err != nil {
		return Status{}, err
	}
	defer connection.Close()
	stopCancellation := context.AfterFunc(ctx, func() { connection.Close() })
	defer stopCancellation()
	deadline, _ := ctx.Deadline()
	connection.SetDeadline(deadline)
	peer, err := client.peerCredentials(connection.(*net.UnixConn))
	if err != nil || peer.PID != client.target.PID || peer.UID != client.target.UID {
		return Status{}, errors.New("agent endpoint belongs to another process")
	}
	request := frame{command: command, payload: payload}
	if _, err := rand.Read(request.token[:]); err != nil {
		return Status{}, err
	}
	if err := writeFrame(connection, request); err != nil {
		return Status{}, err
	}
	reply, err := readFrame(connection)
	if err != nil {
		return Status{}, err
	}
	if reply.command != command|0x80 || reply.token != request.token {
		return Status{}, errors.New("stale agent response")
	}
	var result response
	decoder := json.NewDecoder(bytes.NewReader(reply.payload))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&result); err != nil {
		return Status{}, err
	}
	if err := decoder.Decode(new(any)); err != io.EOF {
		return Status{}, errors.New("unexpected agent response data")
	}
	if result.Schema != 1 || !result.WorkerIdle || result.Status == nil || result.Status.Schema != 1 || result.Status.Modules == nil {
		return Status{}, errors.New("incomplete agent acknowledgement")
	}
	status := *result.Status
	if status.BootID != client.target.BootID || status.PID != client.target.PID || status.UID != client.target.UID || status.StartTicks != client.target.StartTicks {
		return status, errors.New("agent process identity changed")
	}
	if status.NativeID != client.target.NativeID || status.RuntimeID != client.target.RuntimeID {
		return status, ErrRuntimeChanged
	}
	if result.Error != "" {
		return status, errors.New(result.Error)
	}
	if err := ctx.Err(); err != nil {
		return status, err
	}
	return status, nil
}

type frame struct {
	command byte
	token   [16]byte
	payload []byte
}

func readFrame(reader io.Reader) (frame, error) {
	var header [8]byte
	if _, err := io.ReadFull(reader, header[:]); err != nil {
		return frame{}, err
	}
	length := binary.LittleEndian.Uint32(header[4:])
	if string(header[:4]) != "VTA1" || length < 17 || length > maximumFrame {
		return frame{}, errors.New("invalid agent frame")
	}
	body := make([]byte, length)
	if _, err := io.ReadFull(reader, body); err != nil {
		return frame{}, err
	}
	value := frame{command: body[0], payload: body[17:]}
	copy(value.token[:], body[1:17])
	return value, nil
}

func writeFrame(writer io.Writer, value frame) error {
	length := len(value.payload) + 17
	if length > maximumFrame {
		return errors.New("agent frame exceeds limit")
	}
	data := make([]byte, 8+length)
	copy(data[:4], "VTA1")
	binary.LittleEndian.PutUint32(data[4:8], uint32(length))
	data[8] = value.command
	copy(data[9:25], value.token[:])
	copy(data[25:], value.payload)
	for len(data) > 0 {
		written, err := writer.Write(data)
		if err != nil {
			return err
		}
		if written <= 0 {
			return io.ErrShortWrite
		}
		data = data[written:]
	}
	return nil
}
