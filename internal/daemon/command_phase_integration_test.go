package daemon

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Mikeyphw/xbsctl/internal/config"
	"github.com/Mikeyphw/xbsctl/internal/control"
	"github.com/Mikeyphw/xbsctl/internal/tuning"
)

func r02bEnv(t *testing.T) (map[string]any, string) {
	t.Helper()
	root := t.TempDir()
	t.Setenv("XBS_EXTERNAL_ROOT", filepath.Join(root, "external"))
	t.Setenv("XBS_RUNTIME_DIR", filepath.Join(root, "runtime"))
	t.Setenv("XBS_EVENT_BRIDGE_ROOT", filepath.Join(root, "private"))
	cfg := config.Default()
	if _, err := config.Save(cfg); err != nil {
		t.Fatal(err)
	}
	return cfg, ctlPath(cfg)
}

// A crash after applying must block daemon startup BEFORE pending effect WAL
// restoration or active profile replay. Manual reconciliation is required.
func TestR02BStartupUnknownCommandBlocksAutoReplayAndPreservesWAL(t *testing.T) {
	cfg, ctl := r02bEnv(t)
	existing := &state{Mode: "normal", Active: map[string]bool{"manual": true}, EffectResults: map[string]any{}}
	if err := persistRuntimeSnapshot(cfg, existing, map[string]string{"wifi-enabled": "true"}); err != nil {
		t.Fatal(err)
	}
	before, err := os.ReadFile(runtimeStatePath())
	if err != nil {
		t.Fatal(err)
	}
	req, err := control.QueueRequest(ctl, "start", "manual")
	if err != nil {
		t.Fatal(err)
	}
	got, work, err := control.NextRequest(ctl)
	if err != nil || got != req {
		t.Fatalf("claim %+v %v", got, err)
	}
	if err := control.MarkApplying(ctl, got); err != nil {
		t.Fatal(err)
	}
	var out bytes.Buffer
	if code := Main([]string{"--json", "--once"}, &out, &out); code == 0 {
		t.Fatalf("unknown command auto-replayed: %s", out.String())
	}
	after, err := os.ReadFile(runtimeStatePath())
	if err != nil || !bytes.Equal(before, after) {
		t.Fatalf("WAL changed before reconciliation: err=%v", err)
	}
	status, err := os.ReadFile(config.ResolvePaths().Status)
	if err != nil {
		t.Fatal(err)
	}
	var payload struct {
		Mode string `json:"mode"`
	}
	if err := json.Unmarshal(status, &payload); err != nil || payload.Mode != "recovery_required" {
		t.Fatalf("recovery status %s %v", status, err)
	}
	ack, ok := control.ReadAck(ctl, req.ID)
	if !ok || ack.State != "unknown" {
		t.Fatalf("receipt %+v %v", ack, ok)
	}
	if _, err := os.Stat(work); err != nil {
		t.Fatalf("lost unknown .work evidence %v", err)
	}
}

func TestR02BNormalDaemonRequestWritesApplyingBeforeCompletion(t *testing.T) {
	cfg, ctl := r02bEnv(t)
	s := &state{Mode: "normal", Active: map[string]bool{}, EffectResults: map[string]any{}}
	engine := tuning.New(nil)
	bindEngineJournal(engine, cfg, s)
	req, err := control.QueueRequest(ctl, "start", "manual")
	if err != nil {
		t.Fatal(err)
	}
	change, err := processCtl(engine, cfg, s, false)
	if err != nil || change == nil || !s.Active["manual"] {
		t.Fatalf("command did not complete: change=%v err=%v", change, err)
	}
	ack, ok := control.ReadAck(ctl, req.ID)
	if !ok || ack.State != "applied" {
		t.Fatalf("terminal ack %+v %v", ack, ok)
	}
	phase, err := os.ReadFile(filepath.Join(ctl+".phase", req.ID+".applying"))
	if err != nil || !strings.Contains(string(phase), `"phase":"applying"`) {
		t.Fatalf("no applying barrier %q %v", phase, err)
	}
	pending, err := control.PendingUnknown(ctl)
	if err != nil || len(pending) != 0 {
		t.Fatalf("completed request marked unknown %v %v", pending, err)
	}
}
