package injecthelper

import (
	"context"
	"os"

	"voyahtune/unified-loader/agentwire"
	"voyahtune/unified-loader/remoteattach"
)

type Agent interface {
	Status(context.Context) (agentwire.Status, error)
	Apply(context.Context, agentwire.Plan) (agentwire.Status, error)
	Quiesce(context.Context) (agentwire.Status, error)
}

type Platform interface {
	Self() (agentwire.Target, error)
	Verify(agentwire.Target) error
	ValidateFile(string) error
	Connect(agentwire.Target) (Agent, error)
	Resolve(agentwire.Target) (remoteattach.Functions, error)
	Inject(context.Context, remoteattach.Request, remoteattach.Functions, func(remoteattach.Stage)) (remoteattach.NativeResult, error)
}

type nativePlatform struct{}

func (nativePlatform) Self() (agentwire.Target, error) {
	return remoteattach.ReadTarget("/proc", os.Getpid())
}
func (nativePlatform) Verify(target agentwire.Target) error {
	return remoteattach.VerifyTarget("/proc", target)
}
func (nativePlatform) ValidateFile(path string) error { return validateRuntimeFile(path) }
func (nativePlatform) Connect(target agentwire.Target) (Agent, error) {
	return agentwire.NewClient(target)
}
func (nativePlatform) Resolve(target agentwire.Target) (remoteattach.Functions, error) {
	return remoteattach.ResolveFunctions("/proc", target.PID)
}
func (nativePlatform) Inject(ctx context.Context, request remoteattach.Request, functions remoteattach.Functions, progress func(remoteattach.Stage)) (remoteattach.NativeResult, error) {
	return remoteattach.Inject(ctx, request, functions, progress)
}

type Engine struct{ Platform Platform }
