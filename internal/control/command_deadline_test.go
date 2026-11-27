package control

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestR02FullDeadlineExpiredBeforeApplying(t *testing.T) {
	ctl := filepath.Join(t.TempDir(), "ctl")
	req, err := QueueRequestWithDeadline(ctl, "start", "manual", 5*time.Millisecond)
	if err != nil {
		t.Fatal(err)
	}
	time.Sleep(15 * time.Millisecond)
	_, p, err := NextRequest(ctl)
	if err == nil || !strings.HasSuffix(p, ".interrupted") {
		t.Fatalf("expired request executed: %q %v", p, err)
	}
	a, ok := ReadAck(ctl, req.ID)
	if !ok || a.State != "failed" || !strings.Contains(a.Error, "deadline") {
		t.Fatalf("receipt %+v %v", a, ok)
	}
}
func TestR02FullDeadlineBetweenClaimAndApply(t *testing.T) {
	ctl := filepath.Join(t.TempDir(), "ctl")
	// Give NextRequest plenty of time on slow Android fsync. A 5ms TTL
	// races with the claim itself and tests the WRONG phase on Termux.
	req, err := QueueRequestWithDeadline(ctl, "start", "manual", 30*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	claimed, work, err := NextRequest(ctl)
	if err != nil || claimed.ID != req.ID {
		t.Fatalf("claim must succeed before simulated expiry: %+v %v", claimed, err)
	}
	// Simulate a crash/restart time jump by durably expiring the claimed
	// request on disk. This probes MarkApplying after a successful claim,
	// without relying on a flaky wall-clock sleep or filesystem speed.
	req.Deadline = time.Now().Add(-time.Second).UnixNano()
	data, err := json.Marshal(req)
	if err != nil {
		t.Fatal(err)
	}
	f, err := os.OpenFile(work, os.O_WRONLY|os.O_TRUNC, 0600)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := f.Write(append(data, '\n')); err != nil {
		f.Close()
		t.Fatal(err)
	}
	if err := f.Sync(); err != nil {
		f.Close()
		t.Fatal(err)
	}
	if err := f.Close(); err != nil {
		t.Fatal(err)
	}
	if err := MarkApplying(ctl, req); err == nil || !strings.Contains(err.Error(), "deadline") {
		t.Fatalf("expired request must be rejected at applying barrier: %v", err)
	}
	if _, err := os.Stat(phaseMarker(ctl, req, "applying")); !os.IsNotExist(err) {
		t.Fatalf("unsafe applying marker: %v", err)
	}
}
func TestR02FullCancelPendingNeverClaims(t *testing.T) {
	ctl := filepath.Join(t.TempDir(), "ctl")
	req, err := QueueRequest(ctl, "start", "manual")
	if err != nil {
		t.Fatal(err)
	}
	if err := CancelQueuedRequest(ctl, req.ID); err != nil {
		t.Fatal(err)
	}
	a, ok := ReadAck(ctl, req.ID)
	if !ok || a.State != "failed" || !strings.Contains(a.Error, "cancelled") {
		t.Fatalf("receipt %+v %v", a, ok)
	}
	if _, _, err := NextRequest(ctl); err != nil {
		t.Fatal(err)
	}
	if err := CancelQueuedRequest(ctl, req.ID); err == nil {
		t.Fatal("cancel repeated")
	}
}
func TestR02FullCancelClaimedRefused(t *testing.T) {
	ctl, req, _ := queueAndClaim(t)
	if err := CancelQueuedRequest(ctl, req.ID); err == nil {
		t.Fatal("cancel of in-flight root action accepted")
	}
	if _, ok := ReadAck(ctl, req.ID); ok {
		t.Fatal("receipt forged for applying claim")
	}
}
func TestR02FullDeadlineBounds(t *testing.T) {
	ctl := filepath.Join(t.TempDir(), "ctl")
	if _, err := QueueRequestWithDeadline(ctl, "start", "manual", -time.Second); err == nil {
		t.Fatal("negative deadline accepted")
	}
	if _, err := QueueRequestWithDeadline(ctl, "start", "manual", 25*time.Hour); err == nil {
		t.Fatal("unbounded deadline accepted")
	}
}
