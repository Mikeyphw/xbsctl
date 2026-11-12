package daemon

import (
	"strings"
	"testing"

	"github.com/Mikeyphw/xbsctl/internal/control"
)

func TestR03BManualOwnershipCannotBeStolen(t *testing.T) {
	cfg, s, _, _ := r03aSetup(t)
	s.Active["charging"] = true
	err := validateAutoRequest(cfg, s, control.Request{Origin: "automatic", Command: "start", Event: "charging"})
	if err == nil || !strings.Contains(err.Error(), "manually owned") {
		t.Fatalf("manual ownership stolen: %v", err)
	}
}
func TestR03BLeaseOverlapDefersLowerPriority(t *testing.T) {
	cfg, s, _, _ := r03aSetup(t)
	cfg["events"].(map[string]any)["charging"].(map[string]any)["kill_wifi"] = true
	cfg["events"].(map[string]any)["screen_off"].(map[string]any)["kill_wifi"] = true
	desired, deferred, err := selectAutoDesired(cfg, s, []string{"charging", "screen_off"})
	if err != nil || len(desired) != 1 || desired[0] != "charging" || len(deferred) != 1 {
		t.Fatalf("lease planning=%v deferred=%v err=%v", desired, deferred, err)
	}
}
func TestR03BManualOwnerDefersMatchingResource(t *testing.T) {
	cfg, s, _, _ := r03aSetup(t)
	events := cfg["events"].(map[string]any)
	events["charging"].(map[string]any)["kill_wifi"] = true
	events["manual"] = map[string]any{"kill_wifi": true}
	s.Active["manual"] = true
	desired, deferred, err := selectAutoDesired(cfg, s, []string{"charging"})
	if err != nil || len(desired) != 0 || len(deferred) != 1 || !strings.Contains(deferred[0], "manual") {
		t.Fatalf("manual conflict=%v deferred=%v err=%v", desired, deferred, err)
	}
}
func TestR03BUnknownObservationClearsDebounceEpoch(t *testing.T) {
	cfg, s, engine, _ := r03aSetup(t)
	if _, err := automaticCycle(engine, cfg, s); err != nil {
		t.Fatal(err)
	}
	if s.AutoPlanner == nil {
		t.Fatal("no planner")
	}
	engine.R = &r03aPowerFixture{fail: true}
	if _, err := automaticCycle(engine, cfg, s); err != nil {
		t.Fatal(err)
	}
	if s.AutoPlanner != nil {
		t.Fatal("unknown observation retained debounce count")
	}
}
