package tuning

import (
	"errors"
	"strings"
	"testing"
)

// All effects are routed to the existing in-memory fakeRunner; never SystemRunner.
type r09bFaultRunner struct {
	*fakeRunner
	rejectNextPowerSave bool
	rejectNextRestore   bool
}

func (f *r09bFaultRunner) Write(path, value string) error {
	if f.rejectNextPowerSave && value == "powersave" {
		f.rejectNextPowerSave = false
		return errors.New("r09b injected governor apply fault")
	}
	if f.rejectNextRestore && value == "schedutil" {
		f.rejectNextRestore = false
		return errors.New("r09b injected governor rollback fault")
	}
	return f.fakeRunner.Write(path, value)
}
func r09bCPUFixture() (*r09bFaultRunner, Event, string) {
	p := "/sys/devices/system/cpu/cpu1/cpufreq/scaling_governor"
	f := &r09bFaultRunner{fakeRunner: &fakeRunner{files: map[string]string{p: "schedutil"}}}
	ev := safeEvent()
	ev.HandleCores = "cpu1"
	return f, ev, p
}
func TestR09BWALFailureMatrix(t *testing.T) {
	t.Run("journal_failure_before_mutation", func(t *testing.T) {
		f, ev, p := r09bCPUFixture()
		e := New(f)
		writes := 0
		e.SetJournal(func(next map[string]string) error {
			writes++
			if next["file:"+p] != "schedutil" {
				t.Fatalf("preimage absent: %v", next)
			}
			return errors.New("r09b injected fsync failure")
		})
		res, err := e.Apply(ev, false)
		if err == nil || res.Applied || f.files[p] != "schedutil" || len(e.Snapshot()) != 0 || writes != 1 {
			t.Fatalf("WAL fault not fail closed: %+v err=%v snapshot=%v", res, err, e.Snapshot())
		}
	})
	t.Run("governor_apply_failure_rolls_back", func(t *testing.T) {
		f, ev, p := r09bCPUFixture()
		f.rejectNextPowerSave = true
		e := New(f)
		res, err := e.Apply(ev, false)
		if err == nil || res.Applied || f.files[p] != "schedutil" || len(e.Snapshot()) != 0 {
			t.Fatalf("write fault not recovered: %+v err=%v snapshot=%v", res, err, e.Snapshot())
		}
	})
}
func TestR09BRollbackFailureRecovery(t *testing.T) {
	f, ev, p := r09bCPUFixture()
	f.fail = "svc wifi disable"
	f.rejectNextRestore = true
	ev.KillWiFi = true
	e := New(f)
	res, err := e.Apply(ev, false)
	if err == nil || res.Applied || !strings.Contains(err.Error(), "rollback incomplete") {
		t.Fatalf("rollback fault hidden: %+v err=%v", res, err)
	}
	if e.Snapshot()["file:"+p] != "schedutil" || f.files[p] != "powersave" {
		t.Fatalf("recovery preimage lost: %v files=%v", e.Snapshot(), f.files)
	}
	f.fail = ""
	if _, err := e.Restore(ev); err != nil {
		t.Fatalf("explicit recovery failed: %v", err)
	}
	if f.files[p] != "schedutil" || len(e.Snapshot()) != 0 {
		t.Fatalf("original state not restored: %v %v", f.files, e.Snapshot())
	}
}
func TestR09BRestartSnapshotRestore(t *testing.T) {
	f, ev, p := r09bCPUFixture()
	e := New(f)
	if res, err := e.Apply(ev, false); err != nil || !res.Applied {
		t.Fatalf("apply: %+v %v", res, err)
	}
	evidence := e.Snapshot()
	if evidence["file:"+p] != "schedutil" || f.files[p] != "powersave" {
		t.Fatalf("wrong durable preimage %v", evidence)
	}
	restarted := New(f)
	restarted.LoadSnapshot(evidence)
	if _, err := restarted.Restore(ev); err != nil {
		t.Fatalf("restore after restart: %v", err)
	}
	if f.files[p] != "schedutil" || len(restarted.Snapshot()) != 0 {
		t.Fatalf("restart restore mismatch files=%v snapshot=%v", f.files, restarted.Snapshot())
	}
	if e.Snapshot()["file:"+p] != "schedutil" {
		t.Fatal("snapshot ownership aliasing across engines")
	}
}
func TestR09BDryRunNoMutation(t *testing.T) {
	f, ev, p := r09bCPUFixture()
	ev.KillWiFi = true
	ev.Doze = "deep"
	e := New(f)
	res, err := e.Apply(ev, true)
	if err != nil || !res.Applied {
		t.Fatalf("dry simulation rejected: %+v %v", res, err)
	}
	if f.files[p] != "schedutil" || len(f.calls) != 0 || len(e.Snapshot()) != 0 {
		t.Fatalf("dry run executed effects: calls=%v state=%v", f.calls, f.files)
	}
	for _, out := range res.Outcomes {
		if out.Field == "handle_cores" && out.State != "dry_run" {
			t.Fatalf("dry-run mislabeled %+v", out)
		}
	}
}
