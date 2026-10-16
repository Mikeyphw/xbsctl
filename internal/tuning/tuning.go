package tuning

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
)

const maxRuleBytes = 1 << 20

type Event struct {
	Name                                             string `json:"name"`
	Allowlist, Denylist, ProcFile                    string
	KeepOnCharge, Quit                               bool
	HandleCores, DisableCores, HandleApps, HandleGMS string
	HandleProc, LowRAM                               bool
	Doze                                             string
	KillWiFi                                         bool
}
type Outcome struct{ Field, State, Detail string }
type Result struct {
	Event    string
	Applied  bool
	Outcomes []Outcome
}

type Runner interface {
	Run(name string, args ...string) (string, error)
	Read(path string) (string, error)
	Write(path, value string) error
	Glob(pattern string) ([]string, error)
}
type SystemRunner struct{}

func (SystemRunner) Run(n string, a ...string) (string, error) {
	b, e := exec.Command(n, a...).CombinedOutput()
	if e != nil {
		return string(b), fmt.Errorf("%s %v: %w: %s", n, a, e, strings.TrimSpace(string(b)))
	}
	return string(b), nil
}
func (SystemRunner) Read(p string) (string, error) {
	b, e := os.ReadFile(p)
	return strings.TrimSpace(string(b)), e
}
func (SystemRunner) Write(p, v string) error         { return os.WriteFile(p, []byte(v), 0o644) }
func (SystemRunner) Glob(p string) ([]string, error) { return filepath.Glob(p) }

type Engine struct {
	R        Runner
	original map[string]string
}

func New(r Runner) *Engine {
	if r == nil {
		r = SystemRunner{}
	}
	return &Engine{R: r, original: map[string]string{}}
}

// Snapshot returns a copy of the pre-effect values required for crash-safe restoration.
func (e *Engine) Snapshot() map[string]string { return clone(e.original) }

// LoadSnapshot restores durable pre-effect values after daemon process recreation.
func (e *Engine) LoadSnapshot(values map[string]string) {
	e.original = clone(values)
}

func Resolve(cfg map[string]any, name string) (Event, error) {
	evs, ok := cfg["events"].(map[string]any)
	if !ok {
		return Event{}, errors.New("events must be an object")
	}
	raw, ok := evs[name].(map[string]any)
	if !ok {
		return Event{}, fmt.Errorf("unknown event: %s", name)
	}
	merged := map[string]any{}
	if t, ok := evs["template"].(map[string]any); ok {
		for k, v := range t {
			merged[k] = v
		}
	}
	for k, v := range raw {
		merged[k] = v
	}
	e := Event{Name: name, HandleCores: "false", DisableCores: "false", HandleApps: "false", HandleGMS: "false", Doze: "false"}
	e.Allowlist = str(merged["allowlist"])
	e.Denylist = str(merged["denylist"])
	e.ProcFile = str(merged["proc_file"])
	e.KeepOnCharge = boolean(merged["keep_on_charge"])
	e.Quit = boolean(merged["quit"])
	e.HandleProc = boolean(merged["handle_proc"])
	e.LowRAM = boolean(merged["low_ram"])
	e.KillWiFi = boolean(merged["kill_wifi"])
	for p, d := range map[*string]string{&e.HandleCores: "handle_cores", &e.DisableCores: "disable_cores", &e.HandleApps: "handle_apps", &e.HandleGMS: "handle_gms", &e.Doze: "doze"} {
		if v := str(merged[d]); v != "" {
			*p = strings.ToLower(v)
		}
	}
	return e, nil
}
func str(v any) string {
	if s, ok := v.(string); ok {
		return strings.TrimSpace(s)
	}
	if b, ok := v.(bool); ok && !b {
		return "false"
	}
	return ""
}
func boolean(v any) bool {
	switch x := v.(type) {
	case bool:
		return x
	case string:
		b, _ := strconv.ParseBool(strings.TrimSpace(x))
		return b
	}
	return false
}

func (e *Engine) remember(key, value string) {
	if _, ok := e.original[key]; !ok {
		e.original[key] = value
	}
}
func (e *Engine) restoreRemembered(key string, restore func(string) error) error {
	v, ok := e.original[key]
	if !ok {
		return nil
	}
	if err := restore(v); err != nil {
		return err
	}
	delete(e.original, key)
	return nil
}
func (e *Engine) niceOf(pid string) (string, error) {
	s, err := e.R.Read(filepath.Join("/proc", pid, "stat"))
	if err != nil {
		return "", err
	}
	// comm may contain spaces inside parentheses; fields after the final ')' begin at field 3.
	i := strings.LastIndex(s, ")")
	if i < 0 || i+2 >= len(s) {
		return "", errors.New("malformed /proc stat")
	}
	f := strings.Fields(s[i+2:])
	if len(f) < 17 {
		return "", errors.New("short /proc stat")
	}
	return f[16], nil // process stat field 19 (nice)
}
func (e *Engine) renice(pid string, value int) error {
	key := "nice:" + pid
	if _, ok := e.original[key]; !ok {
		old, err := e.niceOf(pid)
		if err != nil {
			return err
		}
		e.remember(key, old)
	}
	_, err := e.R.Run("renice", strconv.Itoa(value), "-p", pid)
	return err
}
func unique(xs []string) []string {
	m := map[string]bool{}
	out := []string{}
	for _, x := range xs {
		if x != "" && !m[x] {
			m[x] = true
			out = append(out, x)
		}
	}
	sort.Strings(out)
	return out
}

func (e *Engine) Apply(ev Event, dry bool) (Result, error) {
	r := Result{Event: ev.Name}
	add := func(f, s, d string) { r.Outcomes = append(r.Outcomes, Outcome{f, s, d}) }
	add("keep_on_charge", "policy", fmt.Sprint(ev.KeepOnCharge))
	add("quit", "policy", fmt.Sprint(ev.Quit))
	if dry {
		for _, f := range []string{"handle_cores", "disable_cores", "handle_apps", "handle_gms", "handle_proc", "low_ram", "doze", "kill_wifi"} {
			add(f, "dry_run", "")
		}
		r.Applied = true
		return r, nil
	}
	undo := []func() error{}
	before := clone(e.original)
	fail := func(err error) (Result, error) {
		for i := len(undo) - 1; i >= 0; i-- {
			_ = undo[i]()
		}
		// renice changes are snapshot-backed rather than individual undo closures.
		for key, old := range e.original {
			if _, existed := before[key]; !existed && strings.HasPrefix(key, "nice:") {
				_, _ = e.R.Run("renice", old, "-p", strings.TrimPrefix(key, "nice:"))
			}
		}
		for key := range e.original {
			if _, existed := before[key]; !existed {
				delete(e.original, key)
			}
		}
		add("rollback", "applied", "effect application failed")
		return r, err
	}
	// CPU governors.
	if ev.HandleCores != "false" {
		cores, err := e.cores(ev.HandleCores, false)
		if err != nil {
			return fail(err)
		}
		for _, c := range cores {
			p := filepath.Join("/sys/devices/system/cpu", c, "cpufreq/scaling_governor")
			old, er := e.R.Read(p)
			if er != nil {
				return fail(er)
			}
			e.remember("file:"+p, old)
			if er = e.R.Write(p, "powersave"); er != nil {
				return fail(er)
			}
			pp, oo := p, old
			undo = append(undo, func() error { return e.R.Write(pp, oo) })
		}
		add("handle_cores", "applied", strings.Join(cores, " "))
	} else {
		add("handle_cores", "disabled", "")
	}
	if ev.DisableCores != "false" {
		cores, err := e.cores(ev.DisableCores, true)
		if err != nil {
			return fail(err)
		}
		for _, c := range cores {
			if c == "cpu0" {
				continue
			}
			p := filepath.Join("/sys/devices/system/cpu", c, "online")
			old, er := e.R.Read(p)
			if er != nil {
				return fail(er)
			}
			e.remember("file:"+p, old)
			if er = e.R.Write(p, "0"); er != nil {
				return fail(er)
			}
			pp, oo := p, old
			undo = append(undo, func() error { return e.R.Write(pp, oo) })
		}
		add("disable_cores", "applied", strings.Join(cores, " "))
	} else {
		add("disable_cores", "disabled", "")
	}
	allow, err := readLines(ev.Allowlist)
	if err != nil {
		return fail(fmt.Errorf("allowlist: %w", err))
	}
	deny, err := readLines(ev.Denylist)
	if err != nil {
		return fail(fmt.Errorf("denylist: %w", err))
	}
	if ev.HandleApps == "suspend" && len(allow) == 0 {
		return fail(errors.New("handle_apps=suspend requires a non-empty allowlist"))
	}
	if ev.HandleApps != "false" {
		pkgs, er := e.packages()
		if er != nil {
			return fail(er)
		}
		targets := filterPackages(pkgs, allow, deny)
		for _, p := range targets {
			if er = e.appAction(ev.HandleApps, p); er != nil {
				return fail(er)
			}
			if ev.HandleApps == "suspend" {
				pkg := p
				undo = append(undo, func() error { _, x := e.R.Run("pm", "unsuspend", "--user", "0", pkg); return x })
			}
		}
		add("handle_apps", "applied", fmt.Sprintf("%d packages", len(targets)))
	} else {
		add("handle_apps", "disabled", "")
	}
	if ev.HandleGMS != "false" {
		if ev.HandleGMS == "kill" {
			disabled, _ := e.R.Run("pm", "list", "packages", "-d", "com.google.android.gms")
			e.remember("gms-enabled", strconv.FormatBool(!strings.Contains(disabled, "com.google.android.gms")))
			if _, err = e.R.Run("pm", "disable", "com.google.android.gms"); err != nil {
				return fail(err)
			}
			if _, err = e.R.Run("am", "force-stop", "com.google.android.gms"); err != nil {
				return fail(err)
			}
			undo = append(undo, func() error { _, x := e.R.Run("pm", "enable", "com.google.android.gms"); return x })
		} else if err := e.appAction(ev.HandleGMS, "com.google.android.gms"); err != nil {
			return fail(err)
		}
		add("handle_gms", "applied", ev.HandleGMS)
	} else {
		add("handle_gms", "disabled", "")
	}
	if ev.HandleProc {
		rules, er := readProc(ev.ProcFile)
		if er != nil {
			return fail(er)
		}
		for n, nice := range rules {
			out, x := e.R.Run("pidof", n)
			if x != nil {
				continue
			}
			for _, pid := range strings.Fields(out) {
				if x = e.renice(pid, nice); x != nil {
					return fail(x)
				}
			}
		}
		add("handle_proc", "applied", fmt.Sprintf("%d rules", len(rules)))
	} else {
		add("handle_proc", "disabled", "")
	}
	if ev.LowRAM {
		old, _ := e.R.Run("getprop", "ro.config.low_ram")
		if _, err = e.R.Run("resetprop", "-n", "ro.config.low_ram", "true"); err != nil {
			return fail(err)
		}
		o := strings.TrimSpace(old)
		e.remember("low_ram", o)
		undo = append(undo, func() error { _, x := e.R.Run("resetprop", "-n", "ro.config.low_ram", o); return x })
		add("low_ram", "applied", "ro.config.low_ram=true")
	} else {
		add("low_ram", "disabled", "")
	}
	if ev.Doze != "false" {
		args := []string{"deviceidle", "force-idle"}
		if ev.Doze == "light" {
			args = append(args, "light")
		}
		if _, err = e.R.Run("dumpsys", args...); err != nil {
			return fail(err)
		}
		undo = append(undo, func() error { _, x := e.R.Run("dumpsys", "deviceidle", "unforce"); return x })
		add("doze", "applied", ev.Doze)
	} else {
		add("doze", "disabled", "")
	}
	if ev.KillWiFi {
		out, _ := e.R.Run("cmd", "wifi", "status")
		wasOn := strings.Contains(strings.ToLower(out), "enabled")
		e.remember("wifi-enabled", strconv.FormatBool(wasOn))
		if _, err = e.R.Run("svc", "wifi", "disable"); err != nil {
			return fail(err)
		}
		if wasOn {
			undo = append(undo, func() error { _, x := e.R.Run("svc", "wifi", "enable"); return x })
		}
		add("kill_wifi", "applied", "")
	} else {
		add("kill_wifi", "disabled", "")
	}
	r.Applied = true
	return r, nil
}
func (e *Engine) cores(v string, high bool) ([]string, error) {
	all, err := e.R.Glob("/sys/devices/system/cpu/cpu[0-9]*")
	if err != nil {
		return nil, err
	}
	names := make([]string, 0, len(all))
	for _, p := range all {
		names = append(names, filepath.Base(p))
	}
	sort.Strings(names)
	if v == "auto" {
		type cf struct {
			name string
			freq int64
		}
		vals := []cf{}
		var max int64
		for _, n := range names {
			raw, x := e.R.Read(filepath.Join("/sys/devices/system/cpu", n, "cpufreq/cpuinfo_max_freq"))
			if x != nil {
				continue
			}
			f, x := strconv.ParseInt(strings.TrimSpace(raw), 10, 64)
			if x != nil {
				continue
			}
			vals = append(vals, cf{n, f})
			if f > max {
				max = f
			}
		}
		if len(vals) == 0 {
			return nil, errors.New("auto core mapping requires cpuinfo_max_freq")
		}
		out := []string{}
		if high {
			// Do not offline every CPU on homogeneous topologies.
			lower := false
			for _, x := range vals {
				if x.freq < max {
					lower = true
				}
			}
			if lower {
				for _, x := range vals {
					if x.freq == max && x.name != "cpu0" {
						out = append(out, x.name)
					}
				}
			}
		} else {
			for _, x := range vals {
				if x.freq < max || len(vals) == 1 {
					avail, er := e.R.Read(filepath.Join("/sys/devices/system/cpu", x.name, "cpufreq/scaling_available_governors"))
					if er == nil && strings.Contains(" "+avail+" ", " powersave ") {
						out = append(out, x.name)
					}
				}
			}
			if len(out) == 0 { // homogeneous topology: powersave-capable CPUs are safe governor targets.
				for _, x := range vals {
					avail, er := e.R.Read(filepath.Join("/sys/devices/system/cpu", x.name, "cpufreq/scaling_available_governors"))
					if er == nil && strings.Contains(" "+avail+" ", " powersave ") {
						out = append(out, x.name)
					}
				}
			}
		}
		return out, nil
	}
	want := strings.Fields(v)
	valid := map[string]bool{}
	for _, n := range names {
		valid[n] = true
	}
	for _, n := range want {
		if !valid[n] {
			return nil, fmt.Errorf("unknown cpu core %q", n)
		}
	}
	return want, nil
}
func (e *Engine) packages() ([]string, error) {
	out, err := e.R.Run("pm", "list", "packages", "-3")
	if err != nil {
		return nil, err
	}
	var r []string
	for _, l := range strings.Fields(out) {
		r = append(r, strings.TrimPrefix(l, "package:"))
	}
	return r, nil
}
func filterPackages(user, allow, deny []string) []string {
	a := map[string]bool{}
	for _, x := range allow {
		a[x] = true
	}
	var r []string
	for _, x := range user {
		if !a[x] {
			r = append(r, x)
		}
	}
	// denylist explicitly adds packages (including system packages) to handling.
	r = append(r, deny...)
	return unique(r)
}
func (e *Engine) appAction(mode, pkg string) error {
	switch mode {
	case "kill":
		_, err := e.R.Run("am", "force-stop", pkg)
		return err
	case "suspend":
		_, err := e.R.Run("pm", "suspend", "--user", "0", pkg)
		return err
	case "nice":
		out, err := e.R.Run("pidof", pkg)
		if err != nil {
			return nil
		}
		for _, pid := range strings.Fields(out) {
			if err = e.renice(pid, 19); err != nil {
				return err
			}
		}
		return nil
	}
	return fmt.Errorf("unsupported app action %q", mode)
}
func readLines(path string) ([]string, error) {
	if strings.TrimSpace(path) == "" {
		return nil, nil
	}
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	st, _ := f.Stat()
	if st != nil && st.Size() > maxRuleBytes {
		return nil, errors.New("rule file exceeds 1 MiB")
	}
	var r []string
	s := bufio.NewScanner(f)
	for s.Scan() {
		v := strings.TrimSpace(strings.SplitN(s.Text(), "#", 2)[0])
		if v != "" {
			r = append(r, v)
		}
	}
	return r, s.Err()
}
func readProc(path string) (map[string]int, error) {
	lines, err := readLines(path)
	if err != nil {
		return nil, fmt.Errorf("proc_file: %w", err)
	}
	r := map[string]int{}
	for i, l := range lines {
		p := strings.Fields(l)
		if len(p) != 2 {
			return nil, fmt.Errorf("proc_file line %d must be: process nice", i+1)
		}
		n, e := strconv.Atoi(p[1])
		if e != nil || n < -20 || n > 19 {
			return nil, fmt.Errorf("proc_file line %d has invalid nice", i+1)
		}
		r[p[0]] = n
	}
	return r, nil
}
func (e *Engine) Restore(ev Event) (Result, error) {
	r := Result{Event: ev.Name}
	add := func(f, s, d string) { r.Outcomes = append(r.Outcomes, Outcome{f, s, d}) }
	var errs []string
	fail := func(field string, err error) {
		if err != nil {
			errs = append(errs, field+": "+err.Error())
			add(field, "restore_failed", err.Error())
		} else {
			add(field, "restored", "")
		}
	}
	// Restore every sysfs value from the exact pre-apply snapshot.
	for key, old := range clone(e.original) {
		if strings.HasPrefix(key, "file:") {
			p := strings.TrimPrefix(key, "file:")
			err := e.R.Write(p, old)
			fail("cpu", err)
			if err == nil {
				delete(e.original, key)
			}
		}
	}
	if ev.Doze != "false" {
		_, err := e.R.Run("dumpsys", "deviceidle", "unforce")
		fail("doze", err)
	}
	if old, ok := e.original["wifi-enabled"]; ok {
		want, _ := strconv.ParseBool(old)
		var err error
		if want {
			_, err = e.R.Run("svc", "wifi", "enable")
		}
		fail("kill_wifi", err)
		if err == nil {
			delete(e.original, "wifi-enabled")
		}
	}
	if old, ok := e.original["low_ram"]; ok {
		_, err := e.R.Run("resetprop", "-n", "ro.config.low_ram", old)
		fail("low_ram", err)
		if err == nil {
			delete(e.original, "low_ram")
		}
	}
	if old, ok := e.original["gms-enabled"]; ok {
		wasEnabled, _ := strconv.ParseBool(old)
		var err error
		if wasEnabled {
			_, err = e.R.Run("pm", "enable", "com.google.android.gms")
		}
		fail("handle_gms", err)
		if err == nil {
			delete(e.original, "gms-enabled")
		}
	}
	if ev.HandleApps == "suspend" {
		user, _ := e.packages()
		allow, _ := readLines(ev.Allowlist)
		deny, _ := readLines(ev.Denylist)
		for _, pkg := range filterPackages(user, allow, deny) {
			_, err := e.R.Run("pm", "unsuspend", "--user", "0", pkg)
			fail("handle_apps", err)
		}
	}
	for key, old := range clone(e.original) {
		if strings.HasPrefix(key, "nice:") {
			pid := strings.TrimPrefix(key, "nice:")
			_, err := e.R.Run("renice", old, "-p", pid)
			fail("nice", err)
			if err == nil {
				delete(e.original, key)
			}
		}
	}
	if len(errs) > 0 {
		return r, errors.New(strings.Join(errs, "; "))
	}
	r.Applied = true
	return r, nil
}
func clone(in map[string]string) map[string]string {
	out := map[string]string{}
	for k, v := range in {
		out[k] = v
	}
	return out
}

// Maintain reapplies effects that are explicitly continuous in the compatibility contract.
func (e *Engine) Maintain(ev Event) error {
	if !ev.HandleProc {
		return nil
	}
	rules, err := readProc(ev.ProcFile)
	if err != nil {
		return err
	}
	for n, nice := range rules {
		out, x := e.R.Run("pidof", n)
		if x != nil {
			continue
		}
		for _, pid := range strings.Fields(out) {
			if x = e.renice(pid, nice); x != nil {
				return x
			}
		}
	}
	return nil
}

func MarshalResult(r Result) map[string]any {
	b, _ := json.Marshal(r)
	var m map[string]any
	_ = json.Unmarshal(b, &m)
	return m
}
