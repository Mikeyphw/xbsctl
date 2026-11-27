package daemon

import (
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/Mikeyphw/xbsctl/internal/control"
)

// This test drives the real automaticCycle on the R03B queue implementation;
// it must not write a fresh automatic request after losing pending evidence.
func TestR03CMissingPendingFailsClosedInDaemon(t *testing.T) {
	cfg, s, engine, ctl := r03aSetup(t)
	s.AutoPending = &autoPending{ID: "00000000000000000001-1-000001", Command: "start", Event: "charging", QueuedAt: time.Now().Unix()}
	changed, err := automaticCycle(engine, cfg, s)
	if err == nil || !strings.Contains(err.Error(), "outcome unknown") || !changed || !s.AutoBlocked {
		t.Fatalf("lost automatic work replayed: changed=%t err=%v blocked=%t", changed, err, s.AutoBlocked)
	}
	d, e := control.InspectRequest(ctl, s.AutoPending.ID)
	if e != nil || d.State != "missing" {
		t.Fatalf("unintended replay %+v %v", d, e)
	}
}
func TestR03CExpiredPendingFailsClosedInDaemon(t *testing.T) {
	cfg, s, engine, ctl := r03aSetup(t)
	r, e := control.QueueAutomaticRequest(ctl, "start", "charging")
	if e != nil {
		t.Fatal(e)
	}
	s.AutoPending = &autoPending{ID: r.ID, Command: "start", Event: "charging", QueuedAt: time.Now().Add(-11 * time.Minute).Unix()}
	_, err := automaticCycle(engine, cfg, s)
	if err == nil || !strings.Contains(err.Error(), "watchdog") || !s.AutoBlocked {
		t.Fatalf("stalled pending was retried: %v", err)
	}
	d, e := control.InspectRequest(ctl, r.ID)
	if e != nil || d.State != "queued" {
		t.Fatalf("queue changed under watchdog %+v %v", d, e)
	}
}
func TestR03CPendingLegacyTimestampIsDurableChange(t *testing.T) {
	cfg, s, engine, ctl := r03aSetup(t)
	r, e := control.QueueAutomaticRequest(ctl, "start", "charging")
	if e != nil {
		t.Fatal(e)
	}
	s.AutoPending = &autoPending{ID: r.ID, Command: "start", Event: "charging"}
	changed, e := automaticCycle(engine, cfg, s)
	if e != nil || !changed || s.AutoPending == nil || s.AutoPending.QueuedAt <= 0 {
		t.Fatalf("missing migration intent %v %v %+v", changed, e, s.AutoPending)
	}
}
func TestR03CSuccessfulReceiptCannotDuplicateStart(t *testing.T) {
	cfg, s, engine, ctl := r03aSetup(t)
	r, e := control.QueueAutomaticRequest(ctl, "start", "charging")
	if e != nil {
		t.Fatal(e)
	}
	s.AutoPending = &autoPending{ID: r.ID, Command: "start", Event: "charging", QueuedAt: time.Now().Unix()}
	_, claim, e := control.NextRequest(ctl)
	if e != nil {
		t.Fatal(e)
	}
	s.Active["charging"] = true
	s.AutoOwned["charging"] = true
	if e := control.CompleteRequest(ctl, r, nil); e != nil {
		t.Fatal(e)
	}
	if _, e := automaticCycle(engine, cfg, s); e != nil {
		t.Fatal(e)
	}
	if s.AutoPending != nil {
		t.Fatal("did not retire applied request")
	}
	d, e := control.InspectRequest(ctl, r.ID)
	if e != nil || d.State != "applied" {
		t.Fatalf("receipt mutated %+v %v", d, e)
	}
	_ = claim
}
func TestR03CNoMutationOnReadOnlyRecoveryProbe(t *testing.T) {
	cfg, s, engine, _ := r03aSetup(t)
	ctl := filepath.Join(t.TempDir(), "ctl")
	cfg["header"].(map[string]any)["ctl_file"] = ctl
	s.AutoPending = &autoPending{ID: "00000000000000000001-1-000001", Command: "start", Event: "charging", QueuedAt: time.Now().Unix()}
	_, _ = automaticCycle(engine, cfg, s)
	if s.Active["charging"] || s.AutoOwned["charging"] {
		t.Fatal("read-only diagnostics changed effect ownership")
	}
}
