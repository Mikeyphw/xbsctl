package daemon

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Mikeyphw/xbsctl/internal/config"
)

func TestR02FullVersionedEventRecordAndStatus(t *testing.T) {
	root := t.TempDir()
	t.Setenv("XBS_RUNTIME_DIR", filepath.Join(root, "runtime"))
	t.Setenv("XBS_EXTERNAL_ROOT", filepath.Join(root, "external"))
	t.Setenv("XBS_EVENT_BRIDGE_ROOT", filepath.Join(root, "private"))
	cfg := config.Default()
	s := &state{Mode: "normal", Active: map[string]bool{}, EffectResults: map[string]any{}}
	if err := writeStatus(cfg, s); err != nil {
		t.Fatal(err)
	}
	if err := writeEventChanges(cfg, s, map[string]any{"state": "inactive", "type": "profile", "name": "manual"}); err != nil {
		t.Fatal(err)
	}
	for _, path := range []string{config.ResolvePaths().Status, filepath.Join(root, "runtime", "event_changes.json")} {
		b, err := os.ReadFile(path)
		if err != nil {
			t.Fatal(err)
		}
		var obj map[string]any
		if err := json.Unmarshal(b, &obj); err != nil {
			t.Fatal(err)
		}
		if obj["version"] == nil {
			t.Fatalf("unversioned record: %s", path)
		}
	}
}
func TestR02FullStartupRejectsInvalidConfigBeforeRuntime(t *testing.T) {
	root := t.TempDir()
	t.Setenv("XBS_RUNTIME_DIR", filepath.Join(root, "runtime"))
	t.Setenv("XBS_EXTERNAL_ROOT", filepath.Join(root, "external"))
	cfg := config.Default()
	cfg["header"].(map[string]any)["version"] = 99
	if err := os.MkdirAll(filepath.Dir(config.ResolvePaths().Config), 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(config.ResolvePaths().Config, []byte(`{"header":{"version":99},"events":{}}`), 0600); err != nil {
		t.Fatal(err)
	}
	var output strings.Builder
	if result := Main([]string{"--once"}, &output, &output); result == 0 {
		t.Fatalf("invalid config accepted: %s", output.String())
	}
	if _, err := os.Stat(filepath.Join(root, "runtime", "daemon_state_v1.json")); err == nil {
		t.Fatal("unsafe runtime WAL created for invalid config")
	}
}
