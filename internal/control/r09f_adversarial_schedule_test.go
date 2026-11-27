package control

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// Each iteration uses a fresh private spool. The schedule alternates daemon
// death before acknowledgment and death after an immutable acknowledgment.
func TestR09FQueueRestartSchedule(t *testing.T) {
	for round := 0; round < 16; round++ {
		t.Run(fmt.Sprintf("restart-%02d", round), func(t *testing.T) {
			ctl := filepath.Join(t.TempDir(), "ctl")
			first, err := QueueRequest(ctl, "start", "manual")
			if err != nil {
				t.Fatal(err)
			}
			claimed, work, err := NextRequest(ctl)
			if err != nil || claimed.ID != first.ID || !strings.HasSuffix(work, ".work") {
				t.Fatalf("claim %+v %q %v", claimed, work, err)
			}
			acknowledged := round%2 == 0
			if acknowledged {
				if err := CompleteRequest(ctl, claimed, nil); err != nil {
					t.Fatal(err)
				}
			}
			old, where, recoveryErr := NextRequest(ctl)
			if acknowledged {
				if recoveryErr != nil || old.ID != "" || where != "" {
					t.Fatalf("acknowledged work replayed %+v %s %v", old, where, recoveryErr)
				}
			} else {
				if recoveryErr == nil || old.ID != "" || !strings.HasSuffix(where, ".interrupted") {
					t.Fatalf("unacknowledged work replayed %+v %s %v", old, where, recoveryErr)
				}
			}
			ack, ok := ReadAck(ctl, first.ID)
			if !ok || ack.State != map[bool]string{true: "applied", false: "failed"}[acknowledged] {
				t.Fatalf("lost immutable outcome %+v %t", ack, ok)
			}
			later, err := QueueRequest(ctl, "reload", "")
			if err != nil {
				t.Fatal(err)
			}
			next, _, err := NextRequest(ctl)
			if err != nil || next.ID != later.ID || next.Command != "reload" {
				t.Fatalf("subsequent request starved %+v %v", next, err)
			}
		})
	}
}

func TestR09FAckImmutabilitySchedule(t *testing.T) {
	for round := 0; round < 16; round++ {
		t.Run(fmt.Sprintf("ack-%02d", round), func(t *testing.T) {
			ctl := filepath.Join(t.TempDir(), "ctl")
			r, err := QueueRequest(ctl, "start", "manual")
			if err != nil {
				t.Fatal(err)
			}
			claim, _, err := NextRequest(ctl)
			if err != nil || claim.ID != r.ID {
				t.Fatalf("claim %+v %v", claim, err)
			}
			firstError := error(nil)
			expected := "applied"
			if round%2 != 0 {
				firstError = errors.New("injected effect refusal")
				expected = "failed"
			}
			if err := CompleteRequest(ctl, claim, firstError); err != nil {
				t.Fatal(err)
			}
			competing := error(nil)
			if firstError == nil {
				competing = errors.New("late failed attempt")
			}
			if err := CompleteRequest(ctl, claim, competing); err == nil {
				t.Fatal("ack overwritten by competing completion")
			}
			ack, ok := ReadAck(ctl, r.ID)
			if !ok || ack.State != expected {
				t.Fatalf("ack lost immutability %+v %t", ack, ok)
			}
		})
	}
}

func TestR09FSymlinkPathRefusalSchedule(t *testing.T) {
	for round := 0; round < 8; round++ {
		t.Run(fmt.Sprintf("symlink-%02d", round), func(t *testing.T) {
			dir := t.TempDir()
			real := filepath.Join(dir, "real")
			if err := os.Mkdir(real, 0700); err != nil {
				t.Fatal(err)
			}
			alias := filepath.Join(dir, "alias")
			if err := os.Symlink(real, alias); err != nil {
				t.Fatalf("symlink fixture unsupported: %v", err)
			}
			ctl := filepath.Join(alias, "ctl")
			if _, err := QueueRequest(ctl, "start", "manual"); err == nil || !strings.Contains(err.Error(), "symlinked") {
				t.Fatalf("unsafe parent accepted: %v", err)
			}
			if _, err := os.Stat(filepath.Join(real, "ctl.d")); !os.IsNotExist(err) {
				t.Fatalf("unsafe queue created through symlink: %v", err)
			}
		})
	}
}
