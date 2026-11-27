package daemon

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Mikeyphw/xbsctl/internal/control"
	"github.com/Mikeyphw/xbsctl/internal/tuning"
)

type r03aPowerFixture struct {
	charging, capacity, screen string
	fail                       bool
}

func (f *r03aPowerFixture) Read(p string) (string, error) {
	if f.fail {
		return "", os.ErrPermission
	}
	switch p {
	case "/sys/class/power_supply/battery/status":
		return f.charging, nil
	case "/sys/class/power_supply/battery/capacity":
		return f.capacity, nil
	}
	return "", os.ErrNotExist
}
func (f *r03aPowerFixture) Run(cmd string, args ...string) (string, error) {
	if f.fail {
		return "", os.ErrPermission
	}
	if cmd == "dumpsys" && len(args) == 1 && args[0] == "power" {
		return "mWakefulness=" + f.screen, nil
	}
	return "", os.ErrInvalid
}
func (*r03aPowerFixture) Write(string, string) error    { return os.ErrInvalid }
func (*r03aPowerFixture) Glob(string) ([]string, error) { return nil, nil }
func r03aSetup(t *testing.T) (map[string]any, *state, *tuning.Engine, string) {
	t.Helper()
	dir := t.TempDir()
	t.Setenv("XBS_RUNTIME_DIR", dir)
	ctl := filepath.Join(dir, "ctl")
	cfg := map[string]any{"header": map[string]any{"ctl_file": ctl, "automatic_events_enabled": true, "delay": 0, "low_power_threshold": 20, "low_power_release_threshold": 25}, "events": map[string]any{"charging": map[string]any{}, "screen_off": map[string]any{}, "low_power": map[string]any{}}}
	s := &state{Mode: "normal", Active: map[string]bool{}, AutoOwned: map[string]bool{}, EffectResults: map[string]any{}}
	return cfg, s, tuning.New(&r03aPowerFixture{charging: "Charging", capacity: "80", screen: "Awake"}), ctl
}
func TestR03AQueueOnceAfterStableObservation(t *testing.T) {
	cfg, s, engine, ctl := r03aSetup(t)
	_, e := automaticCycle(engine, cfg, s)
	if e != nil {
		t.Fatal(e)
	}
	req, path, e := control.NextRequest(ctl)
	if e != nil || path != "" || req.ID != "" {
		t.Fatal("queued on first sample")
	}
	_, e = automaticCycle(engine, cfg, s)
	if e != nil {
		t.Fatal(e)
	}
	req, path, e = control.NextRequest(ctl)
	if e != nil || req.Origin != "automatic" || req.Command != "start" || req.Event != "charging" {
		t.Fatalf("wrong automatic queue: %+v %q %v", req, path, e)
	}
	if _, e := automaticCycle(engine, cfg, s); e != nil {
		t.Fatal(e)
	}
	req2, path2, e := control.NextRequest(ctl)
	if e == nil || req2.ID != "" || !strings.HasSuffix(path2, ".interrupted") {
		t.Fatalf("expected interrupted claimed request detection: %+v %q %v", req2, path2, e)
	}
}
func TestR03ADisabledByDefaultAndManualNeverStopped(t *testing.T) {
	cfg, s, engine, ctl := r03aSetup(t)
	cfg["header"].(map[string]any)["automatic_events_enabled"] = false
	s.Active["charging"] = true
	for i := 0; i < 3; i++ {
		_, e := automaticCycle(engine, cfg, s)
		if e != nil {
			t.Fatal(e)
		}
	}
	req, path, e := control.NextRequest(ctl)
	if e != nil || req.ID != "" || path != "" {
		t.Fatalf("manual changed: %+v %v", req, e)
	}
}
func TestR03AOwnedStopAfterFeatureDisabled(t *testing.T) {
	cfg, s, engine, ctl := r03aSetup(t)
	cfg["header"].(map[string]any)["automatic_events_enabled"] = false
	s.Active["charging"] = true
	s.AutoOwned["charging"] = true
	_, e := automaticCycle(engine, cfg, s)
	if e != nil {
		t.Fatal(e)
	}
	r, _, e := control.NextRequest(ctl)
	if e != nil || r.Command != "stop" || r.Event != "charging" || r.Origin != "automatic" {
		t.Fatalf("did not queue owned stop: %+v %v", r, e)
	}
}
func TestR03ARejectUnownedAutomaticStop(t *testing.T) {
	cfg, s, _, _ := r03aSetup(t)
	s.Active["charging"] = true
	err := validateAutoRequest(cfg, s, control.Request{Command: "stop", Event: "charging", Origin: "automatic"})
	if err == nil {
		t.Fatal("unowned stop accepted")
	}
	s.AutoOwned["charging"] = true
	if err := validateAutoRequest(cfg, s, control.Request{Command: "stop", Event: "charging", Origin: "automatic"}); err != nil {
		t.Fatal(err)
	}
}
func TestR03ARejectAutomaticStartAfterOptOut(t *testing.T) {
	cfg, s, _, _ := r03aSetup(t)
	cfg["header"].(map[string]any)["automatic_events_enabled"] = false
	if err := validateAutoRequest(cfg, s, control.Request{Command: "start", Event: "charging", Origin: "automatic"}); err == nil {
		t.Fatal("stale start accepted")
	}
}
func TestR03AUnknownSensorNeverQueues(t *testing.T) {
	cfg, s, _, ctl := r03aSetup(t)
	runner := &r03aPowerFixture{fail: true}
	engine := tuning.New(runner)
	for i := 0; i < 4; i++ {
		_, e := automaticCycle(engine, cfg, s)
		if e != nil {
			t.Fatal(e)
		}
	}
	r, p, e := control.NextRequest(ctl)
	if e != nil || r.ID != "" || p != "" {
		t.Fatalf("unknown probe queued command: %+v %q %v", r, p, e)
	}
}
func TestR03AOwnedSorted(t *testing.T) {
	s := &state{AutoOwned: map[string]bool{"low_power": true, "charging": true, "screen_off": false}}
	names := autoOwnedList(s)
	if len(names) != 2 || names[0] != "charging" || names[1] != "low_power" {
		t.Fatalf("nondeterministic owners: %v", names)
	}
}
