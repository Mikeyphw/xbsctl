package tuning

import (
	"errors"
	"fmt"
	"math/rand"
	"strings"
	"testing"
)

// These deterministic schedules use the R09C fake Android and R09D post-write
// crash wrapper. They neither spawn root commands nor touch real Android state.
func TestR09FAdversarialCrashSchedule(t *testing.T) {
	governor := "/sys/devices/system/cpu/cpu1/cpufreq/scaling_governor"
	online := "/sys/devices/system/cpu/cpu1/online"
	points := []struct{ name, trip string }{
		{"governor", "write:" + governor + "=powersave"},
		{"online", "write:" + online + "=0"},
		{"gms", "run:pm disable com.google.android.gms"},
		{"lowram", "run:resetprop -n ro.config.low_ram true"},
		{"doze", "run:dumpsys deviceidle force-idle"},
		{"wifi", "run:svc wifi disable"},
	}
	random := rand.New(rand.NewSource(0x09f2026))
	for round := 0; round < 4; round++ {
		for _, position := range random.Perm(len(points)) {
			point := points[position]
			t.Run(fmt.Sprintf("round-%d-%s", round, point.name), func(t *testing.T) {
				android := r09cNewAndroid()
				before := r09cSnapshot(android)
				crash := &r09dTripRunner{r09cAndroid: android, trip: point.trip}
				engine := New(crash)
				caught := false
				func() {
					defer func() {
						if value := recover(); value != nil {
							if value != "r09d-simulated-post-effect-process-death" {
								t.Fatalf("unexpected panic %v", value)
							}
							caught = true
						}
					}()
					if result, err := engine.Apply(r09dAllEffects(), false); err != nil {
						t.Fatalf("pre-crash apply %+v %v", result, err)
					}
				}()
				if !caught || !crash.fired || r09cSnapshot(android) == before {
					t.Fatal("scheduled post-effect crash not observed")
				}
				durable := engine.Snapshot()
				if len(durable) == 0 {
					t.Fatal("lost crash recovery preimage")
				}
				restarted := New(android)
				restarted.LoadSnapshot(durable)
				result, err := restarted.Restore(r09dAllEffects())
				if err != nil || !result.Applied {
					t.Fatalf("recovery %+v %v", result, err)
				}
				if got := r09cSnapshot(android); got != before {
					t.Fatalf("recovery drift got=%s before=%s", got, before)
				}
				if len(restarted.Snapshot()) != 0 {
					t.Fatalf("stale WAL %+v", restarted.Snapshot())
				}
			})
		}
	}
}

func TestR09FJournalReleasePermutation(t *testing.T) {
	effects := []struct {
		name      string
		configure func(*Event)
	}{
		{"wifi", func(e *Event) { e.KillWiFi = true }},
		{"gms", func(e *Event) { e.HandleGMS = "kill" }},
		{"governor", func(e *Event) { e.HandleCores = "cpu1" }},
	}
	for round := 0; round < 4; round++ {
		for _, effect := range effects {
			t.Run(fmt.Sprintf("round-%d-%s", round, effect.name), func(t *testing.T) {
				android := r09cNewAndroid()
				before := r09cSnapshot(android)
				event := r09cEvent()
				effect.configure(&event)
				engine := New(android)
				failRelease := false
				engine.SetJournal(func(snapshot map[string]string) error {
					if failRelease && len(snapshot) == 0 {
						return errors.New("simulated durable release failure")
					}
					return nil
				})
				applied, err := engine.Apply(event, false)
				if err != nil || !applied.Applied {
					t.Fatalf("apply %+v %v", applied, err)
				}
				failRelease = true
				result, err := engine.Restore(event)
				if err == nil || result.Applied {
					t.Fatalf("undurable release marked applied %+v %v", result, err)
				}
				if len(engine.Snapshot()) == 0 {
					t.Fatal("lost evidence after release fsync failure")
				}
				failRelease = false
				restored, err := engine.Restore(event)
				if err != nil || !restored.Applied {
					t.Fatalf("retry %+v %v", restored, err)
				}
				if got := r09cSnapshot(android); got != before || len(engine.Snapshot()) != 0 {
					t.Fatalf("retry state drift %s vs %s WAL=%v", got, before, engine.Snapshot())
				}
			})
		}
	}
}

func TestR09FExternalStateOwnership(t *testing.T) {
	states := []struct {
		name      string
		prepare   func(*r09cAndroid)
		configure func(*Event)
	}{
		{"already-offline-wifi", func(a *r09cAndroid) { a.wifi = false }, func(e *Event) { e.KillWiFi = true }},
		{"gms-disabled-externally", func(a *r09cAndroid) { a.gms = false }, func(e *Event) { e.HandleGMS = "kill" }},
		{"preforced-doze", func(a *r09cAndroid) { a.forcedDoze = true }, func(e *Event) { e.Doze = "deep" }},
	}
	for round := 0; round < 4; round++ {
		for _, item := range states {
			t.Run(fmt.Sprintf("round-%d-%s", round, item.name), func(t *testing.T) {
				android := r09cNewAndroid()
				item.prepare(android)
				before := r09cSnapshot(android)
				event := r09cEvent()
				item.configure(&event)
				engine := New(android)
				result, err := engine.Apply(event, false)
				if err != nil || !result.Applied {
					t.Fatalf("apply %+v %v", result, err)
				}
				if len(engine.Snapshot()) != 0 {
					t.Fatalf("claimed unowned state %+v", engine.Snapshot())
				}
				restored, err := engine.Restore(event)
				if err != nil || !restored.Applied {
					t.Fatalf("restore %+v %v", restored, err)
				}
				if got := r09cSnapshot(android); got != before {
					t.Fatalf("external state changed %s to %s", before, got)
				}
				for _, cmd := range android.commands {
					if strings.Contains(cmd, "svc wifi enable") || strings.Contains(cmd, "pm enable com.google.android.gms") || strings.Contains(cmd, "dumpsys deviceidle unforce") {
						t.Fatalf("unowned state force-restored %s", cmd)
					}
				}
			})
		}
	}
}
