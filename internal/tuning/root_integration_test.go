package tuning

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

// This suite is intentionally opt-in because it changes real Android root state.
// Run only on a disposable/rooted test device after setting XBS_ROOT_IT_ACK=effects-are-reversible.
type suRunner struct{}

func (suRunner) Run(name string, args ...string) (string, error) {
	parts := []string{shellQuoteIT(name)}
	for _, arg := range args {
		parts = append(parts, shellQuoteIT(arg))
	}
	out, err := exec.Command("su", "-c", strings.Join(parts, " ")).CombinedOutput()
	if err != nil {
		return string(out), fmt.Errorf("%s: %w: %s", name, err, strings.TrimSpace(string(out)))
	}
	return string(out), nil
}
func (suRunner) Read(path string) (string, error) {
	out, err := exec.Command("su", "-c", "cat "+shellQuoteIT(path)).CombinedOutput()
	return strings.TrimSpace(string(out)), err
}
func (suRunner) Write(path, value string) error {
	cmd := "printf %s " + shellQuoteIT(value) + " > " + shellQuoteIT(path)
	out, err := exec.Command("su", "-c", cmd).CombinedOutput()
	if err != nil {
		return fmt.Errorf("write %s: %w: %s", path, err, strings.TrimSpace(string(out)))
	}
	return nil
}
func (suRunner) Glob(pattern string) ([]string, error) {
	out, err := exec.Command("su", "-c", "for P in "+pattern+"; do [ -e \"$P\" ] && printf '%s\\n' \"$P\"; done").CombinedOutput()
	if err != nil {
		return nil, err
	}
	fields := strings.Fields(string(out))
	return fields, nil
}

func shellQuoteIT(value string) string { return "'" + strings.ReplaceAll(value, "'", "'\\''") + "'" }

func requireRootIntegration(t *testing.T) suRunner {
	t.Helper()
	if os.Getenv("XBS_ROOT_IT_ACK") != "effects-are-reversible" {
		t.Skip("set XBS_ROOT_IT_ACK=effects-are-reversible on a disposable rooted Android test device")
	}
	r := suRunner{}
	out, err := r.Run("id", "-u")
	if err != nil || strings.TrimSpace(out) != "0" {
		t.Skip("root via su is unavailable")
	}
	return r
}

func applyRestore(t *testing.T, r Runner, ev Event) {
	t.Helper()
	e := New(r)
	result, err := e.Apply(ev, false)
	if err != nil {
		t.Fatalf("apply %s: %v", ev.Name, err)
	}
	if !result.Applied {
		t.Fatalf("%s did not commit", ev.Name)
	}
	if _, err := e.Restore(ev); err != nil {
		t.Fatalf("restore %s: %v", ev.Name, err)
	}
	if len(e.Snapshot()) != 0 {
		t.Fatalf("%s left unrestored snapshot entries: %v", ev.Name, e.Snapshot())
	}
}

func TestRootIntegrationEffectFamilies(t *testing.T) {
	r := requireRootIntegration(t)
	dir := t.TempDir()

	t.Run("cpu_governor", func(t *testing.T) {
		core := os.Getenv("XBS_ROOT_IT_CPU_GOV")
		if core == "" {
			t.Skip("set XBS_ROOT_IT_CPU_GOV, e.g. cpu1")
		}
		avail, err := r.Read(filepath.Join("/sys/devices/system/cpu", core, "cpufreq/scaling_available_governors"))
		if err != nil || !strings.Contains(" "+avail+" ", " powersave ") {
			t.Skip("selected CPU does not expose powersave governor")
		}
		applyRestore(t, r, Event{Name: "it-cpu-governor", HandleCores: core, DisableCores: "false", HandleApps: "false", HandleGMS: "false", Doze: "false"})
	})

	t.Run("cpu_online", func(t *testing.T) {
		core := os.Getenv("XBS_ROOT_IT_CPU_OFFLINE")
		if core == "" || core == "cpu0" {
			t.Skip("set XBS_ROOT_IT_CPU_OFFLINE to a disposable nonzero CPU")
		}
		applyRestore(t, r, Event{Name: "it-cpu-online", HandleCores: "false", DisableCores: core, HandleApps: "false", HandleGMS: "false", Doze: "false"})
	})

	t.Run("apps", func(t *testing.T) {
		pkg := os.Getenv("XBS_ROOT_IT_PACKAGE")
		if pkg == "" {
			t.Skip("set XBS_ROOT_IT_PACKAGE to a disposable running user package")
		}
		allow := filepath.Join(dir, "apps.allow")
		deny := filepath.Join(dir, "apps.deny")
		out, err := r.Run("pm", "list", "packages", "-3")
		if err != nil {
			t.Fatal(err)
		}
		var all []string
		for _, word := range strings.Fields(out) {
			all = append(all, strings.TrimPrefix(word, "package:"))
		}
		if err := os.WriteFile(allow, []byte(strings.Join(all, "\n")+"\n"), 0600); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(deny, []byte(pkg+"\n"), 0600); err != nil {
			t.Fatal(err)
		}
		applyRestore(t, r, Event{Name: "it-apps", Allowlist: allow, Denylist: deny, HandleCores: "false", DisableCores: "false", HandleApps: "nice", HandleGMS: "false", Doze: "false"})
	})

	t.Run("gms", func(t *testing.T) {
		pids, _ := r.Run("pidof", "com.google.android.gms")
		if strings.TrimSpace(pids) == "" {
			t.Skip("GMS is not running")
		}
		applyRestore(t, r, Event{Name: "it-gms", HandleCores: "false", DisableCores: "false", HandleApps: "false", HandleGMS: "nice", Doze: "false"})
	})

	t.Run("proc", func(t *testing.T) {
		name := os.Getenv("XBS_ROOT_IT_PROC_NAME")
		if name == "" {
			t.Skip("set XBS_ROOT_IT_PROC_NAME to a disposable running process name")
		}
		if pids, _ := r.Run("pidof", name); strings.TrimSpace(pids) == "" {
			t.Skip("configured process is not running")
		}
		proc := filepath.Join(dir, "proc.rules")
		if err := os.WriteFile(proc, []byte(name+" 10\n"), 0600); err != nil {
			t.Fatal(err)
		}
		applyRestore(t, r, Event{Name: "it-proc", ProcFile: proc, HandleCores: "false", DisableCores: "false", HandleApps: "false", HandleGMS: "false", HandleProc: true, Doze: "false"})
	})

	t.Run("low_ram", func(t *testing.T) {
		applyRestore(t, r, Event{Name: "it-low-ram", HandleCores: "false", DisableCores: "false", HandleApps: "false", HandleGMS: "false", LowRAM: true, Doze: "false"})
	})

	t.Run("doze", func(t *testing.T) {
		applyRestore(t, r, Event{Name: "it-doze", HandleCores: "false", DisableCores: "false", HandleApps: "false", HandleGMS: "false", Doze: "deep"})
	})

	t.Run("wifi", func(t *testing.T) {
		if os.Getenv("XBS_ROOT_IT_ALLOW_WIFI_TOGGLE") != "1" {
			t.Skip("set XBS_ROOT_IT_ALLOW_WIFI_TOGGLE=1; this briefly disables Wi-Fi")
		}
		applyRestore(t, r, Event{Name: "it-wifi", HandleCores: "false", DisableCores: "false", HandleApps: "false", HandleGMS: "false", Doze: "false", KillWiFi: true})
	})
}
