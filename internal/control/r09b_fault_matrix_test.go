package control

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestR09BInterruptedClaimRecovery(t *testing.T) {
	ctl := filepath.Join(t.TempDir(), "queue")
	req, err := QueueRequest(ctl, "start", "manual")
	if err != nil {
		t.Fatal(err)
	}
	got, path, err := NextRequest(ctl)
	if err != nil || got.ID != req.ID || !strings.HasSuffix(path, ".work") {
		t.Fatalf("first claim=%+v %s %v", got, path, err)
	}
	got, path, err = NextRequest(ctl)
	if err == nil || got.ID != "" || !strings.HasSuffix(path, ".interrupted") {
		t.Fatalf("crashed claim replayed: %+v %s %v", got, path, err)
	}
	ack, ok := ReadAck(ctl, req.ID)
	if !ok || ack.State != "failed" || !strings.Contains(ack.Error, "not replayed") {
		t.Fatalf("missing crash receipt: %+v %v", ack, ok)
	}
	if _, err := os.Stat(path); err != nil {
		t.Fatalf("lost interrupted evidence: %v", err)
	}
}
func TestR09BPublishedAckNonreplace(t *testing.T) {
	ctl := filepath.Join(t.TempDir(), "queue")
	req, err := QueueRequest(ctl, "start", "manual")
	if err != nil {
		t.Fatal(err)
	}
	got, _, err := NextRequest(ctl)
	if err != nil || got.ID != req.ID {
		t.Fatalf("claim %v", err)
	}
	if err := CompleteRequest(ctl, got, nil); err != nil {
		t.Fatal(err)
	}
	if err := CompleteRequest(ctl, got, errors.New("replace applied with failure")); err == nil {
		t.Fatal("immutable receipt overwritten")
	}
	ack, ok := ReadAck(ctl, req.ID)
	if !ok || ack.State != "applied" {
		t.Fatalf("ack drift %+v %v", ack, ok)
	}
}
func TestR09BUnsafeQueueRequests(t *testing.T) {
	for _, v := range []struct {
		command, event string
		ttl            time.Duration
	}{
		{"unknown", "manual", 0}, {"start", "manual\nunsafe", 0}, {"start", "manual", -time.Second},
		{"start", "manual", 25 * time.Hour}, {"start", strings.Repeat("x", 129), 0},
	} {
		ctl := filepath.Join(t.TempDir(), "queue")
		if _, err := QueueRequestWithDeadline(ctl, v.command, v.event, v.ttl); err == nil {
			t.Fatalf("unsafe request accepted %+v", v)
		}
		if _, err := os.Stat(ctl + ".d"); !os.IsNotExist(err) {
			t.Fatalf("invalid request created spool: %v", err)
		}
	}
	ctl := filepath.Join(t.TempDir(), "queue")
	if _, err := QueueAutomaticRequest(ctl, "reload", "charging"); err == nil {
		t.Fatal("automatic reload accepted")
	}
	if _, err := QueueAutomaticRequest(ctl, "start", "manual"); err == nil {
		t.Fatal("unapproved automatic event accepted")
	}
}
