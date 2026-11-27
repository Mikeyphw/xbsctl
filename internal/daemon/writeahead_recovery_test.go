package daemon

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Mikeyphw/xbsctl/internal/config"
	"github.com/Mikeyphw/xbsctl/internal/tuning"
)

type recoveryRunner struct {
	files         map[string]string
	fail          bool
	observeOnRun  func(string, []string) error
	preForcedDoze bool
}

func (r *recoveryRunner) Run(name string, args ...string) (string, error) {
	if r.observeOnRun != nil {
		if err := r.observeOnRun(name, args); err != nil {
			return "", err
		}
	}
	// R01E probes existing forced-idle ownership before journaling. This
	// simulated Android runner must return a definite observed state, not "".
	if name == "dumpsys" && strings.Join(args, " ") == "deviceidle get force" {
		if r.preForcedDoze {
			return "true", nil
		}
		return "false", nil
	}
	return "", nil
}
func (r *recoveryRunner) Read(p string) (string, error) {
	v, ok := r.files[p]
	if !ok {
		return "", os.ErrNotExist
	}
	return v, nil
}
func (r *recoveryRunner) Write(p, v string) error {
	if r.fail {
		return errors.New("injected restore refusal")
	}
	r.files[p] = v
	return nil
}
func (r *recoveryRunner) Glob(string) ([]string, error) { return nil, nil }

func TestRestartRestoresWALBeforeReapplyingProfiles(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("XBS_RUNTIME_DIR", filepath.Join(dir, "runtime"))
	t.Setenv("XBS_EXTERNAL_ROOT", filepath.Join(dir, "external"))
	const path = "/sandbox/fake/sysfs-governor"
	cfg := config.Default()
	previous := &state{Mode: "normal", Active: map[string]bool{}, EffectResults: map[string]any{}}
	if err := persistRuntimeSnapshot(cfg, previous, map[string]string{"file:" + path: "schedutil"}); err != nil {
		t.Fatal(err)
	}
	originalData, err := os.ReadFile(runtimeStatePath())
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(originalData), "schedutil") {
		t.Fatal("missing durable original state")
	}
	runner := &recoveryRunner{files: map[string]string{path: "powersave"}}
	engine := tuning.New(runner)
	next := &state{Mode: "normal", Active: map[string]bool{}, EffectResults: map[string]any{}}
	bindEngineJournal(engine, cfg, next)
	if err := recoverRuntimeState(engine, cfg, next, false); err != nil {
		t.Fatal(err)
	}
	if runner.files[path] != "schedutil" {
		t.Fatalf("snapshot not restored: %v", runner.files)
	}
	if len(engine.Snapshot()) != 0 {
		t.Fatal("snapshot not released")
	}
	if _, err := os.Stat(runtimeStatePath()); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("WAL not cleared: %v", err)
	}
}

func TestFailedRestartRestorationRetainsJournal(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("XBS_RUNTIME_DIR", filepath.Join(dir, "runtime"))
	t.Setenv("XBS_EXTERNAL_ROOT", filepath.Join(dir, "external"))
	cfg := config.Default()
	const path = "/sandbox/fake/cpu"
	state1 := &state{Mode: "normal", Active: map[string]bool{}, EffectResults: map[string]any{}}
	if err := persistRuntimeSnapshot(cfg, state1, map[string]string{"file:" + path: "before"}); err != nil {
		t.Fatal(err)
	}
	engine := tuning.New(&recoveryRunner{files: map[string]string{path: "after"}, fail: true})
	state2 := &state{Mode: "normal", Active: map[string]bool{}, EffectResults: map[string]any{}}
	bindEngineJournal(engine, cfg, state2)
	if err := recoverRuntimeState(engine, cfg, state2, false); err == nil {
		t.Fatal("incomplete restore accepted")
	}
	if engine.Snapshot()["file:"+path] != "before" {
		t.Fatal("lost recovery record")
	}
	if _, err := os.Stat(runtimeStatePath()); err != nil {
		t.Fatalf("lost WAL: %v", err)
	}
}

func TestDaemonJournalBarrierBeforeMutation(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("XBS_RUNTIME_DIR", filepath.Join(dir, "runtime"))
	t.Setenv("XBS_EXTERNAL_ROOT", filepath.Join(dir, "external"))
	cfg := config.Default()
	state1 := &state{Mode: "normal", Active: map[string]bool{}, EffectResults: map[string]any{}}
	engine := tuning.New(&recoveryRunner{files: map[string]string{}})
	bindEngineJournal(engine, cfg, state1)
	// This test probes the same durable callback attached to the runtime.
	if err := engineJournalRecord(engine); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(runtimeStatePath())
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(data), "doze:owned") {
		t.Fatal("journal callback did not durably persist")
	}
}

// Use the engine's exposed Apply pathway, never a privileged device command.
func engineJournalRecord(engine *tuning.Engine) error {
	ev := tuning.Event{Name: "unit", HandleCores: "false", DisableCores: "false", HandleApps: "false", HandleGMS: "false", Doze: "light"}
	result, err := engine.Apply(ev, false)
	if err != nil {
		return err
	}
	if !result.Applied {
		return errors.New("synthetic Doze not committed")
	}
	if engine.Snapshot()["doze:owned"] != "unforce" {
		return errors.New("snapshot absent")
	}
	return nil
}
