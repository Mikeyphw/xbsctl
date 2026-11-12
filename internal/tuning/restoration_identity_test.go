package tuning

import (
	"errors"
	"os"
	"strings"
	"testing"
)

func r01eStat(ticks string) string {
	return "771 (proc name) S " + strings.Repeat("0 ", 18) + ticks
}

type r01eRunner struct {
	*fakeRunner
	stat                            string
	missing                         bool
	disabled, enabled, forced, wifi string
}

func (r *r01eRunner) Read(path string) (string, error) {
	if strings.HasPrefix(path, "/proc/") && strings.HasSuffix(path, "/stat") {
		if r.missing {
			return "", os.ErrNotExist
		}
		return r.stat, nil
	}
	return r.fakeRunner.Read(path)
}
func (r *r01eRunner) Run(n string, args ...string) (string, error) {
	cmd := strings.TrimSpace(n + " " + strings.Join(args, " "))
	switch cmd {
	case "pm list packages -d com.google.android.gms":
		r.calls = append(r.calls, cmd)
		return r.disabled, nil
	case "pm list packages -e com.google.android.gms":
		r.calls = append(r.calls, cmd)
		return r.enabled, nil
	case "dumpsys deviceidle get force":
		r.calls = append(r.calls, cmd)
		return r.forced, nil
	case "cmd wifi status":
		r.calls = append(r.calls, cmd)
		return r.wifi, nil
	}
	return r.fakeRunner.Run(n, args...)
}
func r01eNewRunner() *r01eRunner {
	return &r01eRunner{fakeRunner: &fakeRunner{files: map[string]string{}}, stat: r01eStat("123456"), enabled: "package:com.google.android.gms\n", forced: "false", wifi: "Wi-Fi is enabled"}
}
func r01eRan(calls []string, command string) bool {
	for _, call := range calls {
		if call == command {
			return true
		}
	}
	return false
}
func TestR01EPIDIdentitySurvivesStartAndRestore(t *testing.T) {
	r := r01eNewRunner()
	e := New(r)
	if err := e.renice("771", 10); err != nil {
		t.Fatal(err)
	}
	key := "nice:771@123456"
	if e.Snapshot()[key] != "0" {
		t.Fatalf("identity journal missing: %#v", e.Snapshot())
	}
	if _, err := e.Restore(safeEvent()); err != nil {
		t.Fatal(err)
	}
	if !r01eRan(r.calls, "renice 0 -p 771") || len(e.Snapshot()) != 0 {
		t.Fatalf("restore %#v %+v", r.calls, e.Snapshot())
	}
}
func TestR01EPIDReuseCannotRestoreAnotherProcess(t *testing.T) {
	r := r01eNewRunner()
	e := New(r)
	if err := e.renice("771", 10); err != nil {
		t.Fatal(err)
	}
	r.stat = r01eStat("999999")
	if _, err := e.Restore(safeEvent()); err != nil {
		t.Fatal(err)
	}
	if r01eRan(r.calls, "renice 0 -p 771") || len(e.Snapshot()) != 0 {
		t.Fatalf("old process priority applied to new PID: %v %#v", r.calls, e.Snapshot())
	}
}
func TestR01EExitedPIDReleasesSnapshotWithoutRenice(t *testing.T) {
	r := r01eNewRunner()
	e := New(r)
	if err := e.renice("771", 10); err != nil {
		t.Fatal(err)
	}
	r.missing = true
	if _, err := e.Restore(safeEvent()); err != nil {
		t.Fatal(err)
	}
	if r01eRan(r.calls, "renice 0 -p 771") || len(e.Snapshot()) != 0 {
		t.Fatalf("unsafe exited-pid restore: %v %#v", r.calls, e.Snapshot())
	}
}
func TestR01EStalePIDCannotBeRenicedAgain(t *testing.T) {
	r := r01eNewRunner()
	e := New(r)
	if err := e.renice("771", 10); err != nil {
		t.Fatal(err)
	}
	r.stat = r01eStat("999999")
	if err := e.renice("771", 19); err == nil {
		t.Fatal("new process inherited stale lease")
	}
	if r01eRan(r.calls, "renice 19 -p 771") {
		t.Fatal("modified newly reused PID")
	}
}
func TestR01ELegacyPIDOnlySnapshotRefusesBlindRestore(t *testing.T) {
	r := r01eNewRunner()
	e := New(r)
	e.LoadSnapshot(map[string]string{"nice:771": "0"})
	if _, err := e.Restore(safeEvent()); err == nil || !strings.Contains(err.Error(), "legacy") {
		t.Fatalf("legacy record accepted: %v", err)
	}
	if r01eRan(r.calls, "renice 0 -p 771") || len(e.Snapshot()) != 1 {
		t.Fatalf("unsafe legacy restore: %v %#v", r.calls, e.Snapshot())
	}
}
func TestR01EInvalidProcStatFailsBeforeRenice(t *testing.T) {
	for _, s := range []string{"no parenthesis", "771 (unfinished", "771 (ok) S 0 0 0", r01eStat("0"), r01eStat("garbage")} {
		r := r01eNewRunner()
		r.stat = s
		e := New(r)
		if err := e.renice("771", 10); err == nil {
			t.Fatalf("invalid stat accepted %q", s)
		}
		if len(r.calls) != 0 {
			t.Fatalf("unexpected command: %v", r.calls)
		}
	}
}
func TestR01EPreForcedDozeHasNoOwnership(t *testing.T) {
	r := r01eNewRunner()
	r.forced = "true"
	e := New(r)
	ev := safeEvent()
	ev.Doze = "deep"
	if _, err := e.Apply(ev, false); err != nil {
		t.Fatal(err)
	}
	if _, err := e.Restore(ev); err != nil {
		t.Fatal(err)
	}
	if r01eRan(r.calls, "dumpsys deviceidle force-idle") || r01eRan(r.calls, "dumpsys deviceidle unforce") || len(e.Snapshot()) != 0 {
		t.Fatalf("stole external idle state: %v %#v", r.calls, e.Snapshot())
	}
}
func TestR01EDozeProbeUnrecognizedFailsClosed(t *testing.T) {
	r := r01eNewRunner()
	r.forced = "unknown"
	e := New(r)
	ev := safeEvent()
	ev.Doze = "deep"
	if _, err := e.Apply(ev, false); err == nil {
		t.Fatal("invalid Doze state accepted")
	}
	if r01eRan(r.calls, "dumpsys deviceidle force-idle") {
		t.Fatal("mutated Doze after bad probe")
	}
}
func TestR01EDozeOwnsAndReleasesForcedIdle(t *testing.T) {
	r := r01eNewRunner()
	e := New(r)
	ev := safeEvent()
	ev.Doze = "deep"
	if _, err := e.Apply(ev, false); err != nil {
		t.Fatal(err)
	}
	if e.Snapshot()["doze:owned"] != "unforce" {
		t.Fatal("missing Doze WAL")
	}
	if _, err := e.Restore(ev); err != nil {
		t.Fatal(err)
	}
	if !r01eRan(r.calls, "dumpsys deviceidle unforce") || len(e.Snapshot()) != 0 {
		t.Fatalf("Doze not restored %v", r.calls)
	}
}
func TestR01EGMSRejectsSuffixCollisionAndAmbiguousState(t *testing.T) {
	for _, pair := range [][2]string{{"package:com.google.android.gms.clone\n", ""}, {"", ""}, {"package:com.google.android.gms\n", "package:com.google.android.gms\n"}} {
		r := r01eNewRunner()
		r.disabled, r.enabled = pair[0], pair[1]
		e := New(r)
		ev := safeEvent()
		ev.HandleGMS = "kill"
		if _, err := e.Apply(ev, false); err == nil {
			t.Fatalf("ambiguous GMS state accepted %q %q", pair[0], pair[1])
		}
		if r01eRan(r.calls, "pm disable com.google.android.gms") {
			t.Fatal("disabled GMS without verified initial state")
		}
	}
}
func TestR01EGMSInitiallyDisabledDoesNotReenable(t *testing.T) {
	r := r01eNewRunner()
	r.disabled = "package:com.google.android.gms\n"
	r.enabled = ""
	e := New(r)
	ev := safeEvent()
	ev.HandleGMS = "kill"
	if _, err := e.Apply(ev, false); err != nil {
		t.Fatal(err)
	}
	if _, err := e.Restore(ev); err != nil {
		t.Fatal(err)
	}
	if r01eRan(r.calls, "pm enable com.google.android.gms") {
		t.Fatal("enabled formerly disabled GMS")
	}
}
func TestR01EWiFiNegatedEnabledIsRefused(t *testing.T) {
	r := r01eNewRunner()
	r.wifi = "Wi-Fi is not enabled"
	e := New(r)
	ev := safeEvent()
	ev.KillWiFi = true
	if _, err := e.Apply(ev, false); err == nil {
		t.Fatal("negated Wi-Fi state mistaken for enabled")
	}
	if r01eRan(r.calls, "svc wifi disable") {
		t.Fatal("Wi-Fi changed after invalid state probe")
	}
}
func TestR01EReadErrorForProcStateIsNotProcessExit(t *testing.T) {
	r := r01eNewRunner()
	e := New(r)
	e.LoadSnapshot(map[string]string{"nice:771@123456": "0"})
	r.stat = "broken"
	_, err := e.Restore(safeEvent())
	if err == nil || !errors.Is(err, os.ErrNotExist) && len(e.Snapshot()) == 0 {
		t.Fatalf("bad proc snapshot silently released: %v %#v", err, e.Snapshot())
	}
}
