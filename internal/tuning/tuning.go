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

	"github.com/Mikeyphw/xbsctl/internal/config"
)

const maxRuleBytes = 1 << 20

type Event struct {
	Name                                             string `json:"name"`
	Allowlist, Denylist, ProcFile                    string
	EssentialsPath                                   string
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
	// journal commits the next recovery snapshot before the corresponding root write.
	// A failed commit prevents the write; it never silently degrades to RAM-only.
	journal func(map[string]string) error
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
	e.EssentialsPath = config.ResolvePaths().Essentials
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

// SetJournal enables write-ahead persistence for every newly owned reversible effect.
// The callback must durably commit the supplied snapshot or return an error.
func (e *Engine) SetJournal(fn func(map[string]string) error) { e.journal = fn }

func (e *Engine) remember(key, value string) error {
	if _, ok := e.original[key]; ok {
		return nil
	}
	next := clone(e.original)
	next[key] = value
	if e.journal != nil {
		if err := e.journal(clone(next)); err != nil {
			return fmt.Errorf("write-ahead journal %s: %w", key, err)
		}
	}
	e.original = next
	return nil
}

// release is deliberately ordered: commit removal before forgetting the record
// in memory. A failed commit retains the record for an idempotent retry.
func (e *Engine) release(key string) error {
	if _, ok := e.original[key]; !ok {
		return nil
	}
	next := clone(e.original)
	delete(next, key)
	if e.journal != nil {
		if err := e.journal(clone(next)); err != nil {
			return fmt.Errorf("journal release %s: %w", key, err)
		}
	}
	e.original = next
	return nil
}

func (e *Engine) restoreRemembered(key string, restore func(string) error) error {
	v, ok := e.original[key]
	if !ok {
		return nil
	}
	if err := restore(v); err != nil {
		return err
	}
	return e.release(key)
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
		if err := e.remember(key, old); err != nil {
			return err
		}
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
	// Undo entries are keyed to the exact original-state record. A failed
	// rollback must retain its record for a later explicit restoration attempt.
	type undoStep struct {
		key string
		fn  func() error
	}
	undo := []undoStep{}
	deferUndo := func(key string, fn func() error) {
		undo = append(undo, undoStep{key: key, fn: fn})
	}
	before := clone(e.original)
	fail := func(applyErr error) (Result, error) {
		var rollbackErrors []string
		for i := len(undo) - 1; i >= 0; i-- {
			step := undo[i]
			if err := step.fn(); err != nil {
				rollbackErrors = append(rollbackErrors, fmt.Sprintf("%s: %v", step.key, err))
				continue
			}
			if step.key != "" {
				if _, existed := before[step.key]; !existed {
					if journalErr := e.release(step.key); journalErr != nil {
						rollbackErrors = append(rollbackErrors, journalErr.Error())
					}
				}
			}
		}
		// A nice value is independently snapshot-backed; never delete it if
		// renice fails. Restore will retry the preserved record later.
		for key, old := range clone(e.original) {
			if _, existed := before[key]; existed || !strings.HasPrefix(key, "nice:") {
				continue
			}
			if _, err := e.R.Run("renice", old, "-p", strings.TrimPrefix(key, "nice:")); err != nil {
				rollbackErrors = append(rollbackErrors, fmt.Sprintf("%s: %v", key, err))
			} else if journalErr := e.release(key); journalErr != nil {
				rollbackErrors = append(rollbackErrors, journalErr.Error())
			}
		}
		var pending []string
		for key := range e.original {
			if _, existed := before[key]; !existed {
				pending = append(pending, key)
			}
		}
		sort.Strings(pending)
		if len(rollbackErrors) > 0 || len(pending) > 0 {
			add("rollback", "failed", fmt.Sprintf("errors=%d pending_records=%d", len(rollbackErrors), len(pending)))
			return r, fmt.Errorf("apply failed: %w; rollback incomplete (errors=%v, pending=%v)", applyErr, rollbackErrors, pending)
		}
		add("rollback", "applied", "all registered undo steps succeeded")
		return r, applyErr
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
			if er = e.remember("file:"+p, old); er != nil {
				return fail(er)
			}
			pp, oo := p, old
			deferUndo("file:"+pp, func() error { return e.R.Write(pp, oo) })
			if er = e.R.Write(p, "powersave"); er != nil {
				return fail(er)
			}
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
			if er = e.remember("file:"+p, old); er != nil {
				return fail(er)
			}
			pp, oo := p, old
			deferUndo("file:"+pp, func() error { return e.R.Write(pp, oo) })
			if er = e.R.Write(p, "0"); er != nil {
				return fail(er)
			}
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
		protected, er := protectedPackages(ev.EssentialsPath)
		if er != nil {
			return fail(fmt.Errorf("essentials protection: %w", er))
		}
		// Refuse the complete selection before acting on any package.
		for _, pkg := range targets {
			if protected[pkg] {
				return fail(fmt.Errorf("protected essential package: %s", pkg))
			}
		}
		for _, pkg := range targets {
			if ev.HandleApps == "suspend" {
				er = e.suspendPackage(pkg, deferUndo)
			} else {
				er = e.appAction(ev.HandleApps, pkg)
			}
			if er != nil {
				return fail(er)
			}
		}
		add("handle_apps", "applied", fmt.Sprintf("%d packages selected", len(targets)))
	} else {
		add("handle_apps", "disabled", "")
	}
	if ev.HandleGMS != "false" {
		if ev.HandleGMS == "kill" {
			disabled, probeErr := e.R.Run("pm", "list", "packages", "-d", "com.google.android.gms")
			if probeErr != nil {
				return fail(fmt.Errorf("cannot determine prior GMS enabled state: %w", probeErr))
			}
			wasEnabled := !strings.Contains(disabled, "com.google.android.gms")
			// A pre-disabled package must never be enabled by rollback or restore.
			if wasEnabled {
				if err = e.remember("gms-enabled", "true"); err != nil {
					return fail(err)
				}
				deferUndo("gms-enabled", func() error { _, x := e.R.Run("pm", "enable", "com.google.android.gms"); return x })
			}
			if _, err = e.R.Run("pm", "disable", "com.google.android.gms"); err != nil {
				return fail(err)
			}
			if _, err = e.R.Run("am", "force-stop", "com.google.android.gms"); err != nil {
				return fail(err)
			}
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
		old, probeErr := e.R.Run("getprop", "ro.config.low_ram")
		if probeErr != nil {
			return fail(fmt.Errorf("cannot determine prior low_ram property: %w", probeErr))
		}
		o := strings.TrimSpace(old)
		if o != "true" && o != "false" {
			return fail(fmt.Errorf("prior ro.config.low_ram is not a boolean: %q", o))
		}
		if err = e.remember("low_ram", o); err != nil {
			return fail(err)
		}
		deferUndo("low_ram", func() error { _, x := e.R.Run("resetprop", "-n", "ro.config.low_ram", o); return x })
		if _, err = e.R.Run("resetprop", "-n", "ro.config.low_ram", "true"); err != nil {
			return fail(err)
		}
		add("low_ram", "applied", "ro.config.low_ram=true")
	} else {
		add("low_ram", "disabled", "")
	}
	if ev.Doze != "false" {
		args := []string{"deviceidle", "force-idle"}
		if ev.Doze == "light" {
			args = append(args, "light")
		}
		// This is an XBS-owned force-idle action: record the inverse before
		// invoking Android, including commands that partially succeed then fail.
		if err = e.remember("doze:owned", "unforce"); err != nil {
			return fail(err)
		}
		deferUndo("doze:owned", func() error { _, x := e.R.Run("dumpsys", "deviceidle", "unforce"); return x })
		if _, err = e.R.Run("dumpsys", args...); err != nil {
			return fail(err)
		}
		add("doze", "applied", ev.Doze)
	} else {
		add("doze", "disabled", "")
	}
	if ev.KillWiFi {
		out, probeErr := e.R.Run("cmd", "wifi", "status")
		if probeErr != nil {
			return fail(fmt.Errorf("cannot determine prior Wi-Fi state: %w", probeErr))
		}
		status := strings.ToLower(strings.TrimSpace(out))
		wasOn := strings.Contains(status, "enabled")
		if !wasOn && !strings.Contains(status, "disabled") {
			return fail(fmt.Errorf("cannot determine prior Wi-Fi state from status output: %q", strings.TrimSpace(out)))
		}
		if wasOn {
			if err = e.remember("wifi-enabled", "true"); err != nil {
				return fail(err)
			}
			deferUndo("wifi-enabled", func() error { _, x := e.R.Run("svc", "wifi", "enable"); return x })
		}
		if _, err = e.R.Run("svc", "wifi", "disable"); err != nil {
			return fail(err)
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

// protectedPackages combines non-overridable built-in Android essentials with
// the configured global essentials file (including user-selected protections).
// The file is mandatory for config-resolved events: unreadable policy is unsafe.
func protectedPackages(essentialsPath string) (map[string]bool, error) {
	protected := map[string]bool{}
	for _, name := range strings.Fields(config.EssentialsAllowlist) {
		protected[name] = true
	}
	if essentialsPath != "" {
		additional, err := readLines(essentialsPath)
		if err != nil {
			return nil, err
		}
		for _, name := range additional {
			protected[name] = true
		}
	}
	return protected, nil
}

// packageSuspended requires an unambiguous Android user-0 state; never infer
// unsuspended merely from a missing, changed, or failed diagnostic command.
func (e *Engine) packageSuspended(pkg string) (bool, error) {
	output, err := e.R.Run("dumpsys", "package", pkg)
	if err != nil {
		return false, fmt.Errorf("inspect suspension of %s: %w", pkg, err)
	}
	found := false
	wasSuspended := false
	for _, line := range strings.Split(output, "\n") {
		line = strings.TrimSpace(line)
		if !strings.HasPrefix(line, "User 0:") {
			continue
		}
		for _, word := range strings.Fields(line) {
			if !strings.HasPrefix(word, "suspended=") {
				continue
			}
			if found {
				return false, fmt.Errorf("ambiguous suspension state for %s", pkg)
			}
			found = true
			switch word {
			case "suspended=true":
				wasSuspended = true
			case "suspended=false":
				wasSuspended = false
			default:
				return false, fmt.Errorf("unrecognized suspension state for %s: %q", pkg, word)
			}
		}
	}
	if !found {
		return false, fmt.Errorf("missing user-0 suspension state for %s", pkg)
	}
	return wasSuspended, nil
}

func (e *Engine) suspendPackage(pkg string, deferUndo func(string, func() error)) error {
	key := "suspend:user0:" + pkg
	if _, managed := e.original[key]; managed {
		// XBS already owns this transition from the original unsuspended state.
		return nil
	}
	wasSuspended, err := e.packageSuspended(pkg)
	if err != nil {
		return err
	}
	if wasSuspended {
		// Not ours to modify or to undo.
		return nil
	}
	if err := e.remember(key, "false"); err != nil {
		return err
	}
	deferUndo(key, func() error { _, restoreErr := e.R.Run("pm", "unsuspend", "--user", "0", pkg); return restoreErr })
	return e.appAction("suspend", pkg)
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
				if journalErr := e.release(key); journalErr != nil {
					fail("cpu_journal", journalErr)
				}
			}
		}
	}
	// Never unforce an idle state unless XBS recorded ownership of the
	// force-idle operation. Older unjournaled Doze effects require a
	// deliberate migration decision; do not guess during recovery.
	if _, owned := e.original["doze:owned"]; owned {
		_, err := e.R.Run("dumpsys", "deviceidle", "unforce")
		fail("doze", err)
		if err == nil {
			if journalErr := e.release("doze:owned"); journalErr != nil {
				fail("doze_journal", journalErr)
			}
		}
	}
	if old, ok := e.original["wifi-enabled"]; ok {
		want, _ := strconv.ParseBool(old)
		var err error
		if want {
			_, err = e.R.Run("svc", "wifi", "enable")
		}
		fail("kill_wifi", err)
		if err == nil {
			if journalErr := e.release("wifi-enabled"); journalErr != nil {
				fail("kill_wifi_journal", journalErr)
			}
		}
	}
	if old, ok := e.original["low_ram"]; ok {
		_, err := e.R.Run("resetprop", "-n", "ro.config.low_ram", old)
		fail("low_ram", err)
		if err == nil {
			if journalErr := e.release("low_ram"); journalErr != nil {
				fail("low_ram_journal", journalErr)
			}
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
			if journalErr := e.release("gms-enabled"); journalErr != nil {
				fail("handle_gms_journal", journalErr)
			}
		}
	}
	// Restore only exact packages that XBS recorded as originally unsuspended.
	// Never re-enumerate or unsuspend newly matched or pre-suspended packages.
	for key, old := range clone(e.original) {
		if !strings.HasPrefix(key, "suspend:user0:") {
			continue
		}
		if old != "false" {
			fail("handle_apps", fmt.Errorf("invalid suspension recovery state for %s", key))
			continue
		}
		pkg := strings.TrimPrefix(key, "suspend:user0:")
		if pkg == "" {
			fail("handle_apps", errors.New("empty package in suspension snapshot"))
			continue
		}
		_, err := e.R.Run("pm", "unsuspend", "--user", "0", pkg)
		fail("handle_apps", err)
		if err == nil {
			if journalErr := e.release(key); journalErr != nil {
				fail("suspend_journal", journalErr)
			}
		}
	}
	for key, old := range clone(e.original) {
		if strings.HasPrefix(key, "nice:") {
			pid := strings.TrimPrefix(key, "nice:")
			_, err := e.R.Run("renice", old, "-p", pid)
			fail("nice", err)
			if err == nil {
				if journalErr := e.release(key); journalErr != nil {
					fail("nice_journal", journalErr)
				}
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
