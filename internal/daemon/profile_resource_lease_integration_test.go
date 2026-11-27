package daemon

import (
	"errors"
	"github.com/Mikeyphw/xbsctl/internal/config"
	"github.com/Mikeyphw/xbsctl/internal/tuning"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

type r01dRootRecorder struct {
	r01cRootRecorder
	failLowRestore bool
}

func (r *r01dRootRecorder) Run(n string, args ...string) (string, error) {
	cmd := strings.TrimSpace(n + " " + strings.Join(args, " "))
	if cmd == "getprop ro.config.low_ram" {
		r.calls = append(r.calls, cmd)
		return "false", nil
	}
	if cmd == "resetprop -n ro.config.low_ram false" && r.failLowRestore {
		r.calls = append(r.calls, cmd)
		return "", errors.New("low-ram revert refused")
	}
	return r.r01cRootRecorder.Run(n, args...)
}
func r01dEnv(t *testing.T) (map[string]any, *state, *tuning.Engine, *r01dRootRecorder, string) {
	t.Helper()
	root := t.TempDir()
	t.Setenv("XBS_EXTERNAL_ROOT", filepath.Join(root, "external"))
	t.Setenv("XBS_RUNTIME_DIR", filepath.Join(root, "runtime"))
	cfg := config.Default()
	cfg["events"].(map[string]any)["boot"].(map[string]any)["kill_wifi"] = true
	cfg["events"].(map[string]any)["charging"].(map[string]any)["low_ram"] = true
	s := &state{Mode: "normal", Active: map[string]bool{}, EffectResults: map[string]any{}}
	r := &r01dRootRecorder{}
	e := tuning.New(r)
	bindEngineJournal(e, cfg, s)
	return cfg, s, e, r, ctlPath(cfg)
}
func TestR01DStoppingOneLeaseDoesNotRestorePeer(t *testing.T) {
	cfg, s, e, r, ctl := r01dEnv(t)
	for _, name := range []string{"boot", "charging"} {
		if err := r01cCommand(t, ctl, e, cfg, s, "start", name); err != nil {
			t.Fatal(err)
		}
	}
	if err := r01cCommand(t, ctl, e, cfg, s, "stop", "boot"); err != nil {
		t.Fatal(err)
	}
	if r.count("svc wifi enable") != 1 || r.count("resetprop -n ro.config.low_ram false") != 0 {
		t.Fatalf("peer lease released: %v", r.calls)
	}
	if !s.Active["charging"] || s.Active["boot"] || e.Snapshot()["low_ram"] != "false" {
		t.Fatalf("peer journal lost: active=%v snapshot=%v", s.Active, e.Snapshot())
	}
	if err := r01cCommand(t, ctl, e, cfg, s, "stop", "charging"); err != nil {
		t.Fatal(err)
	}
	if r.count("resetprop -n ro.config.low_ram false") != 1 || len(e.Snapshot()) != 0 {
		t.Fatalf("restoration incomplete: %v snap=%v", r.calls, e.Snapshot())
	}
}
func TestR01DReverseStopOrderPreservesWiFiLease(t *testing.T) {
	cfg, s, e, r, ctl := r01dEnv(t)
	for _, name := range []string{"boot", "charging"} {
		if err := r01cCommand(t, ctl, e, cfg, s, "start", name); err != nil {
			t.Fatal(err)
		}
	}
	if err := r01cCommand(t, ctl, e, cfg, s, "stop", "charging"); err != nil {
		t.Fatal(err)
	}
	if r.count("svc wifi enable") != 0 || e.Snapshot()["wifi-enabled"] != "true" {
		t.Fatalf("wifi lease released: %v", r.calls)
	}
	if err := r01cCommand(t, ctl, e, cfg, s, "stop", "boot"); err != nil {
		t.Fatal(err)
	}
	if r.count("svc wifi enable") != 1 {
		t.Fatalf("WiFi wasn't restored: %v", r.calls)
	}
}
func TestR01DBulkPauseResumeRestoresAndReappliesBoth(t *testing.T) {
	cfg, s, e, r, ctl := r01dEnv(t)
	for _, name := range []string{"boot", "charging"} {
		if err := r01cCommand(t, ctl, e, cfg, s, "start", name); err != nil {
			t.Fatal(err)
		}
	}
	if err := r01cCommand(t, ctl, e, cfg, s, "pause", ""); err != nil {
		t.Fatal(err)
	}
	if r.count("svc wifi enable") != 1 || r.count("resetprop -n ro.config.low_ram false") != 1 || len(e.Snapshot()) != 0 {
		t.Fatalf("pause incomplete: %v", r.calls)
	}
	if err := r01cCommand(t, ctl, e, cfg, s, "resume", ""); err != nil {
		t.Fatal(err)
	}
	if r.count("svc wifi disable") != 2 || r.count("resetprop -n ro.config.low_ram true") != 2 {
		t.Fatalf("resume incomplete: %v", r.calls)
	}
}
func TestR01DFailedScopedRestoreRetainsOnlyOwnerRecord(t *testing.T) {
	cfg, s, e, r, ctl := r01dEnv(t)
	for _, name := range []string{"boot", "charging"} {
		if err := r01cCommand(t, ctl, e, cfg, s, "start", name); err != nil {
			t.Fatal(err)
		}
	}
	r.failLowRestore = true
	if err := r01cCommand(t, ctl, e, cfg, s, "stop", "charging"); err == nil || s.Mode != "recovery_required" {
		t.Fatalf("incomplete recovery not quarantined: %v mode=%s", err, s.Mode)
	}
	if e.Snapshot()["wifi-enabled"] != "true" || e.Snapshot()["low_ram"] != "false" {
		t.Fatalf("lost owned WAL: %v", e.Snapshot())
	}
	if r.count("svc wifi enable") != 0 {
		t.Fatalf("toggled peer Wi-Fi: %v", r.calls)
	}
}
func TestR01DRecoveryRefusesLeaseDriftBeforeRoot(t *testing.T) {
	cfg, s, e, r, _ := r01dEnv(t)
	s.Active["boot"] = true
	if err := persistRuntimeState(e, cfg, s); err != nil {
		t.Fatal(err)
	}
	cfg["events"].(map[string]any)["boot"].(map[string]any)["kill_wifi"] = false
	reconstructed := tuning.New(r)
	recovered := &state{Mode: "normal", Active: map[string]bool{}, EffectResults: map[string]any{}}
	err := recoverRuntimeState(reconstructed, cfg, recovered, false)
	if err == nil || !strings.Contains(err.Error(), "resource lease drift") {
		t.Fatalf("changed profile accepted: %v", err)
	}
	if len(r.calls) != 0 {
		t.Fatalf("changed root before refusal: %v", r.calls)
	}
	if _, err := os.Stat(runtimeStatePath()); err != nil {
		t.Fatalf("WAL removed during refusal: %v", err)
	}
}
