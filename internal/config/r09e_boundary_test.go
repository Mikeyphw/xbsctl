package config

import (
	"strings"
	"testing"
)

func TestR09EConfigBoundaryGuards(t *testing.T) {
	for _, name := range []string{"manual", "a", "a_1-b", "a123", strings.Repeat("x", 64)} {
		if !ValidEventName(name) {
			t.Fatalf("valid event refused %q", name)
		}
	}
	for _, name := range []string{"", "A", "_a", "-a", "1 a", "é", "a/b", "a.b", strings.Repeat("x", 65)} {
		if ValidEventName(name) {
			t.Fatalf("unsafe event name accepted %q", name)
		}
	}
	m := map[string]any{"header": map[string]any{"delay": 3}}
	if v, ok := GetByKey(m, "header.delay"); !ok || v != 3 {
		t.Fatalf("nested config read: %#v %t", v, ok)
	}
	if _, ok := GetByKey(m, "header.unknown"); ok {
		t.Fatal("missing nested key accepted")
	}
	if _, ok := GetByKey(m, "header.delay.extra"); ok {
		t.Fatal("descended into scalar")
	}
	if _, ok := GetByKey(m, "header..delay"); ok {
		t.Fatal("empty component accepted")
	}
	if err := SetByKey(m, "header.active", "true"); err != nil {
		t.Fatal(err)
	}
	if v, ok := GetByKey(m, "header.active"); !ok || v != true {
		t.Fatalf("boolean not coerced: %#v", v)
	}
	if err := SetByKey(m, "header.delay", "15"); err != nil {
		t.Fatal(err)
	}
	if v, ok := GetByKey(m, "header.delay"); !ok || v != 15 {
		t.Fatalf("integer not coerced: %#v", v)
	}
	if err := SetByKey(m, "  ", "1"); err == nil {
		t.Fatal("empty key accepted")
	}
}
