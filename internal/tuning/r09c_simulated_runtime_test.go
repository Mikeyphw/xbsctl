package tuning

import (
	"errors"
	"fmt"
	"strings"
	"testing"
)

// r09cAndroid is an entirely in-memory Android command/sysfs simulator.
// No system command, root service, PM, or real /sys path is ever touched.
type r09cAndroid struct {
	files                 map[string]string
	wifi, gms, forcedDoze bool
	lowRAM                string
	commands              []string
	writes                []string
	failCommand           string
	failWrite             string
}

func r09cNewAndroid() *r09cAndroid {
	return &r09cAndroid{files: map[string]string{
		"/sys/devices/system/cpu/cpu1/cpufreq/scaling_governor": "schedutil",
		"/sys/devices/system/cpu/cpu1/online":                   "1",
	}, wifi: true, gms: true, lowRAM: "false"}
}
func (r *r09cAndroid) Read(path string) (string, error) {
	v, ok := r.files[path]
	if !ok {
		return "", fmt.Errorf("simulated file missing: %s", path)
	}
	return v, nil
}
func (r *r09cAndroid) Write(path, value string) error {
	r.writes = append(r.writes, path+"="+value)
	if r.failWrite != "" && strings.Contains(path+"="+value, r.failWrite) {
		return errors.New("simulated sysfs write fault")
	}
	if _, ok := r.files[path]; !ok {
		return errors.New("unrecognized simulated sysfs path")
	}
	r.files[path] = value
	return nil
}
func (r *r09cAndroid) Glob(pattern string) ([]string, error) {
	if pattern != "/sys/devices/system/cpu/cpu[0-9]*" && pattern != "/sys/devices/system/cpu/cpu*" {
		return nil, fmt.Errorf("unexpected glob %q", pattern)
	}
	return []string{"/sys/devices/system/cpu/cpu0", "/sys/devices/system/cpu/cpu1"}, nil
}
func (r *r09cAndroid) Run(name string, args ...string) (string, error) {
	call := strings.TrimSpace(name + " " + strings.Join(args, " "))
	r.commands = append(r.commands, call)
	if r.failCommand != "" && strings.Contains(call, r.failCommand) {
		return "", errors.New("simulated Android command fault")
	}
	switch call {
	case "cmd wifi status":
		if r.wifi {
			return "Wi-Fi is enabled", nil
		}
		return "Wi-Fi is disabled", nil
	case "svc wifi disable":
		r.wifi = false
		return "", nil
	case "svc wifi enable":
		r.wifi = true
		return "", nil
	case "dumpsys deviceidle get force":
		if r.forcedDoze {
			return "true", nil
		}
		return "false", nil
	case "dumpsys deviceidle force-idle", "dumpsys deviceidle force-idle light":
		r.forcedDoze = true
		return "", nil
	case "dumpsys deviceidle unforce":
		r.forcedDoze = false
		return "", nil
	case "getprop ro.config.low_ram":
		return r.lowRAM, nil
	case "resetprop -n ro.config.low_ram true":
		r.lowRAM = "true"
		return "", nil
	case "resetprop -n ro.config.low_ram false":
		r.lowRAM = "false"
		return "", nil
	case "pm list packages -e com.google.android.gms":
		if r.gms {
			return "package:com.google.android.gms\n", nil
		}
		return "", nil
	case "pm list packages -d com.google.android.gms":
		if !r.gms {
			return "package:com.google.android.gms\n", nil
		}
		return "", nil
	case "pm disable com.google.android.gms":
		r.gms = false
		return "", nil
	case "pm enable com.google.android.gms":
		r.gms = true
		return "", nil
	case "am force-stop com.google.android.gms":
		return "", nil
	}
	return "", fmt.Errorf("unmocked Android command refused: %s", call)
}
func r09cEvent() Event {
	return Event{Name: "manual", HandleCores: "false", DisableCores: "false", HandleApps: "false", HandleGMS: "false", Doze: "false"}
}
func r09cSnapshot(w *r09cAndroid) string {
	return fmt.Sprintf("gov=%s;online=%s;wifi=%t;gms=%t;doze=%t;lowram=%s",
		w.files["/sys/devices/system/cpu/cpu1/cpufreq/scaling_governor"], w.files["/sys/devices/system/cpu/cpu1/online"],
		w.wifi, w.gms, w.forcedDoze, w.lowRAM)
}

func TestR09CFakeAndroidEffectRoundTrip(t *testing.T) {
	cases := []struct {
		name      string
		configure func(*Event)
	}{
		{"cpu-governor", func(e *Event) { e.HandleCores = "cpu1" }},
		{"cpu-online", func(e *Event) { e.DisableCores = "cpu1" }},
		{"wifi", func(e *Event) { e.KillWiFi = true }},
		{"gms", func(e *Event) { e.HandleGMS = "kill" }},
		{"doze", func(e *Event) { e.Doze = "deep" }},
		{"low-ram", func(e *Event) { e.LowRAM = true }},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			w := r09cNewAndroid()
			before := r09cSnapshot(w)
			ev := r09cEvent()
			tc.configure(&ev)
			engine := New(w)
			result, err := engine.Apply(ev, false)
			if err != nil || !result.Applied {
				t.Fatalf("apply=%+v err=%v", result, err)
			}
			if r09cSnapshot(w) == before {
				t.Fatalf("%s did not mutate fake Android state", tc.name)
			}
			if len(engine.Snapshot()) == 0 {
				t.Fatal("missing source-of-truth WAL preimage")
			}
			recovered := New(w)
			recovered.LoadSnapshot(engine.Snapshot())
			restore, err := recovered.Restore(ev)
			if err != nil || !restore.Applied {
				t.Fatalf("restore=%+v err=%v", restore, err)
			}
			if got := r09cSnapshot(w); got != before {
				t.Fatalf("state mismatch after recovery: got=%q want=%q", got, before)
			}
			if len(recovered.Snapshot()) != 0 {
				t.Fatalf("WAL retained: %v", recovered.Snapshot())
			}
		})
	}
}

func TestR09CFakeAndroidPreexistingExternalState(t *testing.T) {
	for _, tc := range []struct {
		name      string
		configure func(*r09cAndroid)
		effect    func(*Event)
	}{
		{"wifi-already-off", func(r *r09cAndroid) { r.wifi = false }, func(e *Event) { e.KillWiFi = true }},
		{"gms-already-disabled", func(r *r09cAndroid) { r.gms = false }, func(e *Event) { e.HandleGMS = "kill" }},
		{"doze-externally-forced", func(r *r09cAndroid) { r.forcedDoze = true }, func(e *Event) { e.Doze = "deep" }},
	} {
		t.Run(tc.name, func(t *testing.T) {
			w := r09cNewAndroid()
			tc.configure(w)
			before := r09cSnapshot(w)
			e := r09cEvent()
			tc.effect(&e)
			engine := New(w)
			result, err := engine.Apply(e, false)
			if err != nil || !result.Applied {
				t.Fatalf("apply=%+v %v", result, err)
			}
			if len(engine.Snapshot()) != 0 {
				t.Fatalf("claimed external state: %v", engine.Snapshot())
			}
			if _, err := engine.Restore(e); err != nil {
				t.Fatal(err)
			}
			if got := r09cSnapshot(w); got != before {
				t.Fatalf("external state changed: %s -> %s", before, got)
			}
			for _, cmd := range w.commands {
				if cmd == "svc wifi enable" || cmd == "pm enable com.google.android.gms" || cmd == "dumpsys deviceidle unforce" {
					t.Fatalf("restored state not owned by XBS: %q", cmd)
				}
			}
		})
	}
}

func TestR09CFakeAndroidProbeAndWriteFaults(t *testing.T) {
	cases := []struct {
		name                   string
		commandFail, writeFail string
		configure              func(*Event)
	}{
		{"wifi-probe", "cmd wifi status", "", func(e *Event) { e.KillWiFi = true }},
		{"gms-probe", "pm list packages -d", "", func(e *Event) { e.HandleGMS = "kill" }},
		{"doze-probe", "dumpsys deviceidle get force", "", func(e *Event) { e.Doze = "deep" }},
		{"lowram-probe", "getprop ro.config.low_ram", "", func(e *Event) { e.LowRAM = true }},
		{"governor-write", "", "=powersave", func(e *Event) { e.HandleCores = "cpu1" }},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			w := r09cNewAndroid()
			before := r09cSnapshot(w)
			w.failCommand = tc.commandFail
			w.failWrite = tc.writeFail
			ev := r09cEvent()
			tc.configure(&ev)
			engine := New(w)
			result, err := engine.Apply(ev, false)
			if err == nil || result.Applied {
				t.Fatalf("fault falsely committed: %v %+v", err, result)
			}
			if got := r09cSnapshot(w); got != before {
				t.Fatalf("unrestored after injected fault: got=%q want=%q", got, before)
			}
			if len(engine.Snapshot()) != 0 {
				t.Fatalf("unexpected remaining WAL: %v", engine.Snapshot())
			}
		})
	}
}

func TestR09CFakeAndroidDurableJournalGate(t *testing.T) {
	w := r09cNewAndroid()
	before := r09cSnapshot(w)
	engine := New(w)
	engine.SetJournal(func(map[string]string) error { return errors.New("simulated fsync failure") })
	ev := r09cEvent()
	ev.KillWiFi = true
	result, err := engine.Apply(ev, false)
	if err == nil || result.Applied {
		t.Fatalf("WAL failure applied effects: %+v %v", result, err)
	}
	if len(engine.Snapshot()) != 0 || r09cSnapshot(w) != before {
		t.Fatal("effect changed without durable journal")
	}
	if strings.Contains(strings.Join(w.commands, "\n"), "svc wifi disable") {
		t.Fatal("un-journaled wifi disable")
	}
}

func TestR09CFakeAndroidDryRunIsPure(t *testing.T) {
	w := r09cNewAndroid()
	before := r09cSnapshot(w)
	e := r09cEvent()
	e.HandleCores = "cpu1"
	e.DisableCores = "cpu1"
	e.HandleGMS = "kill"
	e.Doze = "deep"
	e.KillWiFi = true
	e.LowRAM = true
	engine := New(w)
	result, err := engine.Apply(e, true)
	if err != nil {
		t.Fatal(err)
	}
	if !result.Applied {
		t.Fatal("dry run must be indicated as simulated success")
	}
	if r09cSnapshot(w) != before || len(engine.Snapshot()) != 0 || len(w.writes) != 0 || len(w.commands) != 0 {
		t.Fatalf("dry-run touched Android model; writes=%v cmds=%v snapshot=%v", w.writes, w.commands, engine.Snapshot())
	}
}

var _ Runner = (*r09cAndroid)(nil)
