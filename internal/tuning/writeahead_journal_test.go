package tuning

import (
	"errors"
	"strings"
	"testing"
)

func TestWriteAheadBeforeCPUAndGatedOnJournalFailure(t *testing.T) {
	const governor = "/sys/devices/system/cpu/cpu1/cpufreq/scaling_governor"
	for _, refuse := range []bool{false, true} {
		r := &fakeRunner{files: map[string]string{governor: "schedutil"}}
		engine := New(r)
		calls := 0
		engine.SetJournal(func(next map[string]string) error {
			calls++
			if r.files[governor] != "schedutil" {
				t.Fatal("root write happened before journal")
			}
			if next["file:"+governor] != "schedutil" {
				t.Fatalf("missing preimage: %v", next)
			}
			if refuse {
				return errors.New("disk sync failure")
			}
			return nil
		})
		ev := safeEvent()
		ev.HandleCores = "cpu1"
		result, err := engine.Apply(ev, false)
		if refuse {
			if err == nil || result.Applied {
				t.Fatal("journal failure did not stop mutation")
			}
			if r.files[governor] != "schedutil" || len(engine.Snapshot()) != 0 {
				t.Fatal("mutated despite refused WAL")
			}
		} else {
			if err != nil || !result.Applied {
				t.Fatalf("apply %v %v", result, err)
			}
			if r.files[governor] != "powersave" {
				t.Fatal("CPU write missing")
			}
		}
		if calls != 1 {
			t.Fatalf("journal call count=%d", calls)
		}
	}
}

func TestSnapshotReleaseFailureKeepsRecoveryEvidence(t *testing.T) {
	const governor = "/sys/devices/system/cpu/cpu1/cpufreq/scaling_governor"
	r := &fakeRunner{files: map[string]string{governor: "schedutil"}}
	e := New(r)
	failRelease := true
	e.SetJournal(func(next map[string]string) error {
		if len(next) == 0 && failRelease {
			return errors.New("disk unavailable")
		}
		return nil
	})
	ev := safeEvent()
	ev.HandleCores = "cpu1"
	if _, err := e.Apply(ev, false); err != nil {
		t.Fatal(err)
	}
	if _, err := e.Restore(ev); err == nil {
		t.Fatal("release failure concealed")
	}
	if e.Snapshot()["file:"+governor] != "schedutil" {
		t.Fatal("snapshot disappeared before persistence")
	}
	failRelease = false
	if _, err := e.Restore(ev); err != nil {
		t.Fatalf("retry: %v", err)
	}
	if len(e.Snapshot()) != 0 {
		t.Fatalf("record not released %v", e.Snapshot())
	}
}

func TestDozeOwnedTransitionHasRecoveryJournal(t *testing.T) {
	r := &fakeRunner{files: map[string]string{}}
	e := New(r)
	var snapshots []map[string]string
	e.SetJournal(func(next map[string]string) error { snapshots = append(snapshots, clone(next)); return nil })
	ev := safeEvent()
	ev.Doze = "deep"
	result, err := e.Apply(ev, false)
	if err != nil || !result.Applied {
		t.Fatalf("apply=%+v err=%v", result, err)
	}
	if len(snapshots) != 1 || snapshots[0]["doze:owned"] != "unforce" {
		t.Fatalf("doze not journaled: %v", snapshots)
	}
	_, err = e.Restore(ev)
	if err != nil {
		t.Fatal(err)
	}
	calls := strings.Join(r.calls, "\n")
	if !strings.Contains(calls, "dumpsys deviceidle unforce") {
		t.Fatalf("no unforce: %s", calls)
	}
	if len(e.Snapshot()) != 0 {
		t.Fatalf("owned Doze not released: %v", e.Snapshot())
	}
}

func TestDozeForceNotAttemptedWhenJournalFails(t *testing.T) {
	r := &fakeRunner{files: map[string]string{}}
	e := New(r)
	e.SetJournal(func(map[string]string) error { return errors.New("readonly storage") })
	ev := safeEvent()
	ev.Doze = "deep"
	if _, err := e.Apply(ev, false); err == nil {
		t.Fatal("expected storage error")
	}
	for _, call := range r.calls {
		if strings.HasPrefix(call, "dumpsys deviceidle force-idle") {
			t.Fatalf("uncommitted effect: %v", r.calls)
		}
	}
}
