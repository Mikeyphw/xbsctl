package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestLoadRejectsSemanticallyInvalidInitialConfig(t *testing.T) {
	for _, tc := range []struct{ name, data, expected string }{
		{"bad_event_type", `{"events":{"manual":"invalid"}}`, "events.manual"},
		{"bad_delay", `{"header":{"delay":"invalid"}}`, "header.delay"},
		{"bad_version", `{"header":{"version":333}}`, "header.version"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			setup(t)
			path := ResolvePaths().Config
			if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
				t.Fatal(err)
			}
			if err := os.WriteFile(path, []byte(tc.data), 0o600); err != nil {
				t.Fatal(err)
			}
			_, err := Load()
			if err == nil || !strings.Contains(err.Error(), tc.expected) {
				t.Fatalf("invalid startup config accepted or misdiagnosed: %v", err)
			}
		})
	}
}
