package config

import (
	"encoding/json"
	"strings"
	"testing"
)

func r09bCloneDefault(t *testing.T) map[string]any {
	t.Helper()
	b, err := json.Marshal(Default())
	if err != nil {
		t.Fatal(err)
	}
	var cfg map[string]any
	if err := json.Unmarshal(b, &cfg); err != nil {
		t.Fatal(err)
	}
	return cfg
}
func TestR09BHeaderTypeFaultMatrix(t *testing.T) {
	if errs := Validate(r09bCloneDefault(t)); len(errs) != 0 {
		t.Fatalf("baseline invalid before mutation: %v", errs)
	}
	mutations := []struct {
		label  string
		mutate func(map[string]any)
	}{
		{"null_required_path", func(c map[string]any) { c["header"].(map[string]any)["ctl_file"] = nil }},
		{"numeric_required_path", func(c map[string]any) { c["header"].(map[string]any)["lock_file"] = 42 }},
		{"boolean_required_path", func(c map[string]any) { c["header"].(map[string]any)["log_file"] = true }},
		{"nul_required_path", func(c map[string]any) { c["header"].(map[string]any)["essentials_allowlist"] = "/tmp/a\x00b" }},
		{"missing_header", func(c map[string]any) { delete(c, "header") }},
		{"invalid_delay_negative", func(c map[string]any) { c["header"].(map[string]any)["delay"] = -1 }},
		{"invalid_delay_overflow", func(c map[string]any) { c["header"].(map[string]any)["delay"] = 301 }},
		{"invalid_hysteresis", func(c map[string]any) {
			c["header"].(map[string]any)["low_power_threshold"] = 90
			c["header"].(map[string]any)["low_power_release_threshold"] = 80
		}},
	}
	for _, v := range mutations {
		t.Run(v.label, func(t *testing.T) {
			c := r09bCloneDefault(t)
			v.mutate(c)
			if len(Validate(c)) == 0 {
				t.Fatalf("invalid config accepted: %s", v.label)
			}
		})
	}
}
func TestR09BEventGrammarFaultMatrix(t *testing.T) {
	if errs := Validate(r09bCloneDefault(t)); len(errs) != 0 {
		t.Fatalf("baseline invalid before mutation: %v", errs)
	}
	for _, name := range []string{"manual", "a_1-b", "events123"} {
		if !ValidEventName(name) {
			t.Errorf("legitimate event refused: %q", name)
		}
	}
	for _, name := range []string{"", "UPPER", "-leading", "_leading", "has space", strings.Repeat("a", 65), "a/../b", "nul\x00x"} {
		if ValidEventName(name) {
			t.Errorf("unsafe event accepted: %q", name)
		}
	}
	for _, change := range []func(map[string]any){
		func(c map[string]any) { c["events"] = "not an object" },
		func(c map[string]any) { c["events"].(map[string]any)["UPPER"] = map[string]any{} },
		func(c map[string]any) {
			c["events"].(map[string]any)["manual"] = map[string]any{"handle_apps": "elevate"}
		},
		func(c map[string]any) { c["events"].(map[string]any)["manual"] = map[string]any{"kill_wifi": "maybe"} },
	} {
		c := r09bCloneDefault(t)
		change(c)
		if len(Validate(c)) == 0 {
			t.Fatalf("invalid event config accepted: %v", c["events"])
		}
	}
}
