package injecthelper

import (
	"bytes"
	"encoding/json"
	"strings"
	"testing"

	"voyahtune/unified-loader/agentwire"
)

func TestHelperValidatesTheEntirePlanBeforeInjection(t *testing.T) {
	valid := Request{Schema: 1, RequestID: "valid-request-123", Target: agentwire.Target{BootID: "boot", PID: 41, UID: 1000,
		StartTicks: "100", NativeID: "native-code", RuntimeID: "java-code"}, LibraryPath: "/runtime/agent.so", DEXPath: "/runtime/agents.dex",
		DEXSHA256: strings.Repeat("0", 64), AcquireTimeoutMillis: 1000, StartupTimeoutMillis: 1000,
		Plan: agentwire.Plan{Schema: 1, RuntimeID: "java-code", Revision: 1, Modules: []string{"phone"}, Configuration: map[string]string{}}}
	encode := func(value Request) []byte {
		t.Helper()
		data, err := json.Marshal(value)
		if err != nil {
			t.Fatal(err)
		}
		return data
	}
	if _, err := decodeRequest(bytes.NewReader(encode(valid))); err != nil {
		t.Fatal(err)
	}
	for _, mutate := range []func(*Request){
		func(value *Request) { value.Schema = 2 },
		func(value *Request) { value.Plan.RuntimeID = "other" },
		func(value *Request) { value.Plan.Modules = []string{"phone", "phone"} },
		func(value *Request) { value.AcquireTimeoutMillis = 9223372036854775 },
		func(value *Request) { value.StartupTimeoutMillis = 0 },
		func(value *Request) { value.DEXSHA256 = "bad" },
		func(value *Request) { value.LibraryPath = "relative.so" },
		func(value *Request) { value.Target.NativeID = "with\x00nul" },
	} {
		invalid := valid
		mutate(&invalid)
		if _, err := decodeRequest(bytes.NewReader(encode(invalid))); err == nil {
			t.Fatalf("accepted invalid request %+v", invalid)
		}
	}
	for _, data := range [][]byte{append(encode(valid), []byte(" {}")...), []byte(`{"schema":1,"unknown":true}`), bytes.Repeat([]byte(" "), 1024*1024+1)} {
		if _, err := decodeRequest(bytes.NewReader(data)); err == nil {
			t.Fatal("invalid helper frame accepted")
		}
	}
}
