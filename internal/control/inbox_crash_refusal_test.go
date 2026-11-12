package control

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestUnacknowledgedClaimIsQuarantinedNotReexecuted(t *testing.T) {
	ctl := filepath.Join(t.TempDir(), "ctl")
	first, err := QueueRequest(ctl, "start", "manual")
	if err != nil {
		t.Fatal(err)
	}
	claimed, path, err := NextRequest(ctl)
	if err != nil || claimed.ID != first.ID || !strings.HasSuffix(path, ".work") {
		t.Fatalf("claim=%+v path=%q err=%v", claimed, path, err)
	}
	next, interrupted, err := NextRequest(ctl)
	if err == nil || next.ID != "" || !strings.HasSuffix(interrupted, ".interrupted") {
		t.Fatalf("replayed=%+v path=%q err=%v", next, interrupted, err)
	}
	ack, ok := ReadAck(ctl, first.ID)
	if !ok || ack.State != "failed" || !strings.Contains(ack.Error, "not replayed") {
		t.Fatalf("ack=%+v ok=%v", ack, ok)
	}
	if _, err := os.Stat(interrupted); err != nil {
		t.Fatal(err)
	}
	later, err := QueueRequest(ctl, "reload", "")
	if err != nil {
		t.Fatal(err)
	}
	next, path, err = NextRequest(ctl)
	if err != nil || next.ID != later.ID || !strings.HasSuffix(path, ".work") {
		t.Fatalf("queue blocked by interrupted claim: %+v %q %v", next, path, err)
	}
}

func TestForgedRequestIDCannotWriteOutsideAckDirectory(t *testing.T) {
	dir := t.TempDir()
	ctl := filepath.Join(dir, "ctl")
	queue := ctl + ".d"
	if err := os.MkdirAll(queue, 0700); err != nil {
		t.Fatal(err)
	}
	filename := "00000000000000000001-1-000001.json"
	forged := Request{ID: "../../outside", Command: "start", Event: "manual", Created: 1}
	data, _ := json.Marshal(forged)
	if err := os.WriteFile(filepath.Join(queue, filename), data, 0600); err != nil {
		t.Fatal(err)
	}
	_, path, err := NextRequest(ctl)
	if err == nil || !strings.HasSuffix(path, ".bad") {
		t.Fatalf("forged request accepted: %v %s", err, path)
	}
	if _, err := os.Stat(filepath.Join(dir, "outside.json")); !os.IsNotExist(err) {
		t.Fatalf("unsafe ack escape: %v", err)
	}
	if err := CompleteRequest(ctl, forged, nil); err == nil {
		t.Fatal("forged ID accepted by ack writer")
	}
	if _, ok := ReadAck(ctl, forged.ID); ok {
		t.Fatal("forged ack ID accepted")
	}
}

// HF01: a newly renamed .json -> .work belongs to this call and is not a
// stranded claim. The previous implementation quarantined every fresh claim.
func TestFreshlyClaimedRequestsProcessBeforeCrashGuard(t *testing.T) {
	ctl := filepath.Join(t.TempDir(), "queue")
	const n = 12
	submitted := make([]Request, n)
	for i := 0; i < n; i++ {
		r, err := QueueRequest(ctl, "start", "manual")
		if err != nil {
			t.Fatal(err)
		}
		submitted[i] = r
	}
	for i, want := range submitted {
		got, path, err := NextRequest(ctl)
		if err != nil || got.ID != want.ID || !strings.HasSuffix(path, ".work") {
			t.Fatalf("claim %d newly queued command refused: got=%+v path=%q err=%v", i, got, path, err)
		}
		if err := CompleteRequest(ctl, got, nil); err != nil {
			t.Fatal(err)
		}
		if err := os.Remove(path); err != nil {
			t.Fatal(err)
		}
		ack, ok := ReadAck(ctl, want.ID)
		if !ok || ack.State != "applied" {
			t.Fatalf("ack %d=%+v ok=%v", i, ack, ok)
		}
	}
	got, path, err := NextRequest(ctl)
	if err != nil || got.ID != "" || path != "" {
		t.Fatalf("queue not drained: %+v %q %v", got, path, err)
	}
}

// The crash guard still refuses a claim left without a completion receipt.
func TestOldWorkClaimCannotBeMistakenForFreshJSON(t *testing.T) {
	ctl := filepath.Join(t.TempDir(), "queue")
	old, err := QueueRequest(ctl, "start", "manual")
	if err != nil {
		t.Fatal(err)
	}
	first, claimed, err := NextRequest(ctl)
	if err != nil || first.ID != old.ID || !strings.HasSuffix(claimed, ".work") {
		t.Fatalf("fresh claim=%+v path=%q err=%v", first, claimed, err)
	}
	next, interrupted, err := NextRequest(ctl)
	if err == nil || next.ID != "" || !strings.HasSuffix(interrupted, ".interrupted") {
		t.Fatalf("orphan claim replayed=%+v path=%q err=%v", next, interrupted, err)
	}
	ack, ok := ReadAck(ctl, old.ID)
	if !ok || ack.State != "failed" {
		t.Fatalf("unexpected receipt=%+v %v", ack, ok)
	}
}

// HF02: CLI-queued reload and subsequent event must keep FIFO order even
// when the daemon has acknowledged the first .work but not yet removed it.
func TestAcknowledgedClaimSkippedBeforeSubsequentEvent(t *testing.T) {
	ctl := filepath.Join(t.TempDir(), "xbs.ctl")
	reload, err := QueueRequest(ctl, "reload", "")
	if err != nil {
		t.Fatal(err)
	}
	second, err := QueueRequest(ctl, "start", "manual")
	if err != nil {
		t.Fatal(err)
	}
	claimed, firstPath, err := NextRequest(ctl)
	if err != nil || claimed.ID != reload.ID {
		t.Fatalf("first=%+v path=%q err=%v", claimed, firstPath, err)
	}
	if err := CompleteRequest(ctl, claimed, nil); err != nil {
		t.Fatal(err)
	}
	next, nextPath, err := NextRequest(ctl)
	if err != nil || next.ID != second.ID || next.Command != "start" || next.Event != "manual" {
		t.Fatalf("next=%+v path=%q err=%v", next, nextPath, err)
	}
	if _, err := os.Stat(firstPath); !os.IsNotExist(err) {
		t.Fatalf("stale acked .work=%q err=%v", firstPath, err)
	}
	if err := CompleteRequest(ctl, next, nil); err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(nextPath); err != nil {
		t.Fatal(err)
	}
	last, path, err := NextRequest(ctl)
	if err != nil || path != "" || last.ID != "" {
		t.Fatalf("drain=%+v path=%q err=%v", last, path, err)
	}
}
