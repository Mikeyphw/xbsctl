package control

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestR02AuditedSymlinkedParentRejectedBeforePublication(t *testing.T) {
	root := t.TempDir()
	destination := filepath.Join(root, "real")
	if err := os.MkdirAll(destination, 0700); err != nil {
		t.Fatal(err)
	}
	alias := filepath.Join(root, "alias")
	if err := os.Symlink(destination, alias); err != nil {
		t.Skip(err)
	}
	ctl := filepath.Join(alias, "control")
	if _, err := QueueRequest(ctl, "start", "manual"); err == nil || !strings.Contains(err.Error(), "symlinked") {
		t.Fatalf("symlink-parent queue was accepted: %v", err)
	}
	if _, err := os.Stat(filepath.Join(destination, "control.d")); !os.IsNotExist(err) {
		t.Fatalf("queue created in symlink destination: %v", err)
	}
}

func TestR02AuditedSpoolOwnsPrivateDirectory(t *testing.T) {
	ctl := filepath.Join(t.TempDir(), "ctl")
	if _, err := QueueRequest(ctl, "start", "manual"); err != nil {
		t.Fatal(err)
	}
	if err := privateSpoolDir(ctl+".d", false); err != nil {
		t.Fatalf("new private spool was rejected: %v", err)
	}
}

func TestR02AuditedSpoolRejectsForeignOwnerWhenSupported(t *testing.T) {
	if os.Geteuid() != 0 {
		t.Skip("test requires ability to create a foreign-owned directory")
	}
	path := filepath.Join(t.TempDir(), "foreign")
	if err := os.Mkdir(path, 0755); err != nil {
		t.Fatal(err)
	}
	// Mkdir is masked by the caller's umask. Record an explicit legacy
	// 0755 preimage so the test verifies no privileged chmod took place.
	if err := os.Chmod(path, 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.Chown(path, 65534, 65534); err != nil {
		t.Skip(err)
	}
	if err := privateSpoolDir(path, false); err == nil || !strings.Contains(err.Error(), "owner mismatch") {
		t.Fatalf("foreign-owner spool accepted: %v", err)
	}
	st, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if st.Mode().Perm() != 0755 {
		t.Fatal("foreign-owned directory was chmodded")
	}
}
