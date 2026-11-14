package cli

import (
	"bytes"
	"encoding/json"
	"path/filepath"
	"testing"

	"github.com/Mikeyphw/xbsctl/internal/config"
	"github.com/Mikeyphw/xbsctl/internal/control"
)

// r09cCLI executes the actual Go CLI; it never invokes an Android root service.
func r09cCLI(args ...string) (int, map[string]any, string, error) {
	var out, errOut bytes.Buffer
	code := Main(append([]string{"--json"}, args...), &out, &errOut)
	raw := out.Bytes()
	if len(raw) == 0 {
		raw = errOut.Bytes()
	}
	payload := map[string]any{}
	if err := json.Unmarshal(raw, &payload); err != nil {
		return code, nil, string(raw), err
	}
	return code, payload, string(raw), nil
}
func r09cContract(t *testing.T, args []string, code int, action string, ok bool) {
	t.Helper()
	got, payload, raw, err := r09cCLI(args...)
	if err != nil {
		t.Fatalf("args=%v invalid JSON %q: %v", args, raw, err)
	}
	if got != code || payload["ok"] != ok || payload["action"] != action || payload["app"] != "xbs" || payload["exit_code"] != float64(code) {
		t.Fatalf("golden exit/identity drift %v code=%d payload=%v", args, got, payload)
	}
	if _, exists := payload["schema_version"].(string); !exists {
		t.Fatalf("missing schema version: %s", raw)
	}
	if ok {
		if payload["error"] != nil {
			t.Fatalf("success with error envelope: %s", raw)
		}
	} else {
		errorPart, exists := payload["error"].(map[string]any)
		if !exists || errorPart["message"] == "" {
			t.Fatalf("refusal missing error envelope: %s", raw)
		}
	}
}
func TestR09CCLIGoldenSuccess(t *testing.T) {
	isolatedEnv(t)
	for _, tc := range []struct {
		name   string
		args   []string
		action string
	}{
		{"status", []string{"status"}, "status"},
		{"paths", []string{"paths"}, "paths"},
		{"manpage", []string{"manpage", "show"}, "manpage.show"},
		{"completion-install", []string{"completion", "install"}, "completion.install"},
		{"schema-config", []string{"schema", "config"}, "schema.config"},
	} {
		t.Run(tc.name, func(t *testing.T) { r09cContract(t, tc.args, 0, tc.action, true) })
	}
}
func TestR09CCLIGoldenUsageAndExitCodes(t *testing.T) {
	isolatedEnv(t)
	for _, tc := range []struct {
		name   string
		args   []string
		action string
	}{
		{"unknown-command", []string{"definitely-invalid"}, "definitely-invalid"},
		{"missing-event-subcommand", []string{"event"}, "event"},
		{"missing-config-subcommand", []string{"config"}, "config"},
		{"invalid-safe-switch", []string{"runtime", "safe", "maybe"}, "runtime.safe"},
		{"unknown-schema", []string{"schema", "invalid"}, "schema"},
		{"invalid-completion", []string{"completion", "unknown"}, "completion"},
		{"invalid-manpage", []string{"manpage", "remove"}, "manpage"},
	} {
		t.Run(tc.name, func(t *testing.T) { r09cContract(t, tc.args, 2, tc.action, false) })
	}
}
func TestR09CCLIGoldenConfigValidation(t *testing.T) {
	isolatedEnv(t)
	valid := config.Default()
	if errs := config.Validate(valid); len(errs) != 0 {
		t.Fatalf("default config invalid: %v", errs)
	}

	// A syntactically valid object with a null critical path must fail, never coerce to "<nil>".
	payload := []byte(`{"header":{"version":2,"delay":3,"log_level":3,"log_file":null,"ctl_file":"x","lock_file":"x","essentials_allowlist":"x"},"events":{"template":{}}}`)

	code, data, raw, err := r09cCLI("validate", string(payload))
	if err != nil || code != 1 || data["action"] != "validate" || data["ok"] != false || data["exit_code"] != float64(1) {
		t.Fatalf("invalid config accepted: %d %s %v", code, raw, err)
	}
	detail, ok := data["data"].(map[string]any)
	if !ok || len(detail["errors"].([]any)) == 0 {
		t.Fatalf("missing specific validation errors: %s", raw)
	}
}
func TestR09CCLIGoldenCustomEventAuthorization(t *testing.T) {
	root := isolatedEnv(t)
	cfg := config.Default()
	cfg["events"].(map[string]any)["custom_event"] = map[string]any{}
	if _, err := config.Save(cfg); err != nil {
		t.Fatal(err)
	}
	// Invalid names must be rejected before queue files are created.
	r09cContract(t, []string{"event", "run", "../evil"}, 2, "event.run", false)
	r09cContract(t, []string{"event", "run", "not-configured"}, 2, "event.run", false)
	ctl := filepath.Join(root, "runtime", "xbs.ctl")
	if _, path, err := control.NextRequest(ctl); err != nil || path != "" {
		t.Fatalf("invalid event published: %q err=%v", path, err)
	}
	code, data, raw, err := r09cCLI("event", "run", "custom_event")
	if err != nil || code != 0 || data["ok"] != true {
		t.Fatalf("configured event refused %d %s %v", code, raw, err)
	}
	req, _, qerr := control.NextRequest(ctl)
	if qerr != nil || req.Command != "start" || req.Event != "custom_event" {
		t.Fatalf("wrong durable event: %+v %v", req, qerr)
	}
}
