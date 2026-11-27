package daemon

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Mikeyphw/xbsctl/internal/config"
	"github.com/Mikeyphw/xbsctl/internal/control"
	"github.com/Mikeyphw/xbsctl/internal/tuning"
)

type r01cRootRecorder struct {
	calls       []string
	failRestore bool
}

func (r *r01cRootRecorder) Run(n string, args ...string) (string, error) {
	cmd := strings.TrimSpace(n + " " + strings.Join(args, " "))
	r.calls = append(r.calls, cmd)
	if cmd == "cmd wifi status" {
		return "Wi-Fi is enabled", nil
	}
	if cmd == "svc wifi enable" && r.failRestore {
		return "", errors.New("injected restore refusal")
	}
	return "", nil
}
func (*r01cRootRecorder) Read(string) (string, error)   { return "", errors.New("not needed") }
func (*r01cRootRecorder) Write(string, string) error    { return errors.New("not needed") }
func (*r01cRootRecorder) Glob(string) ([]string, error) { return nil, errors.New("not needed") }
func (r *r01cRootRecorder) count(cmd string) int {
	n := 0
	for _, x := range r.calls {
		if x == cmd {
			n++
		}
	}
	return n
}

func r01cEnvironment(t *testing.T) (map[string]any, *state, *tuning.Engine, *r01cRootRecorder, string) {
	t.Helper()
	root := t.TempDir()
	t.Setenv("XBS_EXTERNAL_ROOT", filepath.Join(root, "external"))
	t.Setenv("XBS_RUNTIME_DIR", filepath.Join(root, "runtime"))
	cfg := config.Default()
	events := cfg["events"].(map[string]any)
	events["boot"].(map[string]any)["kill_wifi"] = true
	events["charging"].(map[string]any)["kill_wifi"] = true
	s := &state{Mode: "normal", Active: map[string]bool{}, EffectResults: map[string]any{}}
	rec := &r01cRootRecorder{}
	engine := tuning.New(rec)
	bindEngineJournal(engine, cfg, s)
	return cfg, s, engine, rec, ctlPath(cfg)
}
func r01cCommand(t *testing.T, ctl string, engine *tuning.Engine, cfg map[string]any, s *state, command, event string) error {
	t.Helper()
	req, err := control.QueueRequest(ctl, command, event)
	if err != nil {
		t.Fatal(err)
	}
	_, err = processCtl(engine, cfg, s, false)
	ack, found := control.ReadAck(ctl, req.ID)
	if !found {
		t.Fatalf("command %s %s missing durable ack", command, event)
	}
	if (err != nil && ack.State != "failed") || (err == nil && ack.State != "applied") {
		t.Fatalf("invalid ack %+v for err=%v", ack, err)
	}
	return err
}

func TestR01CStoppingPassiveProfileKeepsMutatingOwner(t *testing.T) {
	cfg, s, engine, r, ctl := r01cEnvironment(t)
	if err := r01cCommand(t, ctl, engine, cfg, s, "start", "boot"); err != nil {
		t.Fatal(err)
	}
	if r.count("svc wifi disable") != 1 {
		t.Fatalf("boot not applied once: %v", r.calls)
	}
	if err := r01cCommand(t, ctl, engine, cfg, s, "start", "manual"); err != nil {
		t.Fatal(err)
	}
	if err := r01cCommand(t, ctl, engine, cfg, s, "stop", "manual"); err != nil {
		t.Fatal(err)
	}
	if r.count("svc wifi enable") != 0 || !s.Active["boot"] || engine.Snapshot()["wifi-enabled"] != "true" {
		t.Fatalf("passive stop destroyed active owner: active=%v calls=%v snapshot=%v", s.Active, r.calls, engine.Snapshot())
	}
	if err := r01cCommand(t, ctl, engine, cfg, s, "stop", "charging"); err != nil {
		t.Fatal(err)
	}
	if r.count("svc wifi enable") != 0 {
		t.Fatal("stopping inactive mutator restored another's Wi-Fi")
	}
	if err := r01cCommand(t, ctl, engine, cfg, s, "stop", "boot"); err != nil {
		t.Fatal(err)
	}
	if r.count("svc wifi enable") != 1 || len(engine.Snapshot()) != 0 {
		t.Fatalf("actual owner failed to restore: calls=%v journal=%v", r.calls, engine.Snapshot())
	}
}

func TestR01CBulkRestorationDoesNotUndoPassiveProfile(t *testing.T) {
	cfg, s, engine, r, ctl := r01cEnvironment(t)
	if err := r01cCommand(t, ctl, engine, cfg, s, "start", "boot"); err != nil {
		t.Fatal(err)
	}
	if err := r01cCommand(t, ctl, engine, cfg, s, "start", "manual"); err != nil {
		t.Fatal(err)
	}
	if err := r01cCommand(t, ctl, engine, cfg, s, "pause", ""); err != nil {
		t.Fatal(err)
	}
	if r.count("svc wifi enable") != 1 || s.Mode != "paused" || len(engine.Snapshot()) != 0 {
		t.Fatalf("pause must restore owner exactly once: calls=%v mode=%q snapshot=%v", r.calls, s.Mode, engine.Snapshot())
	}
	if err := r01cCommand(t, ctl, engine, cfg, s, "resume", ""); err != nil {
		t.Fatal(err)
	}
	if r.count("svc wifi disable") != 2 || engine.Snapshot()["wifi-enabled"] != "true" {
		t.Fatalf("resume must reapply one mutator: calls=%v snapshot=%v", r.calls, engine.Snapshot())
	}
	if err := r01cCommand(t, ctl, engine, cfg, s, "safe", ""); err != nil {
		t.Fatal(err)
	}
	if r.count("svc wifi enable") != 2 || len(engine.Snapshot()) != 0 || len(s.Active) != 0 {
		t.Fatalf("safe mode lost owner isolation: calls=%v active=%v snapshot=%v", r.calls, s.Active, engine.Snapshot())
	}
}

func TestR01CBulkRestoreFailureEntersRecoveryRequired(t *testing.T) {
	cfg, s, engine, r, ctl := r01cEnvironment(t)
	if err := r01cCommand(t, ctl, engine, cfg, s, "start", "boot"); err != nil {
		t.Fatal(err)
	}
	if err := r01cCommand(t, ctl, engine, cfg, s, "start", "manual"); err != nil {
		t.Fatal(err)
	}
	r.failRestore = true
	err := r01cCommand(t, ctl, engine, cfg, s, "pause", "")
	if err == nil || s.Mode != "recovery_required" || engine.Snapshot()["wifi-enabled"] != "true" {
		t.Fatalf("bulk restoration failure was not quarantined: err=%v mode=%q snap=%v", err, s.Mode, engine.Snapshot())
	}
	if _, statErr := os.Stat(runtimeStatePath()); statErr != nil {
		t.Fatalf("recovery WAL missing: %v", statErr)
	}
	n := len(r.calls)
	_, err = processCtl(engine, cfg, s, false)
	if err == nil || len(r.calls) != n {
		t.Fatalf("recovery mode did not block root: err=%v calls=%v", err, r.calls)
	}
}

func TestR01COrphanedSnapshotCannotBeSilentlyIgnored(t *testing.T) {
	cfg, s, engine, r, _ := r01cEnvironment(t)
	engine.LoadSnapshot(map[string]string{"wifi-enabled": "true"})
	err := restoreActive(engine, cfg, s)
	if err == nil || !strings.Contains(err.Error(), "unowned privileged recovery snapshot") {
		t.Fatalf("lost orphaned snapshot refusal: %v", err)
	}
	if r.count("svc wifi enable") != 0 || engine.Snapshot()["wifi-enabled"] != "true" {
		t.Fatalf("orphan restore performed unsafe root mutations: calls=%v snapshot=%v", r.calls, engine.Snapshot())
	}
}

func TestR01CReloadReappliesOwnerOnceWithPassiveActive(t *testing.T) {
	cfg, s, engine, r, ctl := r01cEnvironment(t)
	if err := r01cCommand(t, ctl, engine, cfg, s, "start", "boot"); err != nil {
		t.Fatal(err)
	}
	if err := r01cCommand(t, ctl, engine, cfg, s, "start", "manual"); err != nil {
		t.Fatal(err)
	}
	if _, err := config.Save(cfg); err != nil {
		t.Fatal(err)
	}
	if err := r01cCommand(t, ctl, engine, cfg, s, "reload", ""); err != nil {
		t.Fatal(err)
	}
	if r.count("svc wifi enable") != 1 || r.count("svc wifi disable") != 2 || engine.Snapshot()["wifi-enabled"] != "true" {
		t.Fatalf("reload did not restore/reapply owner exactly once: calls=%v snapshot=%v", r.calls, engine.Snapshot())
	}
}

func TestR01CConflictingStartHasNoRootSideEffects(t *testing.T) {
	cfg, s, engine, r, ctl := r01cEnvironment(t)
	if err := r01cCommand(t, ctl, engine, cfg, s, "start", "boot"); err != nil {
		t.Fatal(err)
	}
	n := len(r.calls)
	err := r01cCommand(t, ctl, engine, cfg, s, "start", "charging")
	if err == nil || !strings.Contains(err.Error(), "conflicting privileged owners") {
		t.Fatalf("no conflict refusal: %v", err)
	}
	if len(r.calls) != n || s.Active["charging"] || !s.Active["boot"] {
		t.Fatalf("conflicting start changed device: %v", r.calls)
	}
	if err := r01cCommand(t, ctl, engine, cfg, s, "start", "boot"); err != nil {
		t.Fatal(err)
	}
	if len(r.calls) != n {
		t.Fatalf("duplicate start repeated effect: %v", r.calls)
	}
}

func TestR01CReloadConflictRefusesBeforeRestoration(t *testing.T) {
	cfg, s, engine, r, ctl := r01cEnvironment(t)
	if err := r01cCommand(t, ctl, engine, cfg, s, "start", "boot"); err != nil {
		t.Fatal(err)
	}
	if err := r01cCommand(t, ctl, engine, cfg, s, "start", "manual"); err != nil {
		t.Fatal(err)
	}
	fresh := config.Default()
	fresh["events"].(map[string]any)["boot"].(map[string]any)["kill_wifi"] = true
	fresh["events"].(map[string]any)["manual"].(map[string]any)["kill_wifi"] = true
	if _, err := config.Save(fresh); err != nil {
		t.Fatal(err)
	}
	n := len(r.calls)
	err := r01cCommand(t, ctl, engine, cfg, s, "reload", "")
	if err == nil || !strings.Contains(err.Error(), "reload ownership") {
		t.Fatalf("conflicting reload accepted: %v", err)
	}
	if len(r.calls) != n || !s.Active["boot"] || !s.Active["manual"] || engine.Snapshot()["wifi-enabled"] != "true" {
		t.Fatalf("reload changed device before guard: calls=%v active=%v", r.calls, s.Active)
	}
}

func TestR01CRecoveryRefusesPersistedMultipleMutators(t *testing.T) {
	cfg, _, _, rec, _ := r01cEnvironment(t)
	existing := &state{Mode: "normal", Active: map[string]bool{"boot": true, "charging": true}, EffectResults: map[string]any{}}
	if err := persistRuntimeState(tuning.New(rec), cfg, existing); err != nil {
		t.Fatal(err)
	}
	recovered := &state{Mode: "normal", Active: map[string]bool{}, EffectResults: map[string]any{}}
	err := recoverRuntimeState(tuning.New(rec), cfg, recovered, false)
	if err == nil || !strings.Contains(err.Error(), "conflicting privileged owners") {
		t.Fatalf("persisted ownership conflict not detected: %v", err)
	}
	if rec.count("svc wifi disable") != 0 {
		t.Fatalf("unsafe reapplication: %v", rec.calls)
	}
}

func TestR01CFailedOwnerRestorationRequiresRecovery(t *testing.T) {
	cfg, s, engine, r, ctl := r01cEnvironment(t)
	if err := r01cCommand(t, ctl, engine, cfg, s, "start", "boot"); err != nil {
		t.Fatal(err)
	}
	r.failRestore = true
	err := r01cCommand(t, ctl, engine, cfg, s, "stop", "boot")
	if err == nil || s.Mode != "recovery_required" || engine.Snapshot()["wifi-enabled"] != "true" {
		t.Fatalf("restoration failure not fail-closed: err=%v mode=%s original=%v", err, s.Mode, engine.Snapshot())
	}
	req, err := control.QueueRequest(ctl, "start", "manual")
	if err != nil {
		t.Fatal(err)
	}
	n := len(r.calls)
	_, err = processCtl(engine, cfg, s, false)
	if err == nil || !strings.Contains(err.Error(), "unresolved privileged restoration") {
		t.Fatalf("recovery mode accepted commands: %v", err)
	}
	if _, acknowledged := control.ReadAck(ctl, req.ID); acknowledged {
		t.Fatal("refused recovery-mode command unexpectedly acknowledged")
	}
	if len(r.calls) != n {
		t.Fatalf("refused recovery-mode command changed privileged state: %v", r.calls)
	}
	_, err = os.Stat(runtimeStatePath())
	if err != nil {
		t.Fatalf("missing recovery journal: %v", err)
	}
}
