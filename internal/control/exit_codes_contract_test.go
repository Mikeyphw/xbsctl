package control

import (
	"encoding/json"
	"strings"
	"testing"
)

// Error contracts are consumed by Termux scripts, Tasker and app callers.
// A machine-readable diagnostic must not silently rewrite the requested code.
func TestEmitErrorPreservesRequestedExitCodes(t *testing.T) {
	for _, want := range []int{ExitMissingConfig, ExitParseError, ExitInvalidEvent, ExitInvalidArgs, ExitRuntime} {
		var stdout strings.Builder
		got := emitError(&stdout, options{jsonMode: true}, "synthetic failure", want)
		if got != want {
			t.Errorf("want exit=%d got=%d", want, got)
		}
		var msg map[string]any
		if err := json.Unmarshal([]byte(stdout.String()), &msg); err != nil {
			t.Fatal(err)
		}
		if msg["status"] != "error" || msg["message"] != "synthetic failure" {
			t.Errorf("unexpected machine-readable error: %#v", msg)
		}
	}
}
