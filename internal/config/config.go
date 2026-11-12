package config

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
)

const ModuleID = "XtremeBS"
const ConfigVersion = 2

var EventKeys = []string{"boot", "charging", "screen_off", "low_power", "manual"}

var EssentialsAllowlist = "android\ncom.android.systemui\ncom.android.settings\ncom.android.launcher3\ncom.google.android.gms\ncom.android.vending\ncom.android.phone\ncom.google.android.providers.media.module\n"

type Paths struct {
	ModuleRoot   string `json:"module_root"`
	SharedRoot   string `json:"shared_root"`
	ExternalRoot string `json:"external_root"`
	Config       string `json:"config"`
	Status       string `json:"status"`
	Log          string `json:"log"`
	Essentials   string `json:"essentials"`
	Runtime      string `json:"runtime"`
	ListDir      string `json:"list_dir"`
	LogDir       string `json:"log_dir"`
}

func ResolvePaths() Paths {
	home, _ := os.UserHomeDir()
	if home == "" {
		home = "."
	}
	moduleRoot := os.Getenv("XBS_MODULE_DIR")
	if moduleRoot == "" {
		moduleRoot = "."
	}
	shared := sharedRoot(home)
	external := os.Getenv("XBS_EXTERNAL_ROOT")
	if external == "" {
		external = filepath.Join(shared, ".config", "xbs")
	}
	runtime := os.Getenv("XBS_RUNTIME_DIR")
	if runtime == "" {
		runtime = preferWritable(filepath.Join(string(filepath.Separator), "data", "local", "tmp", "XtremeBS"), filepath.Join(home, ".local", "state", "XtremeBS"), filepath.Join(external, ".runtime"))
	}
	listDir := os.Getenv("XBS_EXTERNAL_LIST_DIR")
	if listDir == "" {
		listDir = filepath.Join(external, "lists")
	}
	logDir := os.Getenv("XBS_EXTERNAL_LOG_DIR")
	if logDir == "" {
		logDir = filepath.Join(external, "logs")
	}
	cfg := os.Getenv("XBS_EXTERNAL_CONFIG")
	if cfg == "" {
		cfg = filepath.Join(external, "XtremeBS.json")
	}
	status := os.Getenv("XBS_EXTERNAL_STATUS")
	if status == "" {
		status = filepath.Join(logDir, "XtremeBS.status")
	}
	logFile := os.Getenv("XBS_EXTERNAL_LOG")
	if logFile == "" {
		logFile = filepath.Join(logDir, "XtremeBS.log")
	}
	essentials := os.Getenv("XBS_EXTERNAL_ESSENTIALS")
	if essentials == "" {
		essentials = filepath.Join(listDir, "essentials.allowlist")
	}
	return Paths{ModuleRoot: moduleRoot, SharedRoot: shared, ExternalRoot: external, Config: cfg, Status: status, Log: logFile, Essentials: essentials, Runtime: runtime, ListDir: listDir, LogDir: logDir}
}

func sharedRoot(home string) string {
	for _, key := range []string{"WG_ANDROID_SHARED_ROOT", "ANDROID_SHARED_ROOT", "EXTERNAL_STORAGE"} {
		if v := os.Getenv(key); v != "" {
			if isDir(v) {
				return v
			}
		}
	}
	for _, c := range []string{filepath.Join(home, "storage", "shared"), "/storage/emulated/0", "/sdcard"} {
		if isDir(c) {
			return c
		}
	}
	return filepath.Join(home, ".local", "share", "android-shared")
}

func isDir(path string) bool { info, err := os.Stat(path); return err == nil && info.IsDir() }

func preferWritable(candidates ...string) string {
	for _, c := range candidates {
		if c == "" {
			continue
		}
		if err := os.MkdirAll(c, 0o755); err != nil {
			continue
		}
		probe := filepath.Join(c, ".xbs-write-test")
		if err := os.WriteFile(probe, []byte("ok"), 0o600); err == nil {
			_ = os.Remove(probe)
			return c
		}
	}
	return candidates[len(candidates)-1]
}

func Default() map[string]any {
	p := ResolvePaths()
	ctl := os.Getenv("XBS_DEFAULT_CTL_FILE")
	if ctl == "" {
		ctl = filepath.Join(p.Runtime, "xbs.ctl")
	}
	lock := os.Getenv("XBS_DEFAULT_LOCK_FILE")
	if lock == "" {
		lock = filepath.Join(p.Runtime, "reload.lock")
	}
	events := map[string]any{"template": map[string]any{"allowlist": "", "denylist": "", "proc_file": ""}}
	for _, name := range EventKeys {
		events[name] = map[string]any{}
	}
	return map[string]any{
		"header": map[string]any{
			"version":                     ConfigVersion,
			"delay":                       3,
			"log_file":                    p.Log,
			"log_level":                   3,
			"log_json":                    false,
			"dry_run":                     false,
			"automatic_events_enabled":    false,
			"low_power_threshold":         20,
			"low_power_release_threshold": 25,
			"lock_file":                   lock,
			"ctl_file":                    ctl,
			"essentials_allowlist":        p.Essentials,
			"notify":                      true,
		},
		"events": events,
	}
}

func EnsureDirs() error {
	p := ResolvePaths()
	for _, dir := range []string{p.ExternalRoot, p.ListDir, p.LogDir, p.Runtime} {
		if err := safeMkdirAll(dir); err != nil {
			return err
		}
	}
	if _, err := os.Lstat(p.Essentials); errors.Is(err, os.ErrNotExist) {
		if err := atomicWriteFile(p.Essentials, []byte(EssentialsAllowlist), 0o644); err != nil {
			return err
		}
	} else if err != nil {
		return err
	}
	return nil
}

func safeMkdirAll(path string) error {
	clean := filepath.Clean(path)
	cur := ""
	if filepath.IsAbs(clean) {
		cur = string(filepath.Separator)
	}
	for _, part := range strings.Split(clean, string(filepath.Separator)) {
		if part == "" || part == "." {
			continue
		}
		cur = filepath.Join(cur, part)
		if info, err := os.Lstat(cur); err == nil {
			if info.Mode()&os.ModeSymlink != 0 {
				return fmt.Errorf("unsafe symlink in path: %s", cur)
			}
		} else if !errors.Is(err, os.ErrNotExist) {
			return err
		}
	}
	return os.MkdirAll(clean, 0o755)
}

func Load() (map[string]any, error) {
	if err := EnsureDirs(); err != nil {
		return nil, err
	}
	cfg := Default()
	p := ResolvePaths()
	data, err := os.ReadFile(p.Config)
	if errors.Is(err, os.ErrNotExist) {
		return cfg, nil
	}
	if err != nil {
		return nil, err
	}
	if len(strings.TrimSpace(string(data))) == 0 {
		return nil, fmt.Errorf("config is empty: %s", p.Config)
	}
	var raw map[string]any
	if err := json.Unmarshal(data, &raw); err != nil {
		return nil, fmt.Errorf("config is invalid JSON: %w", err)
	}
	deepUpdate(cfg, raw)
	if errs := Validate(cfg); len(errs) > 0 {
		return nil, fmt.Errorf("config validation failed: %s", strings.Join(errs, "; "))
	}
	return cfg, nil
}

func Save(cfg map[string]any) (map[string]any, error) {
	if errs := Validate(cfg); len(errs) > 0 {
		return nil, fmt.Errorf(strings.Join(errs, "; "))
	}
	if err := EnsureDirs(); err != nil {
		return nil, err
	}
	data, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return nil, err
	}
	data = append(data, '\n')
	if err := atomicWriteFile(ResolvePaths().Config, data, 0o644); err != nil {
		return nil, err
	}
	return Load()
}

func atomicWriteFile(path string, data []byte, mode os.FileMode) error {
	if err := safeMkdirAll(filepath.Dir(path)); err != nil {
		return err
	}
	if info, err := os.Lstat(path); err == nil && info.Mode()&os.ModeSymlink != 0 {
		return fmt.Errorf("refusing to replace symlink: %s", path)
	}
	tmp, err := os.CreateTemp(filepath.Dir(path), "."+filepath.Base(path)+"-*.tmp")
	if err != nil {
		return err
	}
	tmpName := tmp.Name()
	ok := false
	defer func() {
		if !ok {
			_ = os.Remove(tmpName)
		}
	}()
	if _, err := tmp.Write(data); err != nil {
		_ = tmp.Close()
		return err
	}
	if err := tmp.Chmod(mode); err != nil {
		_ = tmp.Close()
		return err
	}
	if err := tmp.Sync(); err != nil {
		_ = tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	if err := os.Rename(tmpName, path); err != nil {
		return err
	}
	if dir, err := os.Open(filepath.Dir(path)); err == nil {
		_ = dir.Sync()
		_ = dir.Close()
	}
	ok = true
	return nil
}

func deepUpdate(base, patch map[string]any) {
	for k, v := range patch {
		if bv, ok := base[k].(map[string]any); ok {
			if pv, ok := v.(map[string]any); ok {
				deepUpdate(bv, pv)
				continue
			}
		}
		base[k] = v
	}
}

func Validate(cfg map[string]any) []string {
	errs := []string{}
	if cfg == nil {
		return []string{"config must be an object"}
	}
	header, ok := cfg["header"].(map[string]any)
	if !ok {
		errs = append(errs, "header must be an object")
	} else {
		if iv, ok := intValue(header["version"]); !ok || iv != ConfigVersion {
			errs = append(errs, "header.version must be 2")
		}
		for _, key := range []string{"delay", "log_level"} {
			if _, ok := intValue(header[key]); !ok {
				errs = append(errs, "header."+key+" must be an integer")
			}
		}
		if delay, ok := intValue(header["delay"]); !ok || delay < 0 || delay > 300 {
			errs = append(errs, "header.delay must be between 0 and 300 seconds")
		}
		if enabled, exists := header["automatic_events_enabled"]; exists {
			if _, valid := enabled.(bool); !valid {
				errs = append(errs, "header.automatic_events_enabled must be a boolean")
			}
		}
		low, lowOK := intValue(header["low_power_threshold"])
		if !lowOK && header["low_power_threshold"] == nil {
			low = 20
			lowOK = true
		}
		release, releaseOK := intValue(header["low_power_release_threshold"])
		if !releaseOK && header["low_power_release_threshold"] == nil {
			release = 25
			releaseOK = true
		}
		if !lowOK || !releaseOK || low < 1 || low > 95 || release <= low || release > 100 {
			errs = append(errs, "header low-power hysteresis thresholds are invalid")
		}
		for _, key := range []string{"log_file", "ctl_file", "lock_file", "essentials_allowlist"} {
			v, exists := header[key]
			s, isString := v.(string)
			if !exists || !isString {
				errs = append(errs, "header."+key+" must be a string")
				continue
			}
			if strings.TrimSpace(s) == "" {
				errs = append(errs, "header."+key+" is required")
				continue
			}
			if strings.ContainsRune(s, '\x00') {
				errs = append(errs, "header."+key+" contains NUL")
			}
		}
	}
	events, ok := cfg["events"].(map[string]any)
	if !ok {
		errs = append(errs, "events must be an object")
	} else {
		if t, exists := events["template"]; exists {
			if _, ok := t.(map[string]any); !ok {
				errs = append(errs, "events.template must be an object")
			}
		}
		names := make([]string, 0, len(events))
		for name := range events {
			names = append(names, name)
		}
		sort.Strings(names)
		for _, name := range names {
			if name != "template" && !ValidEventName(name) {
				errs = append(errs, "events."+name+" has invalid event name")
				continue
			}
			event, ok := events[name].(map[string]any)
			if !ok {
				errs = append(errs, "events."+name+" must be an object")
				continue
			}
			for _, field := range []string{"allowlist", "denylist", "proc_file"} {
				if v, exists := event[field]; exists && v != nil {
					if _, ok := v.(string); !ok {
						errs = append(errs, "events."+name+"."+field+" must be a string")
					}
				}
			}
			for _, field := range []string{"keep_on_charge", "quit", "handle_proc", "low_ram", "kill_wifi"} {
				if v, exists := event[field]; exists && v != nil && !boolLike(v) {
					errs = append(errs, "events."+name+"."+field+" must be true or false")
				}
			}
			choices := map[string]map[string]bool{"handle_apps": {"false": true, "nice": true, "kill": true, "suspend": true}, "handle_gms": {"false": true, "nice": true, "kill": true}, "doze": {"false": true, "light": true, "deep": true}}
			for field, allowed := range choices {
				if v, exists := event[field]; exists && v != nil {
					if !allowed[strings.ToLower(fmt.Sprint(v))] {
						vals := make([]string, 0, len(allowed))
						for k := range allowed {
							vals = append(vals, k)
						}
						sort.Strings(vals)
						errs = append(errs, "events."+name+"."+field+" must be one of: "+strings.Join(vals, ", "))
					}
				}
			}
			for _, field := range []string{"handle_cores", "disable_cores"} {
				if v, exists := event[field]; exists && v != nil && strings.TrimSpace(fmt.Sprint(v)) == "" {
					errs = append(errs, "events."+name+"."+field+" cannot be empty")
				}
			}
		}
	}
	return errs
}

func intValue(v any) (int, bool) {
	switch x := v.(type) {
	case int:
		return x, true
	case int64:
		return int(x), true
	case float64:
		if x == float64(int(x)) {
			return int(x), true
		}
		return 0, false
	case json.Number:
		i, err := strconv.Atoi(string(x))
		return i, err == nil
	case string:
		i, err := strconv.Atoi(strings.TrimSpace(x))
		return i, err == nil
	default:
		return 0, false
	}
}
func boolLike(v any) bool {
	switch x := v.(type) {
	case bool:
		return true
	case string:
		s := strings.ToLower(strings.TrimSpace(x))
		return s == "true" || s == "false"
	default:
		return false
	}
}

func GetByKey(cfg map[string]any, key string) (any, bool) {
	if key == "" {
		return cfg, true
	}
	var cur any = cfg
	for _, part := range strings.Split(key, ".") {
		obj, ok := cur.(map[string]any)
		if !ok {
			return nil, false
		}
		cur, ok = obj[part]
		if !ok {
			return nil, false
		}
	}
	return cur, true
}

func SetByKey(cfg map[string]any, key string, value string) error {
	parts := strings.Split(key, ".")
	if len(parts) == 0 || strings.TrimSpace(key) == "" {
		return fmt.Errorf("config key is required")
	}
	cur := cfg
	for _, part := range parts[:len(parts)-1] {
		next, ok := cur[part].(map[string]any)
		if !ok {
			next = map[string]any{}
			cur[part] = next
		}
		cur = next
	}
	cur[parts[len(parts)-1]] = coerce(value)
	return nil
}

func coerce(value string) any {
	low := strings.ToLower(strings.TrimSpace(value))
	if low == "true" {
		return true
	}
	if low == "false" {
		return false
	}
	if i, err := strconv.Atoi(value); err == nil {
		return i
	}
	return value
}

func StatusFallback() map[string]any {
	p := ResolvePaths()
	data, err := os.ReadFile(p.Status)
	if err != nil {
		return map[string]any{"source": "status_file", "raw": nil, "ok": false}
	}
	var raw any
	if err := json.Unmarshal(data, &raw); err != nil {
		raw = string(data)
	}
	return map[string]any{"source": "status_file", "raw": raw, "ok": true}
}

func PathPayload() map[string]any {
	p := ResolvePaths()
	return map[string]any{"module_root": p.ModuleRoot, "shared_root": p.SharedRoot, "config": p.Config, "status": p.Status, "log": p.Log, "essentials": p.Essentials, "runtime": p.Runtime}
}

func ValidEventName(name string) bool {
	if name == "" || len(name) > 64 {
		return false
	}
	for i, r := range name {
		if !((r >= 'a' && r <= 'z') || (r >= '0' && r <= '9') || (i > 0 && (r == '_' || r == '-'))) {
			return false
		}
	}
	return true
}
