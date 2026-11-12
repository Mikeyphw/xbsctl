package daemon

import (
	"github.com/Mikeyphw/xbsctl/internal/tuning"
	"strings"
	"testing"
)

func TestR01DLeaseDomainsAndSorting(t *testing.T) {
	ev := tuning.Event{HandleCores: "cpu1", DisableCores: "false", HandleApps: "nice", HandleGMS: "kill", Doze: "false", KillWiFi: true, LowRAM: true}
	got := strings.Join(resourceLeases(ev), ",")
	want := "app-packages,cpu-governor,gms,low-ram,process-nice,wifi"
	if got != want {
		t.Fatalf("domains=%q want %q", got, want)
	}
}
func TestR01DIndependentDomainsCanCoexist(t *testing.T) {
	cfg := ownershipFixture()
	leases, err := activeResourceLeases(cfg, map[string]bool{"boot": true, "charging": true, "screen_off": true, "manual": true})
	if err != nil {
		t.Fatal(err)
	}
	if len(leases) != 3 {
		t.Fatalf("expected WiFi/lowRAM/Doze, got=%v", leases)
	}
}
func TestR01DSameDomainCollisionFailsBeforeApply(t *testing.T) {
	cfg := ownershipFixture()
	cfg["events"].(map[string]any)["charging"].(map[string]any)["kill_wifi"] = true
	if err := checkStartOwnership(cfg, map[string]bool{"boot": true}, "charging"); err == nil || !strings.Contains(err.Error(), "conflicting privileged owners") {
		t.Fatalf("collision permitted: %v", err)
	}
}
func TestR01DSourceLeaseMetadataIgnoresPolicyProfiles(t *testing.T) {
	cfg := ownershipFixture()
	active := map[string]bool{"boot": true, "manual": true, "charging": true}
	expected := declaredLeases(cfg, active)
	if len(expected) != 2 || len(expected["manual"]) != 0 {
		t.Fatalf("incorrect declared leases %v", expected)
	}
}
