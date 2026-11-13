package cli

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"github.com/Mikeyphw/xbsctl/internal/config"
)

// R04-01..03: exercise the real Go CLI against disposable user configuration.
// This test never calls root, a daemon, ADB, or a module installer.
func r04IsolatedConfig(t *testing.T) config.Paths {
	t.Helper()
	root := t.TempDir()
	t.Setenv("XBS_EXTERNAL_ROOT", filepath.Join(root, "shared"))
	t.Setenv("XBS_RUNTIME_DIR", filepath.Join(root, "runtime"))
	t.Setenv("XBS_EXTERNAL_LIST_DIR", filepath.Join(root, "shared", "lists"))
	t.Setenv("XBS_EXTERNAL_LOG_DIR", filepath.Join(root, "shared", "logs"))
	return config.ResolvePaths()
}

func r04Invoke(t *testing.T, args ...string) {
	t.Helper()
	var stdout, stderr bytes.Buffer
	if rc := Main(args, &stdout, &stderr); rc != 0 {
		t.Fatalf("Go CLI %v refused rc=%d stdout=%s stderr=%s", args, rc, stdout.String(), stderr.String())
	}
}

func TestR04FreshInitAndValidationAreIdempotent(t *testing.T) {
	p := r04IsolatedConfig(t)
	r04Invoke(t, "init")
	first, err := os.ReadFile(p.Config)
	if err != nil {
		t.Fatal(err)
	}
	r04Invoke(t, "init")
	second, err := os.ReadFile(p.Config)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(first, second) {
		t.Fatal("second init rewrote previously initialized config")
	}
	r04Invoke(t, "validate")
}

func TestR04UserProfilesAndConfigSurviveInitAndValidation(t *testing.T) {
	p := r04IsolatedConfig(t)
	if err := os.MkdirAll(p.ListDir, 0755); err != nil {
		t.Fatal(err)
	}
	cfg := config.Default()
	cfg["header"].(map[string]any)["delay"] = 19
	cfg["events"].(map[string]any)["custom_profile"] = map[string]any{"allowlist": "my-list.txt"}
	original, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(p.Config, append(original, '\n'), 0600); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(p.ListDir, "my-list.txt")
	contents := []byte("com.example.personal\n")
	if err := os.WriteFile(path, contents, 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(p.Essentials, []byte("personal.essentials\n"), 0600); err != nil {
		t.Fatal(err)
	}
	r04Invoke(t, "init")
	r04Invoke(t, "validate")
	after, err := os.ReadFile(p.Config)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(after, append(original, '\n')) {
		t.Fatal("Go init changed user config bytes")
	}
	afterList, err := os.ReadFile(path)
	if err != nil || !bytes.Equal(afterList, contents) {
		t.Fatal("Go init changed user profile/list")
	}
	essentials, err := os.ReadFile(p.Essentials)
	if err != nil || string(essentials) != "personal.essentials\n" {
		t.Fatal("Go init replaced user essentials")
	}
}

func TestR04InvalidExistingConfigRemainsUntouchedAndRefuses(t *testing.T) {
	p := r04IsolatedConfig(t)
	if err := os.MkdirAll(filepath.Dir(p.Config), 0755); err != nil {
		t.Fatal(err)
	}
	bad := []byte("{invalid-user-json\n")
	if err := os.WriteFile(p.Config, bad, 0600); err != nil {
		t.Fatal(err)
	}
	var stdout, stderr bytes.Buffer
	if rc := Main([]string{"init"}, &stdout, &stderr); rc == 0 {
		t.Fatal("invalid config accepted")
	}
	after, err := os.ReadFile(p.Config)
	if err != nil || !bytes.Equal(after, bad) {
		t.Fatal("invalid config was overwritten or deleted")
	}
}
