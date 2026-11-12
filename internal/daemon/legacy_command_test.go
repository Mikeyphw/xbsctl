package daemon

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestR02ALegacyControlRefusesSymlinkAndOversize(t *testing.T) {
	root := t.TempDir()
	target := filepath.Join(root, "target")
	if err := os.WriteFile(target, []byte("start manual\n"), 0600); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(root, "control")
	if err := os.Symlink(target, link); err != nil {
		t.Skip(err)
	}
	if _, err := consumeLegacyControl(link); err == nil {
		t.Fatal("legacy symlink accepted")
	}
	b, _ := os.ReadFile(target)
	if string(b) != "start manual\n" {
		t.Fatal("symlink target modified")
	}
	if err := os.Remove(link); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(link, []byte(strings.Repeat("a", 4097)), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := consumeLegacyControl(link); err == nil {
		t.Fatal("oversized command accepted")
	}
}
func TestR02ALegacyControlConsumedOnce(t *testing.T) {
	ctl := filepath.Join(t.TempDir(), "control")
	if err := os.WriteFile(ctl, []byte("start manual\n"), 0600); err != nil {
		t.Fatal(err)
	}
	cmd, err := consumeLegacyControl(ctl)
	if err != nil || cmd != "start manual" {
		t.Fatalf("consume %q %v", cmd, err)
	}
	cmd, err = consumeLegacyControl(ctl)
	if err != nil || cmd != "" {
		t.Fatalf("replayed %q %v", cmd, err)
	}
}
