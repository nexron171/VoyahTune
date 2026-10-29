package unixpeer

import (
	"net"
	"syscall"
)

func Read(connection *net.UnixConn) (Credentials, error) {
	channel, err := connection.SyscallConn()
	if err != nil {
		return Credentials{}, err
	}
	var credentials Credentials
	var credentialError error
	err = channel.Control(func(fd uintptr) {
		value, err := syscall.GetsockoptUcred(int(fd), syscall.SOL_SOCKET, syscall.SO_PEERCRED)
		credentialError = err
		if err == nil {
			credentials = Credentials{PID: int(value.Pid), UID: value.Uid, GID: value.Gid}
		}
	})
	if err != nil {
		return Credentials{}, err
	}
	return credentials, credentialError
}
