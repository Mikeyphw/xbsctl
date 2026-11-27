package tuning

import (
	"strings"
	"testing"
)

func safeEvent() Event {
	return Event{Name: "manual", HandleCores: "false", DisableCores: "false", HandleApps: "false", HandleGMS: "false", Doze: "false"}
}

func TestInitialStateProbeFailuresRefuseToMutate(t *testing.T) {
	for _, tc := range []struct {
		name, failingCommand, forbiddenMutation string
		configure                               func(*Event)
	}{
		{"wifi_status_failed", "cmd wifi status", "svc wifi disable", func(ev *Event) { ev.KillWiFi = true }},
		{"gms_status_failed", "pm list packages -d com.google.android.gms", "pm disable com.google.android.gms", func(ev *Event) { ev.HandleGMS = "kill" }},
		{"low_ram_status_failed", "getprop ro.config.low_ram", "resetprop -n ro.config.low_ram true", func(ev *Event) { ev.LowRAM = true }},
	} {
		t.Run(tc.name, func(t *testing.T) {
			runner := &fakeRunner{files: map[string]string{}, fail: tc.failingCommand}
			ev := safeEvent()
			tc.configure(&ev)
			result, err := New(runner).Apply(ev, false)
			if err == nil || result.Applied {
				t.Fatalf("refusal not enforced: result=%#v err=%v", result, err)
			}
			for _, call := range runner.calls {
				if strings.HasPrefix(call, tc.forbiddenMutation) {
					t.Errorf("changed system state after failed probe: %s", call)
				}
			}
		})
	}
}

type probeOverrideRunner struct {
	*fakeRunner
	probes map[string]string
}

func (r *probeOverrideRunner) Run(name string, args ...string) (string, error) {
	call := strings.TrimSpace(name + " " + strings.Join(args, " "))
	if value, ok := r.probes[call]; ok {
		r.calls = append(r.calls, call)
		return value, nil
	}
	return r.fakeRunner.Run(name, args...)
}

func TestUnknownOriginalStatesRefuseMutations(t *testing.T) {
	for _, tc := range []struct {
		name, probe, response, forbidden string
		field                            func(*Event)
	}{
		{"wifi_unknown", "cmd wifi status", "unexpected response", "svc wifi disable", func(e *Event) { e.KillWiFi = true }},
		{"low_ram_unknown", "getprop ro.config.low_ram", "", "resetprop -n ro.config.low_ram true", func(e *Event) { e.LowRAM = true }},
	} {
		t.Run(tc.name, func(t *testing.T) {
			fake := &fakeRunner{files: map[string]string{}}
			r := &probeOverrideRunner{fakeRunner: fake, probes: map[string]string{tc.probe: tc.response}}
			ev := safeEvent()
			tc.field(&ev)
			result, err := New(r).Apply(ev, false)
			if err == nil || result.Applied {
				t.Fatalf("unknown original state accepted: %#v %v", result, err)
			}
			for _, call := range fake.calls {
				if strings.HasPrefix(call, tc.forbidden) {
					t.Errorf("unsafe mutation: %s", call)
				}
			}
		})
	}
}

func TestPreDisabledGmsIsNeverReenabled(t *testing.T) {
	fake := &fakeRunner{files: map[string]string{}}
	r := &probeOverrideRunner{fakeRunner: fake, probes: map[string]string{
		"pm list packages -d com.google.android.gms": "package:com.google.android.gms\n",
		"pm list packages -e com.google.android.gms": "",
	}}
	ev := safeEvent()
	ev.HandleGMS = "kill"
	engine := New(r)
	result, err := engine.Apply(ev, false)
	if err != nil || !result.Applied {
		t.Fatalf("apply failed: %#v %v", result, err)
	}
	result, err = engine.Restore(ev)
	if err != nil || !result.Applied {
		t.Fatalf("restore failed: %#v %v", result, err)
	}
	for _, call := range fake.calls {
		if call == "pm enable com.google.android.gms" {
			t.Fatalf("re-enabled GMS originally disabled: %v", fake.calls)
		}
	}
}

func TestPreDisabledGmsIsNotEnabledDuringRollback(t *testing.T) {
	fake := &fakeRunner{files: map[string]string{}, fail: "svc wifi disable"}
	r := &probeOverrideRunner{fakeRunner: fake, probes: map[string]string{
		"pm list packages -d com.google.android.gms": "package:com.google.android.gms\n",
		"pm list packages -e com.google.android.gms": "",
	}}
	ev := safeEvent()
	ev.HandleGMS = "kill"
	ev.KillWiFi = true
	result, err := New(r).Apply(ev, false)
	if err == nil || result.Applied {
		t.Fatalf("expected Wi-Fi mutation failure: %#v %v", result, err)
	}
	for _, call := range fake.calls {
		if call == "pm enable com.google.android.gms" {
			t.Fatalf("rollback enabled originally disabled GMS: %v", fake.calls)
		}
	}
}
