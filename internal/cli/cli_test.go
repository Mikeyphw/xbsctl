package cli

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Mikeyphw/xbsctl/internal/config"
	"github.com/Mikeyphw/xbsctl/internal/control"
)

func isolatedEnv(t *testing.T) string {
	t.Helper()
	root := t.TempDir()
	t.Setenv("XBS_MODULE_DIR", filepath.Join(root, "module"))
	t.Setenv("WG_ANDROID_SHARED_ROOT", filepath.Join(root, "shared"))
	t.Setenv("XBS_EXTERNAL_ROOT", filepath.Join(root, "shared", ".config", "xbs"))
	t.Setenv("XBS_RUNTIME_DIR", filepath.Join(root, "runtime"))
	return root
}

func TestHelpAndVersionDoNotRequireRuntime(t *testing.T) {
	var out, err bytes.Buffer
	if code := Main([]string{"--help"}, &out, &err); code != 0 {
		t.Fatalf("help exit = %d, stderr=%s", code, err.String())
	}
	if !strings.Contains(out.String(), "xbs Go backend") {
		t.Fatalf("help did not describe Go backend: %s", out.String())
	}
	out.Reset()
	err.Reset()
	if code := Main([]string{"--version"}, &out, &err); code != 0 {
		t.Fatalf("version exit = %d", code)
	}
	if !strings.Contains(out.String(), "XGO13") {
		t.Fatalf("version missing phase: %s", out.String())
	}
}

func TestContractsCheckAllowed(t *testing.T) {
	var out, err bytes.Buffer
	if code := Main([]string{"contracts", "check"}, &out, &err); code != 0 {
		t.Fatalf("contracts check exit = %d, stdout=%s stderr=%s", code, out.String(), err.String())
	}
	if !strings.Contains(out.String(), "cli=17") || !strings.Contains(out.String(), "bridge=39") {
		t.Fatalf("unexpected summary: %s", out.String())
	}
}

func TestStatusNowImplementedInJSON(t *testing.T) {
	isolatedEnv(t)
	var out, err bytes.Buffer
	code := Main([]string{"--json", "status"}, &out, &err)
	if code != 0 {
		t.Fatalf("status exit = %d stderr=%s stdout=%s", code, err.String(), out.String())
	}
	var payload map[string]any
	if err := json.Unmarshal(out.Bytes(), &payload); err != nil {
		t.Fatalf("invalid JSON: %v\n%s", err, out.String())
	}
	if payload["ok"] != true || payload["action"] != "status" {
		t.Fatalf("unexpected payload: %#v", payload)
	}
}

func TestConfigSetGetValidateRoundTrip(t *testing.T) {
	isolatedEnv(t)
	var out, err bytes.Buffer
	if code := Main([]string{"--json", "init"}, &out, &err); code != 0 {
		t.Fatalf("init exit=%d stderr=%s stdout=%s", code, err.String(), out.String())
	}
	out.Reset()
	err.Reset()
	if code := Main([]string{"--json", "config", "set", "header.delay", "7"}, &out, &err); code != 0 {
		t.Fatalf("set exit=%d stderr=%s stdout=%s", code, err.String(), out.String())
	}
	out.Reset()
	err.Reset()
	if code := Main([]string{"--json", "config", "get", "header.delay"}, &out, &err); code != 0 {
		t.Fatalf("get exit=%d stderr=%s stdout=%s", code, err.String(), out.String())
	}
	var payload map[string]any
	if err := json.Unmarshal(out.Bytes(), &payload); err != nil {
		t.Fatal(err)
	}
	data := payload["data"].(map[string]any)
	if data["value"].(float64) != 7 {
		t.Fatalf("delay not round-tripped: %#v", data["value"])
	}
}

func TestRuntimeAndEventCommandsDelegateToGoController(t *testing.T) {
	root := isolatedEnv(t)
	var out, err bytes.Buffer
	if code := Main([]string{"--json", "init"}, &out, &err); code != 0 {
		t.Fatalf("init exit=%d stderr=%s stdout=%s", code, err.String(), out.String())
	}
	out.Reset()
	err.Reset()
	code := Main([]string{"--json", "runtime", "reload"}, &out, &err)
	if code != 0 {
		t.Fatalf("runtime reload exit = %d stdout=%s stderr=%s", code, out.String(), err.String())
	}
	var payload map[string]any
	if err := json.Unmarshal(out.Bytes(), &payload); err != nil {
		t.Fatal(err)
	}
	if payload["status"] != "pending" || payload["requested_action"] != "reload" {
		t.Fatalf("unexpected runtime payload: %#v", payload)
	}
	ctl := filepath.Join(root, "runtime", "xbs.ctl")
	req, _, qerr := control.NextRequest(ctl)
	if qerr != nil || req.Command != "reload" {
		t.Fatalf("reload request=%+v err=%v", req, qerr)
	}
	out.Reset()
	err.Reset()
	code = Main([]string{"--json", "event", "run", "manual"}, &out, &err)
	if code != 0 {
		t.Fatalf("event run exit = %d stdout=%s stderr=%s", code, out.String(), err.String())
	}
	req, _, qerr = control.NextRequest(ctl)
	if qerr != nil || req.Command != "reload" { // first request remains until daemon acknowledges it
		t.Fatalf("queued request order lost: %+v err=%v", req, qerr)
	}
}

func TestCompletionAndManpageImplemented(t *testing.T) {
	var out, err bytes.Buffer
	if code := Main([]string{"completion", "zsh"}, &out, &err); code != 0 {
		t.Fatalf("completion exit=%d stderr=%s", code, err.String())
	}
	if !strings.Contains(out.String(), "#compdef") {
		t.Fatalf("completion output missing zsh header: %s", out.String())
	}
	out.Reset()
	err.Reset()
	if code := Main([]string{"--json", "manpage", "show"}, &out, &err); code != 0 {
		t.Fatalf("manpage exit=%d stderr=%s stdout=%s", code, err.String(), out.String())
	}
}

func TestCapabilitiesImplementedInJSON(t *testing.T) {
	root := isolatedEnv(t)
	module := filepath.Join(root, "module")
	if err := os.MkdirAll(module, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(module, "module.prop"), []byte("id=XtremeBS\nversion=v2.1.6\nversionCode=216\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(module, "schema.json"), []byte(`{"properties":{"events":{"additionalProperties":{"properties":{"allowlist":{},"denylist":{}}}}}}`), 0o644); err != nil {
		t.Fatal(err)
	}
	var out, errBuf bytes.Buffer
	code := Main([]string{"--json", "capabilities"}, &out, &errBuf)
	if code != 0 {
		t.Fatalf("capabilities exit=%d stderr=%s stdout=%s", code, errBuf.String(), out.String())
	}
	var payload map[string]any
	if err := json.Unmarshal(out.Bytes(), &payload); err != nil {
		t.Fatal(err)
	}
	compat := payload["data"].(map[string]any)["compatibility"].(map[string]any)
	if compat["contract_version"] != "1.1.0" {
		t.Fatalf("unexpected compatibility payload: %#v", compat)
	}
	if len(compat["commands"].([]any)) != 39 {
		t.Fatalf("command count mismatch: %#v", compat["commands"])
	}
}

func TestEventBridgePublishAndVerifyCommands(t *testing.T) {
	root := isolatedEnv(t)
	bridgeRoot := filepath.Join(root, "bridge")
	runtime := filepath.Join(root, "runtime")
	status := filepath.Join(runtime, "status.json")
	event := filepath.Join(runtime, "event.json")
	if err := os.MkdirAll(runtime, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(status, []byte(`{"mode":"manual","active_profiles":[]}`), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(event, []byte(`{"active":["manual"],"inactive":[]}`), 0o600); err != nil {
		t.Fatal(err)
	}
	key := filepath.Join(bridgeRoot, "event_bridge_hmac_v1.key")
	envelope := filepath.Join(runtime, "event_bridge_v1.json")
	seq := filepath.Join(runtime, "event_bridge_v1.seq")
	var out, errBuf bytes.Buffer
	code := Main([]string{"--json", "event-bridge", "publish", "--event", event, "--status", status, "--key", key, "--envelope", envelope, "--sequence", seq}, &out, &errBuf)
	if code != 0 {
		t.Fatalf("publish exit=%d stderr=%s stdout=%s", code, errBuf.String(), out.String())
	}
	out.Reset()
	errBuf.Reset()
	code = Main([]string{"--json", "event-bridge", "verify", "--key", key, "--envelope", envelope}, &out, &errBuf)
	if code != 0 {
		t.Fatalf("verify exit=%d stderr=%s stdout=%s", code, errBuf.String(), out.String())
	}
	var payload map[string]any
	if err := json.Unmarshal(out.Bytes(), &payload); err != nil {
		t.Fatal(err)
	}
	bridge := payload["data"].(map[string]any)["bridge"].(map[string]any)
	if bridge["authenticated"] != true || bridge["sequence"].(float64) != 1 {
		t.Fatalf("unexpected bridge payload: %#v", bridge)
	}
}

func TestPrimaryCLIQueuesConfiguredCustomEvent(t *testing.T) {
	root := isolatedEnv(t)
	cfg := config.Default()
	cfg["events"].(map[string]any)["gaming_custom"] = map[string]any{}
	if _, err := config.Save(cfg); err != nil {
		t.Fatal(err)
	}
	var out, errOut bytes.Buffer
	if code := Main([]string{"--json", "event", "run", "gaming_custom"}, &out, &errOut); code != 0 {
		t.Fatalf("code=%d out=%s err=%s", code, out.String(), errOut.String())
	}
	req, _, err := control.NextRequest(filepath.Join(root, "runtime", "xbs.ctl"))
	if err != nil {
		t.Fatal(err)
	}
	if req.Command != "start" || req.Event != "gaming_custom" {
		t.Fatalf("request=%+v", req)
	}
}
