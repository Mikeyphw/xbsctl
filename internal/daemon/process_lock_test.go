package daemon

import (
	"os"
	"path/filepath"
	"testing"
)

func TestR02ASecondDaemonCannotClaimRuntime(t *testing.T) {
	t.Setenv("XBS_RUNTIME_DIR", filepath.Join(t.TempDir(), "runtime"))
	first, err := acquireDaemonLock()
	if err != nil {
		t.Fatal(err)
	}
	defer first.Close()
	second, err := acquireDaemonLock()
	if err == nil {
		second.Close()
		t.Fatal("second daemon lock accepted")
	}
	if err := first.Close(); err != nil {
		t.Fatal(err)
	}
	third, err := acquireDaemonLock()
	if err != nil {
		t.Fatalf("lock not released: %v", err)
	}
	third.Close()
}
func TestR02ASymlinkDaemonLockRefused(t *testing.T) {
	runtime := filepath.Join(t.TempDir(), "runtime")
	t.Setenv("XBS_RUNTIME_DIR", runtime)
	if err := os.MkdirAll(runtime, 0700); err != nil {
		t.Fatal(err)
	}
	target := filepath.Join(runtime, "victim")
	if err := os.WriteFile(target, []byte("untouched"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(target, filepath.Join(runtime, "xbs-daemon.lock")); err != nil {
		t.Skip(err)
	}
	f, err := acquireDaemonLock()
	if err == nil {
		f.Close()
		t.Fatal("symlink daemon lock accepted")
	}
	b, err := os.ReadFile(target)
	if err != nil || string(b) != "untouched" {
		t.Fatalf("lock target touched: %s %v", b, err)
	}
}
