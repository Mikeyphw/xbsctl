package control

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func queueAndClaim(t *testing.T) (string, Request, string) {
	t.Helper()
	ctl := filepath.Join(t.TempDir(), "ctl")
	r, err := QueueRequest(ctl, "start", "manual")
	if err != nil {
		t.Fatal(err)
	}
	got, work, err := NextRequest(ctl)
	if err != nil || got != r || !strings.HasSuffix(work, ".work") {
		t.Fatalf("claim %v %s %v", got, work, err)
	}
	return ctl, r, work
}

func TestR02BDurableApplyingCrashHasUnknownReceiptAndPermanentHold(t *testing.T) {
	ctl, r, work := queueAndClaim(t)
	if err := MarkApplying(ctl, r); err != nil {
		t.Fatal(err)
	}
	unknown, err := PendingUnknown(ctl)
	if err != nil || len(unknown) != 1 || unknown[0] != r.ID {
		t.Fatalf("preflight %+v %v", unknown, err)
	}
	ack, ok := ReadAck(ctl, r.ID)
	if !ok || ack.State != "unknown" || !strings.Contains(ack.Error, "manual reconciliation") {
		t.Fatalf("unknown receipt %+v %v", ack, ok)
	}
	if err := CompleteRequest(ctl, r, nil); err == nil {
		t.Fatal("unknown receipt was overwritten with success")
	}
	if _, err := os.Stat(work); err != nil {
		t.Fatalf("evidence removed: %v", err)
	}
	got, path, err := NextRequest(ctl)
	if !errors.Is(err, ErrUnknownOutcome) || got.ID != "" || path != work {
		t.Fatalf("unknown claim replayed %+v %s %v", got, path, err)
	}
	again, err := PendingUnknown(ctl)
	if err != nil || len(again) != 1 || again[0] != r.ID {
		t.Fatalf("unknown hold cleared without review %+v %v", again, err)
	}
}

func TestR02BClaimedButNeverApplyingSafelyAborted(t *testing.T) {
	ctl, r, work := queueAndClaim(t)
	unknown, err := PendingUnknown(ctl)
	if err != nil || len(unknown) != 0 {
		t.Fatalf("preapply treated as uncertain %v %v", unknown, err)
	}
	got, quarantine, err := NextRequest(ctl)
	if err == nil || got.ID != "" || !strings.HasSuffix(quarantine, ".interrupted") {
		t.Fatalf("preapply replayed %+v %s %v", got, quarantine, err)
	}
	ack, ok := ReadAck(ctl, r.ID)
	if !ok || ack.State != "failed" || !strings.Contains(ack.Error, "before privileged execution") {
		t.Fatalf("expected safe failed receipt %+v %v", ack, ok)
	}
	if _, err := os.Stat(work); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("unquarantined claim: %v", err)
	}
}

func TestR02BLegacyClaimWithoutPhaseIsUnknownNotFailed(t *testing.T) {
	ctl, r, work := queueAndClaim(t)
	if err := os.Remove(phaseMarker(ctl, r, "claimed")); err != nil {
		t.Fatal(err)
	}
	got, p, err := NextRequest(ctl)
	if !errors.Is(err, ErrUnknownOutcome) || got.ID != "" || p != work {
		t.Fatalf("missing phase unsafe replay %+v %s %v", got, p, err)
	}
	ack, ok := ReadAck(ctl, r.ID)
	if !ok || ack.State != "unknown" {
		t.Fatalf("missing-phase receipt %+v %v", ack, ok)
	}
}

func TestR02BAcknowledgedApplyingWorkCanBeCleaned(t *testing.T) {
	ctl, r, work := queueAndClaim(t)
	if err := MarkApplying(ctl, r); err != nil {
		t.Fatal(err)
	}
	if err := CompleteRequest(ctl, r, nil); err != nil {
		t.Fatal(err)
	}
	unknown, err := PendingUnknown(ctl)
	if err != nil || len(unknown) != 0 {
		t.Fatalf("terminal receipt caused hold %v %v", unknown, err)
	}
	got, path, err := NextRequest(ctl)
	if err != nil || got.ID != "" || path != "" {
		t.Fatalf("acked work replayed %+v %s %v", got, path, err)
	}
	if _, err := os.Stat(work); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("acked work retained: %v", err)
	}
}

func TestR02BApplyingRequiresExactDurableClaim(t *testing.T) {
	ctl, r, _ := queueAndClaim(t)
	wrong := r
	wrong.Event = "other"
	if err := MarkApplying(ctl, wrong); err == nil {
		t.Fatal("forged event applied")
	}
	if err := os.Remove(phaseMarker(ctl, r, "claimed")); err != nil {
		t.Fatal(err)
	}
	if err := MarkApplying(ctl, r); err == nil {
		t.Fatal("missing claim allowed privileged work")
	}
	if _, err := os.Stat(phaseMarker(ctl, r, "applying")); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("unsafe applying marker: %v", err)
	}
}

func TestR02BDuplicateApplyingMarkerRejected(t *testing.T) {
	ctl, r, _ := queueAndClaim(t)
	if err := MarkApplying(ctl, r); err != nil {
		t.Fatal(err)
	}
	if err := MarkApplying(ctl, r); err == nil {
		t.Fatal("second apply phase accepted")
	}
}

func TestR02BMalformedPhasePreventsRootAction(t *testing.T) {
	ctl, r, _ := queueAndClaim(t)
	path := phaseMarker(ctl, r, "claimed")
	if err := os.WriteFile(path, []byte(`{"version":1,"id":"other","phase":"claimed"}`), 0600); err != nil {
		t.Fatal(err)
	}
	if err := MarkApplying(ctl, r); err == nil {
		t.Fatal("forged phase accepted")
	}
	if _, err := os.Stat(phaseMarker(ctl, r, "applying")); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("corrupt marker had side effects: %v", err)
	}
}

func TestR02BPhaseSymlinkNeverFollowed(t *testing.T) {
	ctl, r, _ := queueAndClaim(t)
	path := phaseMarker(ctl, r, "claimed")
	secret := filepath.Join(t.TempDir(), "secret")
	if err := os.WriteFile(secret, []byte("private"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(path); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(secret, path); err != nil {
		t.Skip(err)
	}
	if err := MarkApplying(ctl, r); err == nil {
		t.Fatal("symlink phase followed")
	}
	data, err := os.ReadFile(secret)
	if err != nil || string(data) != "private" {
		t.Fatalf("symlink target changed %q %v", data, err)
	}
}

func TestR02BUnknownReceiptDoesNotOverwriteExisting(t *testing.T) {
	ctl, r, _ := queueAndClaim(t)
	if err := MarkApplying(ctl, r); err != nil {
		t.Fatal(err)
	}
	if err := CompleteRequest(ctl, r, nil); err != nil {
		t.Fatal(err)
	}
	if err := CompleteUnknownRequest(ctl, r); err == nil {
		t.Fatal("unknown overwrote applied receipt")
	}
	ack, ok := ReadAck(ctl, r.ID)
	if !ok || ack.State != "applied" {
		t.Fatalf("rewrote terminal result %+v %v", ack, ok)
	}
}

func TestR02BPhaseIdentityRemainsBoundToRequest(t *testing.T) {
	ctl, r, _ := queueAndClaim(t)
	data, err := readSpoolFile(phaseMarker(ctl, r, "claimed"), maxRequestBytes)
	if err != nil {
		t.Fatal(err)
	}
	var rec commandPhase
	if err = json.Unmarshal(data, &rec); err != nil {
		t.Fatal(err)
	}
	if rec.Version != 1 || rec.ID != r.ID || rec.Event != r.Event || rec.Phase != "claimed" {
		t.Fatalf("unbound command phase %+v", rec)
	}
	if err := MarkApplying(ctl, r); err != nil {
		t.Fatal(err)
	}
	applying, err := readSpoolFile(phaseMarker(ctl, r, "applying"), maxRequestBytes)
	if err != nil {
		t.Fatal(err)
	}
	if err = json.Unmarshal(applying, &rec); err != nil || rec.Phase != "applying" {
		t.Fatalf("apply transition %+v %v", rec, err)
	}
}
