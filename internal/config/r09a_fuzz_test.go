package config

import (
	"strings"
	"testing"
)

// FuzzR09AEventName exercises the production event grammar, including Unicode
// and untrusted path separators, without modifying any system state.
func FuzzR09AEventName(f *testing.F) {
	for _, seed := range []string{"manual", "a", "screen-off", "ab_12", "", "ABC", "../escape", "a/b", "a\x00b", "météo", strings.Repeat("a", 65)} {
		f.Add(seed)
	}
	f.Fuzz(func(t *testing.T, name string) {
		allowed := ValidEventName(name)
		if !allowed {
			return
		}
		if len(name) == 0 || len(name) > 64 || strings.ContainsAny(name, "/.\\\x00\n\r\t") {
			t.Fatalf("unsafe accepted event name: %q", name)
		}
		if name[0] < 'a' || name[0] > 'z' { // Numeric first characters are accepted by the actual contract.
			if name[0] < '0' || name[0] > '9' {
				t.Fatalf("invalid initial byte accepted: %q", name)
			}
		}
		for i := 0; i < len(name); i++ {
			c := name[i]
			if !((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || (i > 0 && (c == '_' || c == '-'))) {
				t.Fatalf("invalid byte accepted at %d: %q", i, name)
			}
		}
	})
}

// FuzzR09ARequiredPathTypes probes the actual config validator; malformed
// paths must be rejected before any Save or filesystem operation occurs.
func FuzzR09ARequiredPathTypes(f *testing.F) {
	for _, seed := range []string{"/tmp/xbs.ctl", "", " ", "\x00", "/safe\x00evil", "../relative", "root"} {
		f.Add(seed)
	}
	f.Fuzz(func(t *testing.T, value string) {
		cfg := Default()
		header, ok := cfg["header"].(map[string]any)
		if !ok {
			t.Fatal("default header missing")
		}
		header["ctl_file"] = value
		errors := Validate(cfg)
		invalid := strings.TrimSpace(value) == "" || strings.ContainsRune(value, '\x00')
		if invalid && len(errors) == 0 {
			t.Fatalf("invalid ctl_file accepted: %q", value)
		}
	})
}

func TestR09AConfigTypeNegatives(t *testing.T) {
	for _, value := range []any{nil, true, 4, map[string]any{"a": 1}, []string{"/tmp"}} {
		cfg := Default()
		cfg["header"].(map[string]any)["ctl_file"] = value
		if errs := Validate(cfg); len(errs) == 0 {
			t.Fatalf("non-string ctl_file accepted: %#v", value)
		}
	}
}
