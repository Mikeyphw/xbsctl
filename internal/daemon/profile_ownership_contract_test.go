package daemon

import (
	"github.com/Mikeyphw/xbsctl/internal/tuning"
	"strings"
	"testing"
)

func ownershipFixture() map[string]any {
	return map[string]any{"events": map[string]any{
		"template":   map[string]any{},
		"boot":       map[string]any{"kill_wifi": true},
		"charging":   map[string]any{"low_ram": true},
		"manual":     map[string]any{},
		"screen_off": map[string]any{"doze": "light"},
	}}
}

func TestR01CPrivilegeClassificationAllFamilies(t *testing.T) {
	base := tuning.Event{HandleCores: "false", DisableCores: "false", HandleApps: "false", HandleGMS: "false", Doze: "false"}
	if hasPrivilegedEffects(base) {
		t.Fatal("policy-only event mistaken for privileged mutator")
	}
	cases := []struct {
		name string
		edit func(*tuning.Event)
	}{
		{"cpu_governor", func(e *tuning.Event) { e.HandleCores = "cpu1" }},
		{"cpu_online", func(e *tuning.Event) { e.DisableCores = "cpu2" }},
		{"app_kill", func(e *tuning.Event) { e.HandleApps = "kill" }},
		{"app_nice", func(e *tuning.Event) { e.HandleApps = "nice" }},
		{"gms", func(e *tuning.Event) { e.HandleGMS = "kill" }},
		{"process_nice", func(e *tuning.Event) { e.HandleProc = true }},
		{"low_ram", func(e *tuning.Event) { e.LowRAM = true }},
		{"doze", func(e *tuning.Event) { e.Doze = "deep" }},
		{"wifi", func(e *tuning.Event) { e.KillWiFi = true }},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			e := base
			c.edit(&e)
			if !hasPrivilegedEffects(e) {
				t.Fatalf("lost mutation family %s", c.name)
			}
		})
	}
}

func TestR01COnlyOnePrivilegedOwner(t *testing.T) {
	cfg := ownershipFixture()
	owner, err := exclusiveActiveOwner(cfg, map[string]bool{"boot": true, "manual": true})
	if err != nil || owner != "boot" {
		t.Fatalf("owner=%q err=%v", owner, err)
	}
	if err := checkStartOwnership(cfg, map[string]bool{"boot": true, "manual": true}, "charging"); err == nil || !strings.Contains(err.Error(), "conflicting privileged owners") {
		t.Fatalf("overlapping mutators allowed: %v", err)
	}
}

func TestR01COwnerCheckRefusesUnrelatedPrivilegedFamilies(t *testing.T) {
	cfg := ownershipFixture()
	if err := checkStartOwnership(cfg, map[string]bool{"boot": true}, "screen_off"); err == nil {
		t.Fatal("global snapshot allows disjoint owners to overwrite each other")
	}
}

func TestR01CInactiveProfilesDoNotOwnEffects(t *testing.T) {
	cfg := ownershipFixture()
	owner, err := exclusiveActiveOwner(cfg, map[string]bool{"boot": false, "charging": true, "manual": true})
	if err != nil || owner != "charging" {
		t.Fatalf("owner=%q err=%v", owner, err)
	}
}

func TestR01CUnknownActiveProfileFailsClosed(t *testing.T) {
	_, err := exclusiveActiveOwner(ownershipFixture(), map[string]bool{"unknown": true})
	if err == nil || !strings.Contains(err.Error(), "resolve active profile") {
		t.Fatalf("unresolved active event should refuse: %v", err)
	}
}
