//go:build !linux

package unixpeer

import (
	"errors"
	"net"
)

func Read(connection *net.UnixConn) (Credentials, error) {
	return Credentials{}, errors.New("Unix peer credentials require Linux/Android")
}
