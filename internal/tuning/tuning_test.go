package tuning

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

type fakeRunner struct {
	files map[string]string
	calls []string
	fail  string
}

func (f *fakeRunner) Run(n string, a ...string) (string, error) {
	c := n + " " + strings.Join(a, " ")
	f.calls = append(f.calls, c)
	if f.fail != "" && strings.Contains(c, f.fail) {
		return "", errors.New("forced")
	}
	switch c {
	case "pm list packages -3":
		return "package:app.one\npackage:app.keep\n", nil
	case "pidof app.one":
		return "101", nil
	case "pidof com.google.android.gms":
		return "202", nil
	case "pidof netd":
		return "303", nil
	case "getprop ro.config.low_ram":
		return "false", nil
	case "cmd wifi status":
		return "Wi-Fi is enabled", nil
	}
	return "", nil
}
func (f *fakeRunner) Read(p string) (string, error) {
	if strings.HasPrefix(p, "/proc/") && strings.HasSuffix(p, "/stat") {
		return "1 (proc) S 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0", nil
	}
	v, ok := f.files[p]
	if !ok {
		return "", errors.New("missing " + p)
	}
	return v, nil
}
func (f *fakeRunner) Write(p, v string) error {
	if f.fail != "" && strings.Contains(p, f.fail) {
		return errors.New("forced")
	}
	f.files[p] = v
	return nil
}
func (f *fakeRunner) Glob(string) ([]string, error) {
	return []string{"/sys/devices/system/cpu/cpu0", "/sys/devices/system/cpu/cpu1", "/sys/devices/system/cpu/cpu2", "/sys/devices/system/cpu/cpu3"}, nil
}
func TestResolveMergesTemplateAndEvent(t *testing.T) {
	cfg := map[string]any{"events": map[string]any{"template": map[string]any{"handle_apps": "nice", "kill_wifi": true}, "manual": map[string]any{"handle_apps": "suspend", "doze": "deep"}}}
	e, err := Resolve(cfg, "manual")
	if err != nil {
		t.Fatal(err)
	}
	if e.HandleApps != "suspend" || !e.KillWiFi || e.Doze != "deep" {
		t.Fatalf("%+v", e)
	}
}
func TestApplyCoversEveryEffectAndCommits(t *testing.T) {
	d := t.TempDir()
	allow := filepath.Join(d, "allow")
	proc := filepath.Join(d, "proc")
	if err := os.WriteFile(allow, []byte("app.keep\n"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(proc, []byte("netd 10\n"), 0600); err != nil {
		t.Fatal(err)
	}
	f := &fakeRunner{files: map[string]string{}}
	for _, c := range []string{"cpu0", "cpu1", "cpu2", "cpu3"} {
		f.files["/sys/devices/system/cpu/"+c+"/cpufreq/scaling_governor"] = "schedutil"
		if c != "cpu0" {
			f.files["/sys/devices/system/cpu/"+c+"/online"] = "1"
		}
	}
	e := Event{Name: "manual", Allowlist: allow, ProcFile: proc, KeepOnCharge: true, Quit: true, HandleCores: "cpu0", DisableCores: "cpu3", HandleApps: "suspend", HandleGMS: "kill", HandleProc: true, LowRAM: true, Doze: "deep", KillWiFi: true}
	r, err := New(f).Apply(e, false)
	if err != nil {
		t.Fatal(err)
	}
	if !r.Applied {
		t.Fatal("not committed")
	}
	joined := strings.Join(f.calls, "\n")
	for _, want := range []string{"pm suspend --user 0 app.one", "pm disable com.google.android.gms", "renice 10 -p 303", "resetprop -n ro.config.low_ram true", "dumpsys deviceidle force-idle", "svc wifi disable"} {
		if !strings.Contains(joined, want) {
			t.Errorf("missing %q in %s", want, joined)
		}
	}
	if f.files["/sys/devices/system/cpu/cpu3/online"] != "0" {
		t.Fatal("cpu3 not disabled")
	}
}
func TestApplyFailureRollsBackAndDoesNotCommit(t *testing.T) {
	f := &fakeRunner{files: map[string]string{"/sys/devices/system/cpu/cpu1/cpufreq/scaling_governor": "schedutil"}, fail: "svc wifi disable"}
	e := Event{Name: "manual", HandleCores: "cpu1", DisableCores: "false", HandleApps: "false", HandleGMS: "false", Doze: "false", KillWiFi: true}
	r, err := New(f).Apply(e, false)
	if err == nil {
		t.Fatal("expected failure")
	}
	if r.Applied {
		t.Fatal("failed plan committed")
	}
	if got := f.files["/sys/devices/system/cpu/cpu1/cpufreq/scaling_governor"]; got != "schedutil" {
		t.Fatalf("rollback=%q", got)
	}
}
func TestRuleFileBounded(t *testing.T) {
	p := filepath.Join(t.TempDir(), "huge")
	if err := os.WriteFile(p, make([]byte, maxRuleBytes+1), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := readLines(p); err == nil {
		t.Fatal("expected bounded read failure")
	}
}

func TestSuspendRequiresAllowlist(t *testing.T) {
	f := &fakeRunner{files: map[string]string{}}
	_, err := New(f).Apply(Event{Name: "manual", HandleCores: "false", DisableCores: "false", HandleApps: "suspend", HandleGMS: "false", Doze: "false"}, false)
	if err == nil || !strings.Contains(err.Error(), "allowlist") {
		t.Fatalf("expected fail-closed allowlist error, got %v", err)
	}
}
func TestPackageSelectionUsesUserAppsPlusExplicitDenylist(t *testing.T) {
	got := filterPackages([]string{"user.one", "user.keep"}, []string{"user.keep"}, []string{"system.target"})
	want := []string{"system.target", "user.one"}
	if strings.Join(got, ",") != strings.Join(want, ",") {
		t.Fatalf("got %v want %v", got, want)
	}
}
func TestResolveCoversAllContractFields(t *testing.T) {
	cfg := map[string]any{"events": map[string]any{"template": map[string]any{"allowlist": "a", "denylist": "d", "proc_file": "p", "keep_on_charge": true, "quit": true, "handle_cores": "cpu0", "disable_cores": "cpu3", "handle_apps": "nice", "handle_gms": "kill", "handle_proc": true, "low_ram": true, "doze": "light", "kill_wifi": true}, "boot": map[string]any{}}}
	e, err := Resolve(cfg, "boot")
	if err != nil {
		t.Fatal(err)
	}
	if e.Allowlist != "a" || e.Denylist != "d" || e.ProcFile != "p" || !e.KeepOnCharge || !e.Quit || e.HandleCores != "cpu0" || e.DisableCores != "cpu3" || e.HandleApps != "nice" || e.HandleGMS != "kill" || !e.HandleProc || !e.LowRAM || e.Doze != "light" || !e.KillWiFi {
		t.Fatalf("%+v", e)
	}
}

func TestSnapshotRoundTripPreservesOriginals(t *testing.T) {
	f := &fakeRunner{files: map[string]string{"/sys/devices/system/cpu/cpu1/cpufreq/scaling_governor": "schedutil"}}
	e := New(f)
	_, err := e.Apply(Event{Name: "manual", HandleCores: "cpu1", DisableCores: "false", HandleApps: "false", HandleGMS: "false", Doze: "false"}, false)
	if err != nil {
		t.Fatal(err)
	}
	snap := e.Snapshot()
	if snap["file:/sys/devices/system/cpu/cpu1/cpufreq/scaling_governor"] != "schedutil" {
		t.Fatalf("snapshot=%v", snap)
	}
	e2 := New(f)
	e2.LoadSnapshot(snap)
	if got := e2.Snapshot(); got["file:/sys/devices/system/cpu/cpu1/cpufreq/scaling_governor"] != "schedutil" {
		t.Fatalf("round trip=%v", got)
	}
}
