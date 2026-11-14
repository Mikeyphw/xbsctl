package tuning

import (
	"errors"
	"fmt"
	"strings"
	"testing"
)

// r09dTripRunner simulates an abrupt process death *after* an in-memory
// fake-Android write. A panic deliberately bypasses Engine.Apply's normal
// error rollback, exercising durable snapshot recovery instead.
type r09dTripRunner struct {
	*r09cAndroid
	trip  string
	fired bool
}

func (r *r09dTripRunner) Run(name string, args ...string) (string, error) {
	cmd := strings.TrimSpace(name + " " + strings.Join(args, " "))
	out, err := r.r09cAndroid.Run(name, args...)
	if err == nil && !r.fired && r.trip == "run:"+cmd {
		r.fired = true
		panic("r09d-simulated-post-effect-process-death")
	}
	return out, err
}
func (r *r09dTripRunner) Write(path, value string) error {
	err := r.r09cAndroid.Write(path, value)
	if err == nil && !r.fired && r.trip == "write:"+path+"="+value {
		r.fired = true
		panic("r09d-simulated-post-effect-process-death")
	}
	return err
}
func r09dAllEffects() Event {
	e := r09cEvent()
	e.HandleCores = "cpu1"
	e.DisableCores = "cpu1"
	e.HandleGMS = "kill"
	e.LowRAM = true
	e.Doze = "deep"
	e.KillWiFi = true
	return e
}

// Test each of the six distinct effect domains at the dangerous boundary:
// the fake effect happened, but process death prevented a normal return.
// A new Engine must restore the exact original state from the existing WAL.
func TestR09DPostEffectCrashRecoveryMatrix(t *testing.T) {
	governor := "/sys/devices/system/cpu/cpu1/cpufreq/scaling_governor"
	online := "/sys/devices/system/cpu/cpu1/online"
	cases := []struct{ name, trip string }{
		{"governor", "write:" + governor + "=powersave"},
		{"cpu-online", "write:" + online + "=0"},
		{"gms", "run:pm disable com.google.android.gms"},
		{"low-ram", "run:resetprop -n ro.config.low_ram true"},
		{"doze", "run:dumpsys deviceidle force-idle"},
		{"wifi", "run:svc wifi disable"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			android := r09cNewAndroid()
			before := r09cSnapshot(android)
			trip := &r09dTripRunner{r09cAndroid: android, trip: tc.trip}
			engine := New(trip)
			panicked := false
			func() {
				defer func() {
					if recovered := recover(); recovered != nil {
						if recovered != "r09d-simulated-post-effect-process-death" {
							t.Fatalf("unexpected panic: %v", recovered)
						}
						panicked = true
					}
				}()
				if result, err := engine.Apply(r09dAllEffects(), false); err != nil {
					t.Fatalf("unrelated apply failure before crash: result=%+v err=%v", result, err)
				}
			}()
			if !panicked || !trip.fired {
				t.Fatal("crash point not exercised")
			}
			snapshot := engine.Snapshot()
			if len(snapshot) == 0 {
				t.Fatal("process died without persisted recovery snapshot")
			}
			if r09cSnapshot(android) == before {
				t.Fatal("crash was not after a real simulated effect")
			}
			recovered := New(android)
			recovered.LoadSnapshot(snapshot)
			res, err := recovered.Restore(r09dAllEffects())
			if err != nil || !res.Applied {
				t.Fatalf("recovery failed: %+v %v", res, err)
			}
			if got := r09cSnapshot(android); got != before {
				t.Fatalf("unrestored crash %s: got=%s want=%s", tc.name, got, before)
			}
			if len(recovered.Snapshot()) != 0 {
				t.Fatalf("recovery record still held: %v", recovered.Snapshot())
			}
		})
	}
}

// A failed restore is NOT a successful transaction; its unresolved preimage
// must survive in the snapshot until a later recovery can be verified.
func TestR09DRestoreFailureRetainsJournal(t *testing.T) {
	for _, tc := range []struct{ name, failedCommand, failedWrite, key string }{
		{"cpu-governor", "", "=schedutil", "file:/sys/devices/system/cpu/cpu1/cpufreq/scaling_governor"},
		{"gms", "pm enable com.google.android.gms", "", "gms-enabled"},
		{"wifi", "svc wifi enable", "", "wifi-enabled"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			android := r09cNewAndroid()
			before := r09cSnapshot(android)
			e := r09dAllEffects()
			engine := New(android)
			if result, err := engine.Apply(e, false); err != nil || !result.Applied {
				t.Fatalf("apply: %+v %v", result, err)
			}
			android.failCommand = tc.failedCommand
			android.failWrite = tc.failedWrite
			result, err := engine.Restore(e)
			if err == nil || result.Applied {
				t.Fatalf("unverified restore reported success: %+v %v", result, err)
			}
			snapshot := engine.Snapshot()
			if _, ok := snapshot[tc.key]; !ok {
				t.Fatalf("failed effect lost WAL %q: %v", tc.key, snapshot)
			}
			android.failCommand = ""
			android.failWrite = ""
			rebooted := New(android)
			rebooted.LoadSnapshot(snapshot)
			result, err = rebooted.Restore(e)
			if err != nil || !result.Applied {
				t.Fatalf("second restore failed: %+v %v", result, err)
			}
			if got := r09cSnapshot(android); got != before {
				t.Fatalf("state drift: %s -> %s", before, got)
			}
			if len(rebooted.Snapshot()) != 0 {
				t.Fatalf("WAL not released after verified retry: %v", rebooted.Snapshot())
			}
		})
	}
}

// An immutable caller-owned copy is essential: editing a returned snapshot
// cannot erase the engine's recovery evidence or smuggle fake original values.
func TestR09DSnapshotCopyIsolation(t *testing.T) {
	android := r09cNewAndroid()
	before := r09cSnapshot(android)
	e := r09cEvent()
	e.KillWiFi = true
	engine := New(android)
	if res, err := engine.Apply(e, false); err != nil || !res.Applied {
		t.Fatalf("apply: %+v %v", res, err)
	}
	copy1 := engine.Snapshot()
	copy1["wifi-enabled"] = "false"
	delete(copy1, "wifi-enabled")
	if engine.Snapshot()["wifi-enabled"] != "true" {
		t.Fatalf("caller mutated WAL: %v", engine.Snapshot())
	}
	rebooted := New(android)
	rebooted.LoadSnapshot(engine.Snapshot())
	externallyReturned := rebooted.Snapshot()
	externallyReturned["wifi-enabled"] = "false"
	if res, err := rebooted.Restore(e); err != nil || !res.Applied {
		t.Fatalf("recovery: %+v %v", res, err)
	}
	if r09cSnapshot(android) != before {
		t.Fatalf("snapshot isolation broke recovery: %s", r09cSnapshot(android))
	}
}

// A failing journal release cannot silently erase an already-applied effect;
// only explicitly successful durable release permits dropping that record.
func TestR09DJournalReleaseAfterRestoreFailure(t *testing.T) {
	android := r09cNewAndroid()
	e := r09cEvent()
	e.KillWiFi = true
	engine := New(android)
	releaseFails := false
	engine.SetJournal(func(m map[string]string) error {
		if releaseFails && len(m) == 0 {
			return errors.New("simulated fsync failure during journal release")
		}
		return nil
	})
	if _, err := engine.Apply(e, false); err != nil {
		t.Fatal(err)
	}
	releaseFails = true
	result, err := engine.Restore(e)
	if err == nil || result.Applied {
		t.Fatalf("false success after release failure: %+v %v", result, err)
	}
	if len(engine.Snapshot()) != 1 {
		t.Fatalf("lost WAL after fsync error: %v", engine.Snapshot())
	}
	releaseFails = false
	result, err = engine.Restore(e)
	if err != nil || !result.Applied || len(engine.Snapshot()) != 0 {
		t.Fatalf("recovery retry: %+v %v %v", result, err, engine.Snapshot())
	}
	if !android.wifi {
		t.Fatal(fmt.Sprint("wifi not restored after retry"))
	}
}

var _ Runner = (*r09dTripRunner)(nil)
