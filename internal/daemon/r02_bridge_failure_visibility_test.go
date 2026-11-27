package daemon

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Mikeyphw/xbsctl/internal/config"
)

// Event-bridge failures must be visible in durable daemon status, even when
// the privileged effect has already completed and should not be replayed.
func TestR02AuditedBridgePublicationFailureVisibleInStatus(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("XBS_RUNTIME_DIR", filepath.Join(dir, "runtime"))
	t.Setenv("XBS_EXTERNAL_ROOT", filepath.Join(dir, "external"))
	t.Setenv("XBS_EVENT_BRIDGE_ROOT", filepath.Join(dir, "private"))
	// A symlinked signing key is deliberately untrusted by the bridge.
	target := filepath.Join(dir, "target")
	if err := os.WriteFile(target, []byte("test"), 0600); err != nil {
		t.Fatal(err)
	}
	key := filepath.Join(dir, "bad-key")
	if err := os.Symlink(target, key); err != nil {
		t.Skip(err)
	}
	t.Setenv("XBS_EVENT_BRIDGE_KEY", key)
	cfg := config.Default()
	s := &state{Mode: "normal", Active: map[string]bool{}, EffectResults: map[string]any{}}
	if err := writeEventChanges(cfg, s, map[string]any{"state": "inactive", "type": "profile", "name": "manual"}); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(s.LastError, "event bridge publication") {
		t.Fatalf("bridge failure was silenced: %q", s.LastError)
	}
	b, err := os.ReadFile(config.ResolvePaths().Status)
	if err != nil || !strings.Contains(string(b), "event bridge publication") {
		t.Fatalf("failure not in status: %s %v", b, err)
	}
}
