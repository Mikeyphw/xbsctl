package control

import (
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

func TestR02AuditedOrphanApplyingCannotBypassStartup(t *testing.T) {
	ctl, req, work := queueAndClaim(t)
	if err := MarkApplying(ctl, req); err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(work); err != nil {
		t.Fatal(err)
	}
	unknown, err := PendingUnknown(ctl)
	if err != nil || !reflect.DeepEqual(unknown, []string{req.ID}) {
		t.Fatalf("lost applying phase: ids=%v err=%v", unknown, err)
	}
	ack, ok := ReadAck(ctl, req.ID)
	if !ok || ack.State != "unknown" {
		t.Fatalf("missing durable unknown acknowledgement: %+v, %v", ack, ok)
	}
	again, err := PendingUnknown(ctl)
	if err != nil || !reflect.DeepEqual(again, unknown) {
		t.Fatalf("unknown outcome cleared: %v %v", again, err)
	}
}

func TestR02AuditedUnknownReceiptWithoutClaimStillBlocks(t *testing.T) {
	ctl, req, work := queueAndClaim(t)
	if err := CompleteUnknownRequest(ctl, req); err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(work); err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(phaseMarker(ctl, req, "claimed")); err != nil {
		t.Fatal(err)
	}
	unknown, err := PendingUnknown(ctl)
	if err != nil || !reflect.DeepEqual(unknown, []string{req.ID}) {
		t.Fatalf("orphan unknown receipt missed: %v %v", unknown, err)
	}
	report, err := InspectRecovery(ctl, t.TempDir())
	if err != nil || !reflect.DeepEqual(report.Unknown, unknown) || !report.RequiresOperator || report.CanAutoClear {
		t.Fatalf("read-only report missed orphan: %+v %v", report, err)
	}
}

func TestR02AuditedApplyingWithoutWorkIsReadOnlyInspected(t *testing.T) {
	ctl, req, work := queueAndClaim(t)
	if err := MarkApplying(ctl, req); err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(work); err != nil {
		t.Fatal(err)
	}
	report, err := InspectRecovery(ctl, t.TempDir())
	if err != nil || !reflect.DeepEqual(report.Unknown, []string{req.ID}) || !report.RequiresOperator {
		t.Fatalf("orphan phase not shown: %+v %v", report, err)
	}
	if _, ok := ReadAck(ctl, req.ID); ok {
		t.Fatal("inspection unexpectedly wrote a receipt")
	}
}

func TestR02AuditedTerminalReceiptDoesNotCreateFalseHold(t *testing.T) {
	ctl, req, work := queueAndClaim(t)
	if err := MarkApplying(ctl, req); err != nil {
		t.Fatal(err)
	}
	if err := CompleteRequest(ctl, req, nil); err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(work); err != nil {
		t.Fatal(err)
	}
	unknown, err := PendingUnknown(ctl)
	if err != nil || len(unknown) != 0 {
		t.Fatalf("completed command incorrectly blocked: %v %v", unknown, err)
	}
}

func TestR02AuditedOrphanPhaseSymlinkFailsClosed(t *testing.T) {
	ctl, req, work := queueAndClaim(t)
	if err := MarkApplying(ctl, req); err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(work); err != nil {
		t.Fatal(err)
	}
	target := filepath.Join(t.TempDir(), "untrusted")
	if err := os.WriteFile(target, []byte("secret"), 0600); err != nil {
		t.Fatal(err)
	}
	phase := phaseMarker(ctl, req, "applying")
	if err := os.Remove(phase); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(target, phase); err != nil {
		t.Skip(err)
	}
	if _, err := PendingUnknown(ctl); err == nil {
		t.Fatal("symlinked orphan applying marker accepted")
	}
	b, err := os.ReadFile(target)
	if err != nil || string(b) != "secret" {
		t.Fatalf("changed symlink destination: %q %v", b, err)
	}
}

func TestR02AuditedInvalidOrphanPhaseCannotBeCleared(t *testing.T) {
	ctl, req, work := queueAndClaim(t)
	if err := MarkApplying(ctl, req); err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(work); err != nil {
		t.Fatal(err)
	}
	p := phaseMarker(ctl, req, "applying")
	if err := os.WriteFile(p, []byte(`{"version":1,"id":"fake","phase":"applying"}`), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := PendingUnknown(ctl); err == nil || !strings.Contains(err.Error(), "identity") {
		t.Fatalf("forged phase not rejected: %v", err)
	}
}

func TestR02AuditedInspectionNeverUpgradesUnsafeLegacyPermissions(t *testing.T) {
	ctl := filepath.Join(t.TempDir(), "ctl")
	if err := os.Mkdir(ctl+".d", 0755); err != nil {
		t.Fatal(err)
	}
	// A restrictive process umask (077 is common in Termux) would silently
	// turn Mkdir(0755) into 0700, invalidating this negative test. Explicitly
	// make the fixture unsafe *before* exercising read-only inspection.
	if err := os.Chmod(ctl+".d", 0755); err != nil {
		t.Fatal(err)
	}
	if _, err := InspectRecovery(ctl, t.TempDir()); err == nil {
		t.Fatal("unsafe spool should be refused, not repaired by inspection")
	}
	st, err := os.Stat(ctl + ".d")
	if err != nil || st.Mode().Perm() != 0755 {
		t.Fatalf("read-only inspection chmodded the spool: %v %v", st, err)
	}
}
