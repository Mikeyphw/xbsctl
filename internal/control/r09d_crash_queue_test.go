package control

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// Verify a queued command is never replayed when a daemon dies between its
// durable claim and acknowledgement, or after the acknowledgement but before
// deleting the .work file.
func TestR09DQueueClaimAckCrashMatrix(t *testing.T) {
	for _, tc := range []struct {
		name         string
		acknowledged bool
	}{
		{"after-claim-before-ack", false},
		{"after-ack-before-cleanup", true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			ctl := filepath.Join(t.TempDir(), "control")
			req, err := QueueRequest(ctl, "start", "manual")
			if err != nil {
				t.Fatal(err)
			}
			got, work, err := NextRequest(ctl)
			if err != nil || got.ID != req.ID || !strings.HasSuffix(work, ".work") {
				t.Fatalf("initial durable claim %+v %s %v", got, work, err)
			}
			if tc.acknowledged {
				if err := CompleteRequest(ctl, got, nil); err != nil {
					t.Fatal(err)
				}
			}
			next, where, recoveryError := NextRequest(ctl)
			if tc.acknowledged {
				if recoveryError != nil || next.ID != "" || where != "" {
					t.Fatalf("acknowledged work replayed %+v %q %v", next, where, recoveryError)
				}
				a, ok := ReadAck(ctl, req.ID)
				if !ok || a.State != "applied" {
					t.Fatalf("durable acknowledgement lost: %+v %t", a, ok)
				}
				if _, err := os.Stat(work); !os.IsNotExist(err) {
					t.Fatalf("work not removed after ack: %v", err)
				}
			} else {
				if recoveryError == nil || next.ID != "" || !strings.HasSuffix(where, ".interrupted") {
					t.Fatalf("unsafe replay after claim crash %+v %q %v", next, where, recoveryError)
				}
				a, ok := ReadAck(ctl, req.ID)
				if !ok || a.State != "failed" {
					t.Fatalf("no interruption receipt: %+v %t", a, ok)
				}
				if err := CompleteRequest(ctl, req, nil); err == nil {
					t.Fatal("interrupted claim overwritten with applied receipt")
				}
			}
			later, err := QueueRequest(ctl, "reload", "")
			if err != nil {
				t.Fatal(err)
			}
			follow, _, err := NextRequest(ctl)
			if err != nil || follow.ID != later.ID || follow.Command != "reload" {
				t.Fatalf("crash blocked later request %+v %v", follow, err)
			}
		})
	}
}

func TestR09DImmutableAckRejectsCompetingCompletion(t *testing.T) {
	ctl := filepath.Join(t.TempDir(), "control")
	r, err := QueueRequest(ctl, "start", "manual")
	if err != nil {
		t.Fatal(err)
	}
	claim, _, err := NextRequest(ctl)
	if err != nil || claim.ID != r.ID {
		t.Fatalf("claim: %+v %v", claim, err)
	}
	if err := CompleteRequest(ctl, claim, errors.New("simulated failure")); err != nil {
		t.Fatal(err)
	}
	if err := CompleteRequest(ctl, claim, nil); err == nil {
		t.Fatal("failed receipt promoted to success")
	}
	ack, ok := ReadAck(ctl, r.ID)
	if !ok || ack.State != "failed" {
		t.Fatalf("receipt modified: %+v %v", ack, ok)
	}
}
