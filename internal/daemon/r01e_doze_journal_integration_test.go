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

// These tests deliberately exercise the real daemon WAL callback and the
// injected Runner. They never invoke Android or a root command.
func TestR01EJournalCommittedBeforeDozeForce(t *testing.T) {
	root := t.TempDir()
	t.Setenv("XBS_RUNTIME_DIR", filepath.Join(root, "runtime"))
	t.Setenv("XBS_EXTERNAL_ROOT", filepath.Join(root, "external"))
	cfg := config.Default()
	s := &state{Mode: "normal", Active: map[string]bool{}, EffectResults: map[string]any{}}
	r := &recoveryRunner{files: map[string]string{}}
	probed, forced := false, false
	r.observeOnRun = func(name string, args []string) error {
		call := strings.TrimSpace(name + " " + strings.Join(args, " "))
		switch call {
		case "dumpsys deviceidle get force":
			probed = true
		case "dumpsys deviceidle force-idle":
			if !probed {
				return errors.New("Doze mutated before ownership probe")
			}
			b, err := os.ReadFile(runtimeStatePath())
			if err != nil {
				return err
			}
			if !strings.Contains(string(b), `"doze:owned":"unforce"`) {
				return errors.New("missing durable Doze WAL before mutation")
			}
			forced = true
		}
		return nil
	}
	e := tuning.New(r)
	bindEngineJournal(e, cfg, s)
	ev := tuning.Event{Name: "probe", HandleCores: "false", DisableCores: "false", HandleApps: "false", HandleGMS: "false", Doze: "deep"}
	result, err := e.Apply(ev, false)
	if err != nil || !result.Applied || !forced {
		t.Fatalf("apply=%+v err=%v probed=%v forced=%v", result, err, probed, forced)
	}
	if _, err = e.Restore(ev); err != nil {
		t.Fatal(err)
	}
	if len(e.Snapshot()) != 0 {
		t.Fatalf("unreleased WAL: %v", e.Snapshot())
	}
}

func TestR01EExternallyForcedDozeNeverClaimed(t *testing.T) {
	root := t.TempDir()
	t.Setenv("XBS_RUNTIME_DIR", filepath.Join(root, "runtime"))
	t.Setenv("XBS_EXTERNAL_ROOT", filepath.Join(root, "external"))
	cfg := config.Default()
	s := &state{Mode: "normal", Active: map[string]bool{}, EffectResults: map[string]any{}}
	r := &recoveryRunner{files: map[string]string{}, preForcedDoze: true}
	sawMutation := false
	r.observeOnRun = func(name string, args []string) error {
		if strings.TrimSpace(name+" "+strings.Join(args, " ")) == "dumpsys deviceidle force-idle" {
			sawMutation = true
		}
		return nil
	}
	e := tuning.New(r)
	bindEngineJournal(e, cfg, s)
	ev := tuning.Event{Name: "external", HandleCores: "false", DisableCores: "false", HandleApps: "false", HandleGMS: "false", Doze: "light"}
	result, err := e.Apply(ev, false)
	if err != nil || !result.Applied {
		t.Fatalf("apply=%+v err=%v", result, err)
	}
	if sawMutation || len(e.Snapshot()) != 0 {
		t.Fatalf("externally forced Doze incorrectly claimed: mutated=%v WAL=%v", sawMutation, e.Snapshot())
	}
	if _, err = e.Restore(ev); err != nil {
		t.Fatal(err)
	}
}

func TestR01EFailedDozeProbeWritesNoJournal(t *testing.T) {
	root := t.TempDir()
	t.Setenv("XBS_RUNTIME_DIR", filepath.Join(root, "runtime"))
	t.Setenv("XBS_EXTERNAL_ROOT", filepath.Join(root, "external"))
	cfg := config.Default()
	s := &state{Mode: "normal", Active: map[string]bool{}, EffectResults: map[string]any{}}
	r := &recoveryRunner{files: map[string]string{}}
	sawMutation := false
	r.observeOnRun = func(name string, args []string) error {
		call := strings.TrimSpace(name + " " + strings.Join(args, " "))
		if call == "dumpsys deviceidle get force" {
			return errors.New("probe unavailable")
		}
		if call == "dumpsys deviceidle force-idle" {
			sawMutation = true
		}
		return nil
	}
	e := tuning.New(r)
	bindEngineJournal(e, cfg, s)
	ev := tuning.Event{Name: "failed-probe", HandleCores: "false", DisableCores: "false", HandleApps: "false", HandleGMS: "false", Doze: "deep"}
	result, err := e.Apply(ev, false)
	if err == nil || result.Applied {
		t.Fatalf("probe failure accepted: %+v %v", result, err)
	}
	if sawMutation || len(e.Snapshot()) != 0 {
		t.Fatalf("mutated or journaled without probe: %v %v", sawMutation, e.Snapshot())
	}
	if _, err := os.Stat(runtimeStatePath()); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("unexpected WAL after refused probe: %v", err)
	}
}
