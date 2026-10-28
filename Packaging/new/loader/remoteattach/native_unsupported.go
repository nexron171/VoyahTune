//go:build !android || (!arm && !arm64) || !cgo

package remoteattach

import (
	"context"
	"errors"
)

func injectNative(context.Context, Request, Functions, func(Stage)) (NativeResult, error) {
	return NativeResult{}, errors.New("native injection requires Android ARM/ARM64 with CGO")
}
