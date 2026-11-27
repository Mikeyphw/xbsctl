package daemon

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/Mikeyphw/xbsctl/internal/control"
)

func TestR03CPendingQueuedAndAppliedReceipts(t *testing.T) {
	ctl := filepath.Join(t.TempDir(), "ctl")
	r, err := control.QueueAutomaticRequest(ctl, "start", "charging")
	if err != nil {
		t.Fatal(err)
	}
	now := time.Unix(1720000000, 0)
	p := &autoPending{ID: r.ID, Command: "start", Event: "charging", QueuedAt: now.Unix()}
	done, changed, err := checkAutoPending(ctl, p, now)
	if err != nil || done || changed {
		t.Fatalf("premature completion %v %v %v", done, changed, err)
	}
	got, claim, err := control.NextRequest(ctl)
	if err != nil || got.ID != r.ID {
		t.Fatalf("%v %v", got, err)
	}
	if err := control.CompleteRequest(ctl, r, nil); err != nil {
		t.Fatal(err)
	}
	done, changed, err = checkAutoPending(ctl, p, now)
	if err != nil || !done || !changed {
		t.Fatalf("missed applied receipt %v %v %v", done, changed, err)
	}
	if _, err := os.Stat(claim); err != nil {
		t.Fatal(err)
	} // inspector MUST NOT delete claim
}
func TestR03CPendingMissingReceiptRefuses(t *testing.T) {
	ctl := filepath.Join(t.TempDir(), "ctl")
	p := &autoPending{ID: "00000000000000000001-1-000001", Command: "start", Event: "charging", QueuedAt: 100}
	_, _, err := checkAutoPending(ctl, p, time.Unix(102, 0))
	if err == nil || !strings.Contains(err.Error(), "outcome unknown") {
		t.Fatalf("missing request accepted: %v", err)
	}
	for _, path := range []string{ctl + ".d", ctl + ".ack"} {
		if _, e := os.Stat(path); !os.IsNotExist(e) {
			t.Fatalf("inspection mutated %s: %v", path, e)
		}
	}
}
func TestR03CPendingStalledWatchdogRefuses(t *testing.T) {
	ctl := filepath.Join(t.TempDir(), "ctl")
	r, e := control.QueueAutomaticRequest(ctl, "start", "charging")
	if e != nil {
		t.Fatal(e)
	}
	p := &autoPending{ID: r.ID, Command: "start", Event: "charging", QueuedAt: 100}
	_, _, err := checkAutoPending(ctl, p, time.Unix(701, 0))
	if err == nil || !strings.Contains(err.Error(), "watchdog") {
		t.Fatalf("stale queue retried: %v", err)
	}
	d, e := control.InspectRequest(ctl, r.ID)
	if e != nil || d.State != "queued" {
		t.Fatalf("watchdog mutated queue %+v %v", d, e)
	}
}
func TestR03CLegacyInFlightTimestampMigrates(t *testing.T) {
	ctl := filepath.Join(t.TempDir(), "ctl")
	r, e := control.QueueAutomaticRequest(ctl, "stop", "screen_off")
	if e != nil {
		t.Fatal(e)
	}
	p := &autoPending{ID: r.ID, Command: "stop", Event: "screen_off"}
	now := time.Unix(3000, 0)
	done, changed, err := checkAutoPending(ctl, p, now)
	if err != nil || done || !changed || p.QueuedAt != 3000 {
		t.Fatalf("legacy migrate %v %v %v %d", done, changed, err, p.QueuedAt)
	}
}
func TestR03CFailedReceiptBlocksWithoutRemovingWork(t *testing.T) {
	ctl := filepath.Join(t.TempDir(), "ctl")
	r, e := control.QueueAutomaticRequest(ctl, "start", "low_power")
	if e != nil {
		t.Fatal(e)
	}
	_, claim, e := control.NextRequest(ctl)
	if e != nil {
		t.Fatal(e)
	}
	if e := control.CompleteRequest(ctl, r, os.ErrPermission); e != nil {
		t.Fatal(e)
	}
	p := &autoPending{ID: r.ID, Command: "start", Event: "low_power", QueuedAt: 500}
	_, _, err := checkAutoPending(ctl, p, time.Unix(501, 0))
	if err == nil || !strings.Contains(err.Error(), "reconciliation") {
		t.Fatalf("failed accepted: %v", err)
	}
	if _, e := os.Stat(claim); e != nil {
		t.Fatalf("failed inspection erased claim: %v", e)
	}
}
func TestR03CForgedOrChangedPendingIdentityRefuses(t *testing.T) {
	ctl := filepath.Join(t.TempDir(), "ctl")
	r, e := control.QueueAutomaticRequest(ctl, "start", "charging")
	if e != nil {
		t.Fatal(e)
	}
	p := &autoPending{ID: r.ID, Command: "stop", Event: "charging", QueuedAt: 500}
	_, _, err := checkAutoPending(ctl, p, time.Unix(501, 0))
	if err == nil || !strings.Contains(err.Error(), "differs") {
		t.Fatalf("queue intent changed: %v", err)
	}
}
func TestR03CFutureWatchTimestampRefuses(t *testing.T) {
	ctl := filepath.Join(t.TempDir(), "ctl")
	r, e := control.QueueAutomaticRequest(ctl, "start", "charging")
	if e != nil {
		t.Fatal(e)
	}
	p := &autoPending{ID: r.ID, Command: "start", Event: "charging", QueuedAt: 9000}
	_, _, err := checkAutoPending(ctl, p, time.Unix(100, 0))
	if err == nil {
		t.Fatal("future persisted timestamp accepted")
	}
}
