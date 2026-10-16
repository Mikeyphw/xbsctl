package config

import (
	"os"
	"path/filepath"
	"testing"
)

func setup(t *testing.T) string {
	t.Helper()
	root := t.TempDir()
	t.Setenv("XBS_MODULE_DIR", filepath.Join(root, "module"))
	t.Setenv("WG_ANDROID_SHARED_ROOT", filepath.Join(root, "shared"))
	t.Setenv("XBS_EXTERNAL_ROOT", filepath.Join(root, "shared", ".config", "xbs"))
	t.Setenv("XBS_RUNTIME_DIR", filepath.Join(root, "runtime"))
	return root
}

func TestDefaultLoadSaveRoundTrip(t *testing.T) {
	setup(t)
	cfg, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if errs := Validate(cfg); len(errs) != 0 {
		t.Fatalf("default invalid: %v", errs)
	}
	if err := SetByKey(cfg, "events.boot.handle_apps", "nice"); err != nil {
		t.Fatal(err)
	}
	if _, err := Save(cfg); err != nil {
		t.Fatal(err)
	}
	loaded, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	got, ok := GetByKey(loaded, "events.boot.handle_apps")
	if !ok || got != "nice" {
		t.Fatalf("got %#v ok=%v", got, ok)
	}
}

func TestCorruptJSONFailsClosed(t *testing.T) {
	setup(t)
	p := ResolvePaths()
	if err := os.MkdirAll(filepath.Dir(p.Config), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(p.Config, []byte("{"), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := Load(); err == nil {
		t.Fatal("expected corrupt JSON to fail")
	}
}

func TestRejectsSymlinkConfig(t *testing.T) {
	root := setup(t)
	p := ResolvePaths()
	if err := os.MkdirAll(filepath.Dir(p.Config), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(filepath.Join(root, "elsewhere"), p.Config); err != nil {
		t.Skipf("symlink unavailable: %v", err)
	}
	if _, err := Save(Default()); err == nil {
		t.Fatal("expected symlink save rejection")
	}
}

func TestRequiredPathsAreStrictStrings(t *testing.T) {
	for _, bad := range []any{nil, 42, true, "", "   ", "bad\x00path"} {
		cfg := Default()
		cfg["header"].(map[string]any)["ctl_file"] = bad
		if errs := Validate(cfg); len(errs) == 0 {
			t.Fatalf("ctl_file=%#v accepted", bad)
		}
	}
}
func TestCanonicalEventNameGrammar(t *testing.T) {
	for _, good := range []string{"manual", "custom_event", "game-mode", "x1"} {
		if !ValidEventName(good) {
			t.Fatalf("rejected %q", good)
		}
	}
	for _, bad := range []string{"", "Bad", "has space", "_leading", "a/b", "a.b"} {
		if ValidEventName(bad) {
			t.Fatalf("accepted %q", bad)
		}
	}
	cfg := Default()
	cfg["events"].(map[string]any)["Bad Name"] = map[string]any{}
	if errs := Validate(cfg); len(errs) == 0 {
		t.Fatal("invalid configured event accepted")
	}
}
