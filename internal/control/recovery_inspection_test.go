package control

import (
	"os"
	"path/filepath"
	"testing"
)

func TestR02FullReadOnlyRecoveryInspectionDoesNotAcknowledge(t *testing.T) {
	ctl, req, work := queueAndClaim(t)
	if err := MarkApplying(ctl, req); err != nil {
		t.Fatal(err)
	}
	report, err := InspectRecovery(ctl, t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	if len(report.Unknown) != 1 || report.Unknown[0] != req.ID || !report.RequiresOperator || report.CanAutoClear {
		t.Fatalf("inspection=%+v", report)
	}
	if _, ok := ReadAck(ctl, req.ID); ok {
		t.Fatal("inspection mutated receipt")
	}
	if _, err := os.Stat(work); err != nil {
		t.Fatal(err)
	}
}
func TestR02FullRecoveryInspectionRejectsSymlinkedWAL(t *testing.T) {
	d := t.TempDir()
	target := filepath.Join(d, "secret")
	if err := os.WriteFile(target, []byte(`{"version":1}`), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(target, filepath.Join(d, "daemon_state_v1.json")); err != nil {
		t.Skip(err)
	}
	if _, err := InspectRecovery(filepath.Join(d, "ctl"), d); err == nil {
		t.Fatal("followed WAL symlink")
	}
}
func TestR02FullRecoveryInspectionRejectsInvalidWAL(t *testing.T) {
	d := t.TempDir()
	if err := os.WriteFile(filepath.Join(d, "daemon_state_v1.json"), []byte(`{"version":99}`), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := InspectRecovery(filepath.Join(d, "ctl"), d); err == nil {
		t.Fatal("accepted unknown WAL schema")
	}
}
