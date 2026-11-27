package autoevents

import (
	"reflect"
	"testing"
	"time"
)

func steady(t *testing.T, p Policy, o Observation) Desired {
	t.Helper()
	planner, e := New(p)
	if e != nil {
		t.Fatal(e)
	}
	var d Desired
	for i := 0; i < 3; i++ {
		d, e = planner.Next(o, time.Unix(2000+int64(i), 0))
		if e != nil {
			t.Fatal(e)
		}
	}
	if !d.Stable {
		t.Fatal("not stable")
	}
	return d
}
func TestR03BKeepOnChargeRespectExplicitPolicy(t *testing.T) {
	p := pol()
	p.KeepOnCharge = false
	d := steady(t, p, obs(true, true, 14))
	if !reflect.DeepEqual(d.Events, []string{"charging"}) {
		t.Fatalf("unexpected charging policy %v", d.Events)
	}
	p.KeepOnCharge = true
	d = steady(t, p, obs(true, true, 14))
	if !reflect.DeepEqual(d.Events, []string{"charging", "screen_off"}) {
		t.Fatalf("keep on charge ignored: %v", d.Events)
	}
}
func TestR03BIndependentScreenLowPowerDesires(t *testing.T) {
	d := steady(t, pol(), obs(false, true, 12))
	if !reflect.DeepEqual(d.Events, []string{"screen_off", "low_power"}) || d.Event != "screen_off" {
		t.Fatalf("prioritized desires: %+v", d)
	}
}
func TestR03BChargingSuppressesLowPowerEvenWithKeepOnCharge(t *testing.T) {
	p := pol()
	p.KeepOnCharge = true
	d := steady(t, p, obs(true, false, 3))
	if len(d.Events) != 1 || d.Events[0] != "charging" {
		t.Fatalf("low-power after plug-in: %+v", d)
	}
}
