package autoevents

import (
	"errors"
	"strings"
	"testing"
	"time"
)

type sampleReader struct {
	data    map[string]string
	failure string
}

func (s sampleReader) Read(path string) (string, error) {
	if s.failure == path {
		return "", errors.New("sensor refused")
	}
	v, ok := s.data[path]
	if !ok {
		return "", errors.New("missing")
	}
	return v, nil
}
func (s sampleReader) Run(cmd string, args ...string) (string, error) {
	if s.failure == cmd {
		return "", errors.New("dumpsys refused")
	}
	return s.data["power"], nil
}
func obs(charge, off bool, battery int) Observation {
	return Observation{Charging: charge, ScreenOff: off, BatteryPercent: battery, Source: "test"}
}
func pol() Policy {
	return Policy{LowThreshold: 20, ReleaseThreshold: 25, StableSamples: 2, Delay: time.Second}
}
func TestObservationStrictSensors(t *testing.T) {
	for _, tt := range []struct {
		name, status, capacity, power string
		fail                          bool
	}{
		{"charging", "Charging", "80", "mWakefulness=Awake", false},
		{"full", "Full", "100", "mWakefulness=Asleep", false},
		{"discharging", "Discharging", "30", "mWakefulness=Awake", false},
		{"notcharging", "Not charging", "30", "mWakefulness=Asleep", false},
		{"unknown", "Suspended", "30", "mWakefulness=Awake", true},
		{"ambiguous percent", "Charging", "101", "mWakefulness=Awake", true},
		{"invalid percent", "Charging", "abc", "mWakefulness=Awake", true},
		{"missing wakefulness", "Charging", "30", "unknown", true},
		{"duplicate wakefulness", "Charging", "30", "mWakefulness=Awake\nmWakefulness=Asleep", true},
		{"unknown wakefulness", "Charging", "30", "mWakefulness=Dozing", true},
	} {
		t.Run(tt.name, func(t *testing.T) {
			r := sampleReader{data: map[string]string{"/sys/class/power_supply/battery/status": tt.status, "/sys/class/power_supply/battery/capacity": tt.capacity, "power": tt.power}}
			got, err := Observe(r)
			if (err != nil) != tt.fail {
				t.Fatalf("got=%+v err=%v", got, err)
			}
		})
	}
}
func TestFailedSensorNeverGetsSyntheticState(t *testing.T) {
	for _, fail := range []string{"/sys/class/power_supply/battery/status", "/sys/class/power_supply/battery/capacity", "dumpsys"} {
		r := sampleReader{failure: fail, data: map[string]string{"/sys/class/power_supply/battery/status": "Charging", "/sys/class/power_supply/battery/capacity": "50", "power": "mWakefulness=Awake"}}
		_, e := Observe(r)
		if e == nil {
			t.Fatalf("failed sensor %s accepted", fail)
		}
	}
}
func TestPlannerStableDelayAndChargingPriority(t *testing.T) {
	p, _ := New(pol())
	start := time.Unix(12000, 0)
	for i, at := range []time.Time{start, start.Add(500 * time.Millisecond), start.Add(time.Second)} {
		v, e := p.Next(obs(true, true, 18), at)
		if e != nil {
			t.Fatal(e)
		}
		if (i == 2) != (v.Stable && v.Event == "charging") {
			t.Fatalf("i=%d got %+v", i, v)
		}
	}
}
func TestPlannerScreenAndHysteresis(t *testing.T) {
	p, _ := New(pol())
	n := time.Unix(100, 0)
	samples := []struct {
		o      Observation
		want   string
		stable bool
	}{{obs(false, false, 19), "", false}, {obs(false, false, 19), "low_power", true}, {obs(false, false, 23), "", false}, {obs(false, false, 23), "low_power", true}, {obs(false, true, 23), "", false}, {obs(false, true, 23), "screen_off", true}, {obs(false, false, 26), "", false}, {obs(false, false, 26), "", true}}
	for i, x := range samples {
		v, e := p.Next(x.o, n.Add(time.Duration(i)*2*time.Second))
		if e != nil {
			t.Fatal(e)
		}
		if v.Event != x.want || v.Stable != x.stable {
			t.Fatalf("step=%d got=%+v want=%q stable=%v", i, v, x.want, x.stable)
		}
	}
}
func TestPlannerRepeatedSamplesIdempotent(t *testing.T) {
	p, _ := New(pol())
	n := time.Unix(100, 0)
	for i := 0; i < 20; i++ {
		v, e := p.Next(obs(false, true, 60), n.Add(time.Duration(i)*time.Second))
		if e != nil {
			t.Fatal(e)
		}
		if i >= 1 && v.Event != "screen_off" {
			t.Fatalf("repeated stable event vanished at %d", i)
		}
	}
}
func TestPlannerInvalidConfig(t *testing.T) {
	for _, p := range []Policy{{LowThreshold: 20, ReleaseThreshold: 20, StableSamples: 1}, {LowThreshold: 0, ReleaseThreshold: 25, StableSamples: 1}, {LowThreshold: 20, ReleaseThreshold: 25, StableSamples: 0}, {LowThreshold: 20, ReleaseThreshold: 101, StableSamples: 2}, {LowThreshold: 20, ReleaseThreshold: 25, StableSamples: 2, Delay: -time.Second}} {
		if _, err := New(p); err == nil {
			t.Fatalf("accepted %+v", p)
		}
	}
}
func TestNonmonotonicTimeRefused(t *testing.T) {
	p, _ := New(pol())
	n := time.Unix(1000, 0)
	_, _ = p.Next(obs(true, false, 70), n)
	_, err := p.Next(obs(true, false, 70), n.Add(-time.Second))
	if err == nil || !strings.Contains(err.Error(), "nonmonotonic") {
		t.Fatal(err)
	}
}
func TestInvalidObservationRefused(t *testing.T) {
	p, _ := New(pol())
	if _, err := p.Next(Observation{Charging: true, BatteryPercent: -1}, time.Now()); err == nil {
		t.Fatal("invalid observation accepted")
	}
}
func TestDebounceOfScreenFlapping(t *testing.T) {
	p, _ := New(pol())
	n := time.Unix(600, 0)
	for i, v := range []bool{true, false, true, false, true} {
		got, err := p.Next(obs(false, v, 50), n.Add(time.Duration(i)*time.Second))
		if err != nil || got.Stable || got.Event != "" {
			t.Fatalf("flapping was accepted: %v %v", got, err)
		}
	}
}
