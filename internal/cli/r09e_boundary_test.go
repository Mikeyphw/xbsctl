package cli

import (
	"encoding/json"
	"testing"
)

func TestR09ECLIArgumentAndJSONRefusals(t *testing.T) {
	for _, v := range []struct {
		value any
		want  int
		valid bool
	}{
		{int(0), 0, true}, {int64(9), 9, true}, {float64(3), 3, true}, {float64(3.25), 0, false},
		{json.Number("27"), 27, true}, {json.Number("2.4"), 0, false}, {" 12 ", 12, true},
		{true, 0, false}, {nil, 0, false},
	} {
		got, ok := intFromAny(v.value)
		if ok != v.valid || (ok && got != v.want) {
			t.Fatalf("intFromAny(%#v)=(%d,%t)", v.value, got, ok)
		}
	}
	for _, raw := range []string{"{", "[]", "null", `{"header":`, `"string"`} {
		cfg, err := configFromArg([]string{raw})
		if raw == "null" {
			if err != nil || cfg != nil {
				t.Fatalf("null parse %#v %v", cfg, err)
			}
			continue
		}
		if raw == "[]" || raw == `"string"` {
			if err == nil {
				t.Fatalf("nonobject accepted %q", raw)
			}
			continue
		}
		if err == nil {
			t.Fatalf("invalid JSON accepted %q: %#v", raw, cfg)
		}
	}
	g, remaining := parseGlobals([]string{"--quiet", "status", "--no-interactive", "--json"})
	if !g.quiet || !g.jsonMode || !g.noInteractive || len(remaining) != 1 || remaining[0] != "status" {
		t.Fatalf("global option parse %#v %#v", g, remaining)
	}
}
