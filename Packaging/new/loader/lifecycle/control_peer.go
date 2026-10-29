package lifecycle

import (
	"net"

	"voyahtune/unified-loader/unixpeer"
)

func readPeerUID(connection *net.UnixConn) (uint32, error) {
	credentials, err := unixpeer.Read(connection)
	return credentials.UID, err
}
