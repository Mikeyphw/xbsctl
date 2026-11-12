package control

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Mikeyphw/xbsctl/internal/config"
)

func TestR02FullControllerRecoveryIsReadOnlyJSON(t *testing.T) {
	root := t.TempDir()
	t.Setenv("XBS_EXTERNAL_ROOT", filepath.Join(root, "external"))
	t.Setenv("XBS_RUNTIME_DIR", filepath.Join(root, "runtime"))
	cfg := config.Default()
	if _, err := config.Save(cfg); err != nil {
		t.Fatal(err)
	}
	var out strings.Builder
	if code := Main([]string{"--json", "recovery"}, &out, &out); code != 0 {
		t.Fatalf("code=%d %s", code, out.String())
	}
	var v map[string]any
	if err := json.Unmarshal([]byte(out.String()), &v); err != nil {
		t.Fatal(err)
	}
	if v["status"] != "ok" {
		t.Fatal(v)
	}
}
func TestR02FullControllerCancelAndTimeoutAreDistinct(t *testing.T) {
	root := t.TempDir()
	t.Setenv("XBS_EXTERNAL_ROOT", filepath.Join(root, "external"))
	t.Setenv("XBS_RUNTIME_DIR", filepath.Join(root, "runtime"))
	cfg := config.Default()
	if _, err := config.Save(cfg); err != nil {
		t.Fatal(err)
	}
	ctl := filepath.Join(root, "runtime", "xbs.ctl")
	req, err := QueueRequest(ctl, "start", "manual")
	if err != nil {
		t.Fatal(err)
	}
	var out strings.Builder
	if code := Main([]string{"--json", "cancel", req.ID}, &out, &out); code != 0 {
		t.Fatalf("code=%d %s", code, out.String())
	}
	if ack, ok := ReadAck(ctl, req.ID); !ok || ack.State != "failed" {
		t.Fatalf("ack %+v %v", ack, ok)
	}
	if _, err := os.Stat(filepath.Join(ctl+".d", req.ID+".cancelled")); err != nil {
		t.Fatal(err)
	}
}
