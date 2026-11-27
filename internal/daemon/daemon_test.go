package daemon

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Mikeyphw/xbsctl/internal/config"
	"github.com/Mikeyphw/xbsctl/internal/control"
	"github.com/Mikeyphw/xbsctl/internal/tuning"
)

func TestDaemonOneShotProcessesStartCommand(t *testing.T) {
	root := t.TempDir()
	t.Setenv("XBS_EXTERNAL_ROOT", filepath.Join(root, "external"))
	t.Setenv("XBS_RUNTIME_DIR", filepath.Join(root, "runtime"))
	t.Setenv("XBS_EVENT_BRIDGE_ROOT", filepath.Join(root, "private"))
	cfg := config.Default()
	if _, err := config.Save(cfg); err != nil {
		t.Fatal(err)
	}
	ctl, _ := config.GetByKey(cfg, "header.ctl_file")
	if err := os.WriteFile(ctl.(string), []byte("start manual\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	var out strings.Builder
	if code := Main([]string{"--json", "--once"}, &out, &out); code != 0 {
		t.Fatalf("code=%d out=%s", code, out.String())
	}
	statusData, err := os.ReadFile(config.ResolvePaths().Status)
	if err != nil {
		t.Fatal(err)
	}
	var status map[string]any
	if err := json.Unmarshal(statusData, &status); err != nil {
		t.Fatal(err)
	}
	active := status["active_events"].([]any)
	if len(active) != 1 || active[0] != "manual" {
		t.Fatalf("status=%s", statusData)
	}
	if _, err := os.Stat(filepath.Join(config.ResolvePaths().Runtime, "event_changes.json")); err != nil {
		t.Fatal(err)
	}
}

func TestDaemonOneShotPublishesEachSemanticTransitionExactlyOnce(t *testing.T) {
	tests := []struct {
		name      string
		command   string
		wantType  string
		wantName  string
		wantState string
	}{
		{name: "start", command: "start manual\n", wantType: "profile", wantName: "manual", wantState: "active"},
		{name: "stop", command: "stop manual\n", wantType: "profile", wantName: "manual", wantState: "inactive"},
		{name: "reload", command: "reload\n", wantType: "reload", wantName: "reload", wantState: "applied"},
		{name: "pause", command: "pause\n", wantType: "mode", wantName: "pause", wantState: "active"},
		{name: "safe", command: "safe\n", wantType: "safe_mode", wantName: "safe_mode", wantState: "active"},
		{name: "resume", command: "resume\n", wantType: "safe_mode", wantName: "safe_mode", wantState: "inactive"},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			root := t.TempDir()
			t.Setenv("XBS_EXTERNAL_ROOT", filepath.Join(root, "external"))
			t.Setenv("XBS_RUNTIME_DIR", filepath.Join(root, "runtime"))
			t.Setenv("XBS_EVENT_BRIDGE_ROOT", filepath.Join(root, "private"))
			cfg := config.Default()
			if _, err := config.Save(cfg); err != nil {
				t.Fatal(err)
			}
			ctl, _ := config.GetByKey(cfg, "header.ctl_file")
			if err := os.WriteFile(ctl.(string), []byte(tt.command), 0o644); err != nil {
				t.Fatal(err)
			}

			var out strings.Builder
			if code := Main([]string{"--json", "--once"}, &out, &out); code != 0 {
				t.Fatalf("code=%d out=%s", code, out.String())
			}

			sequenceData, err := os.ReadFile(filepath.Join(root, "runtime", "event_bridge_v1.seq"))
			if err != nil {
				t.Fatal(err)
			}
			if got := strings.TrimSpace(string(sequenceData)); got != "1" {
				t.Fatalf("sequence=%q, want exactly one publication", got)
			}

			eventData, err := os.ReadFile(filepath.Join(root, "runtime", "event_changes.json"))
			if err != nil {
				t.Fatal(err)
			}
			var event map[string]any
			if err := json.Unmarshal(eventData, &event); err != nil {
				t.Fatal(err)
			}
			change, ok := event["last_change"].(map[string]any)
			if !ok {
				t.Fatalf("last_change=%#v, want retained transition; event=%s", event["last_change"], eventData)
			}
			if change["type"] != tt.wantType || change["name"] != tt.wantName || change["state"] != tt.wantState {
				t.Fatalf("last_change=%#v, want type=%q name=%q state=%q", change, tt.wantType, tt.wantName, tt.wantState)
			}
		})
	}
}

func TestFailedEffectApplicationDoesNotPublishActiveState(t *testing.T) {
	root := t.TempDir()
	t.Setenv("XBS_EXTERNAL_ROOT", filepath.Join(root, "external"))
	t.Setenv("XBS_RUNTIME_DIR", filepath.Join(root, "runtime"))
	cfg := config.Default()
	events := cfg["events"].(map[string]any)
	events["manual"] = map[string]any{"handle_apps": "kill"}
	ctl, _ := config.GetByKey(cfg, "header.ctl_file")
	if err := os.MkdirAll(filepath.Dir(ctl.(string)), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(ctl.(string), []byte("start manual\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	s := &state{Mode: "normal", Active: map[string]bool{}, EffectResults: map[string]any{}}
	change, err := processCtl(tuning.New(nil), cfg, s, false)
	if err == nil {
		t.Fatal("expected effect application failure without Android pm")
	}
	if change != nil {
		t.Fatalf("failed application returned transition: %#v", change)
	}
	if s.Active["manual"] {
		t.Fatal("failed effect application marked event active")
	}
}

func TestReloadFailurePreservesLiveConfigurationAndActiveState(t *testing.T) {
	root := t.TempDir()
	t.Setenv("XBS_EXTERNAL_ROOT", filepath.Join(root, "external"))
	t.Setenv("XBS_RUNTIME_DIR", filepath.Join(root, "runtime"))
	cfg := config.Default()
	if _, err := config.Save(cfg); err != nil {
		t.Fatal(err)
	}
	s := &state{Mode: "normal", Active: map[string]bool{"manual": true}, LogLevel: get(cfg, "header.log_level"), EffectResults: map[string]any{}}
	oldLevel := get(cfg, "header.log_level")
	// Bypass Save deliberately to emulate a malformed on-disk replacement.
	if err := os.WriteFile(config.ResolvePaths().Config, []byte(`{"header":null}`), 0644); err != nil {
		t.Fatal(err)
	}
	ctl := ctlPath(cfg)
	if _, err := control.QueueRequest(ctl, "reload", ""); err != nil {
		t.Fatal(err)
	}
	change, err := processCtl(tuning.New(nil), cfg, s, true)
	if err == nil || change != nil {
		t.Fatalf("reload should fail: change=%v err=%v", change, err)
	}
	if get(cfg, "header.log_level") != oldLevel {
		t.Fatalf("live config mutated on failed reload")
	}
	if !s.Active["manual"] {
		t.Fatalf("active state lost on failed reload")
	}
}

func TestDryRunConsumesLegacyControlOnceAndPublishesTruth(t *testing.T) {
	root := t.TempDir()
	t.Setenv("XBS_EXTERNAL_ROOT", filepath.Join(root, "external"))
	t.Setenv("XBS_RUNTIME_DIR", filepath.Join(root, "runtime"))
	t.Setenv("XBS_EVENT_BRIDGE_ROOT", filepath.Join(root, "private"))
	cfg := config.Default()
	if _, err := config.Save(cfg); err != nil {
		t.Fatal(err)
	}
	ctl := ctlPath(cfg)
	if err := os.MkdirAll(filepath.Dir(ctl), 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(ctl, []byte("start manual\n"), 0644); err != nil {
		t.Fatal(err)
	}
	s := &state{Mode: "normal", Active: map[string]bool{}, EffectResults: map[string]any{}, DryRun: true}
	engine := tuning.New(nil)
	change, err := processCtl(engine, cfg, s, true)
	if err != nil || change == nil {
		t.Fatalf("change=%v err=%v", change, err)
	}
	change, err = processCtl(engine, cfg, s, true)
	if err != nil || change != nil {
		t.Fatalf("command reprocessed: change=%v err=%v", change, err)
	}
	if !dryRunState(s) {
		t.Fatal("dry_run status not truthful")
	}
}

func TestSuccessfulCommandClearsStaleError(t *testing.T) {
	root := t.TempDir()
	t.Setenv("XBS_EXTERNAL_ROOT", filepath.Join(root, "external"))
	t.Setenv("XBS_RUNTIME_DIR", filepath.Join(root, "runtime"))
	cfg := config.Default()
	ctl := ctlPath(cfg)
	if err := os.MkdirAll(filepath.Dir(ctl), 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(ctl, []byte("start manual\n"), 0644); err != nil {
		t.Fatal(err)
	}
	s := &state{Mode: "normal", Active: map[string]bool{}, EffectResults: map[string]any{}, LastError: "old", LastErrorTime: "old", DryRun: true}
	if _, err := processCtl(tuning.New(nil), cfg, s, true); err != nil {
		t.Fatal(err)
	}
	if s.LastError != "" || s.LastErrorTime != "" {
		t.Fatalf("stale error retained: %#v", s)
	}
}

func TestRuntimeStateRecoversActiveEventsAndSnapshot(t *testing.T) {
	root := t.TempDir()
	t.Setenv("XBS_EXTERNAL_ROOT", filepath.Join(root, "external"))
	t.Setenv("XBS_RUNTIME_DIR", filepath.Join(root, "runtime"))
	cfg := config.Default()
	s := &state{Mode: "paused", Active: map[string]bool{"manual": true}, EffectResults: map[string]any{}}
	e := tuning.New(nil)
	e.LoadSnapshot(map[string]string{"file:/tmp/example": "before"})
	if err := persistRuntimeState(e, cfg, s); err != nil {
		t.Fatal(err)
	}
	s2 := &state{Mode: "normal", Active: map[string]bool{}, EffectResults: map[string]any{}}
	e2 := tuning.New(nil)
	if err := recoverRuntimeState(e2, cfg, s2, true); err == nil || !strings.Contains(err.Error(), "dry-run cannot reconcile") {
		t.Fatalf("unrestored privileged snapshot accepted in dry-run: %v", err)
	}
	if s2.Mode != "paused" || !s2.Active["manual"] {
		t.Fatalf("state=%+v", s2)
	}
	if e2.Snapshot()["file:/tmp/example"] != "before" {
		t.Fatalf("durable recovery evidence disappeared: %v", e2.Snapshot())
	}
}

func TestBridgePathsFollowResolvedRuntimeAndStatus(t *testing.T) {
	root := t.TempDir()
	t.Setenv("XBS_RUNTIME_DIR", filepath.Join(root, "custom-runtime"))
	t.Setenv("XBS_EXTERNAL_STATUS", filepath.Join(root, "custom-status.json"))
	p := bridgePaths()
	if p.Runtime != filepath.Join(root, "custom-runtime") || p.Status != filepath.Join(root, "custom-status.json") {
		t.Fatalf("paths=%+v", p)
	}
	if p.Event != filepath.Join(root, "custom-runtime", "event_changes.json") {
		t.Fatalf("event=%s", p.Event)
	}
}
