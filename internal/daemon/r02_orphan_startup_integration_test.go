package daemon

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"github.com/Mikeyphw/xbsctl/internal/config"
	"github.com/Mikeyphw/xbsctl/internal/control"
)

// A lost .work path does not erase its independently durable applying phase.
// No active profile should be replayed and no privileged restoration should
// happen before a human resolves the command's unknown outcome.
func TestR02AuditedOrphanApplyingPhaseBlocksDaemonStartup(t *testing.T) {
	_, ctl := r02bEnv(t)
	req, err := control.QueueRequest(ctl, "start", "manual")
	if err != nil {
		t.Fatal(err)
	}
	got, work, err := control.NextRequest(ctl)
	if err != nil {
		t.Fatal(err)
	}
	if err := control.MarkApplying(ctl, got); err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(work); err != nil {
		t.Fatal(err)
	}
	var stdout bytes.Buffer
	if code := Main([]string{"--once", "--json"}, &stdout, &stdout); code == 0 {
		t.Fatalf("orphan applying phase bypassed recovery: %s", stdout.String())
	}
	ack, exists := control.ReadAck(ctl, req.ID)
	if !exists || ack.State != "unknown" {
		t.Fatalf("startup did not retain unknown receipt: %+v %v", ack, exists)
	}
	data, err := os.ReadFile(config.ResolvePaths().Status)
	if err != nil {
		t.Fatal(err)
	}
	var status map[string]any
	if err := json.Unmarshal(data, &status); err != nil {
		t.Fatal(err)
	}
	if status["mode"] != "recovery_required" {
		t.Fatalf("daemon did not refuse startup: %s", data)
	}
	if _, err := os.Stat(filepath.Join(ctl+".phase", req.ID+".applying")); err != nil {
		t.Fatalf("lost applying evidence: %v", err)
	}
}
