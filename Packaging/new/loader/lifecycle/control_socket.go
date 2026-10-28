package lifecycle

import (
	"bufio"
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"sync"
	"time"
)

const maximumControlFrame = 4096

type ControlServer struct {
	listener *net.UnixListener
	control  *Control
	path     string
	identity os.FileInfo
	peerUID  func(*net.UnixConn) (uint32, error)
}

func ListenControl(path string, control *Control) (*ControlServer, error) {
	if !filepath.IsAbs(path) {
		return nil, errors.New("absolute control socket path required")
	}
	parent, err := os.Lstat(filepath.Dir(path))
	if err != nil || !parent.IsDir() || parent.Mode().Perm()&0022 != 0 {
		return nil, errors.New("control socket requires a directory without group/world write access")
	}
	listener, err := net.ListenUnix("unix", &net.UnixAddr{Name: path, Net: "unix"})
	if err != nil {
		return nil, err
	}
	listener.SetUnlinkOnClose(false)
	identity, err := os.Lstat(path)
	if err == nil {
		err = os.Chmod(path, 0600)
	}
	if err != nil {
		listener.Close()
		if identity != nil {
			removeOwnedSocket(path, identity)
		}
		return nil, err
	}
	return &ControlServer{listener: listener, control: control, path: path, identity: identity, peerUID: readPeerUID}, nil
}

func removeOwnedSocket(path string, identity os.FileInfo) {
	current, err := os.Lstat(path)
	if err == nil && current.Mode()&os.ModeSocket != 0 && os.SameFile(current, identity) {
		os.Remove(path)
	}
}

func (server *ControlServer) Serve(ctx context.Context) error {
	finished := make(chan struct{})
	defer close(finished)
	defer removeOwnedSocket(server.path, server.identity)
	defer server.listener.Close()
	go func() {
		select {
		case <-ctx.Done():
			server.listener.Close()
		case <-finished:
		}
	}()
	var connections sync.WaitGroup
	defer connections.Wait()
	capacity := make(chan struct{}, 8)
	for {
		connection, err := server.listener.AcceptUnix()
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			return err
		}
		select {
		case capacity <- struct{}{}:
			connections.Add(1)
			go func() {
				defer connections.Done()
				defer func() { <-capacity }()
				server.serveConnection(ctx, connection)
			}()
		default:
			connection.Close()
		}
	}
}

func (server *ControlServer) serveConnection(ctx context.Context, connection *net.UnixConn) {
	defer connection.Close()
	connection.SetDeadline(time.Now().Add(5 * time.Second))
	uid, err := server.peerUID(connection)
	if err != nil || uid != 0 {
		return
	}
	var request ControlRequest
	if readControlFrame(connection, &request) != nil {
		return
	}
	if request.TimeoutMillis >= 1 && request.TimeoutMillis <= 60000 {
		connection.SetDeadline(time.Now().Add(time.Duration(request.TimeoutMillis)*time.Millisecond + time.Second))
	}
	response := server.control.Execute(ctx, uid, request)
	json.NewEncoder(connection).Encode(response)
}

func readControlFrame(reader io.Reader, value any) error {
	line, err := bufio.NewReaderSize(reader, maximumControlFrame).ReadSlice('\n')
	if err != nil {
		return fmt.Errorf("bounded control frame required: %w", err)
	}
	decoder := json.NewDecoder(bytes.NewReader(line))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(value); err != nil {
		return err
	}
	var trailing any
	if err := decoder.Decode(&trailing); err != io.EOF {
		return errors.New("unexpected trailing control data")
	}
	return nil
}

func RequestControl(ctx context.Context, path string, identity ControlIdentity, command string) (ControlResponse, error) {
	deadline, available := ctx.Deadline()
	if !available {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, 20*time.Second)
		defer cancel()
		deadline, _ = ctx.Deadline()
	}
	timeout := time.Until(deadline).Milliseconds()
	if timeout < 1 {
		return ControlResponse{}, context.DeadlineExceeded
	}
	if timeout > 60000 {
		timeout = 60000
	}
	var token [16]byte
	if _, err := rand.Read(token[:]); err != nil {
		return ControlResponse{}, err
	}
	request := ControlRequest{Schema: ControlSchema, RequestID: hex.EncodeToString(token[:]), Identity: identity, Command: command, TimeoutMillis: int(timeout)}
	return exchangeControl(ctx, path, request, readPeerUID)
}

func exchangeControl(ctx context.Context, path string, request ControlRequest, peerUID func(*net.UnixConn) (uint32, error)) (ControlResponse, error) {
	connection, err := (&net.Dialer{}).DialContext(ctx, "unix", path)
	if err != nil {
		return ControlResponse{}, err
	}
	defer connection.Close()
	stopCancellation := context.AfterFunc(ctx, func() { connection.Close() })
	defer stopCancellation()
	if deadline, available := ctx.Deadline(); available {
		connection.SetDeadline(deadline)
	}
	uid, err := peerUID(connection.(*net.UnixConn))
	if err != nil || uid != 0 {
		return ControlResponse{}, errors.New("control endpoint is not owned by root")
	}
	if err := json.NewEncoder(connection).Encode(request); err != nil {
		return ControlResponse{}, err
	}
	var response ControlResponse
	if err := readControlFrame(connection, &response); err != nil {
		return ControlResponse{}, err
	}
	if response.Schema != ControlSchema || response.RequestID != request.RequestID || response.Identity != request.Identity {
		return response, errors.New("stale control acknowledgement")
	}
	if response.State.InFlight < 0 || (response.State.Phase == Quiesced && response.State.InFlight != 0) ||
		(response.State.Phase != Running && response.State.Phase != Quiescing && response.State.Phase != Quiesced) {
		return response, errors.New("invalid control state")
	}
	if response.Error != "" {
		return response, errors.New(response.Error)
	}
	if request.Command == "quiesce" && response.State.Phase != Quiesced {
		return response, errors.New("runtime has not quiesced")
	}
	return response, nil
}
