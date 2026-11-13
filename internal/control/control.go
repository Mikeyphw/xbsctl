package control

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/Mikeyphw/xbsctl/internal/config"
)

const (
	ExitOK            = 0
	ExitMissingConfig = 2
	ExitParseError    = 3
	ExitInvalidEvent  = 4
	ExitInvalidArgs   = 5
	ExitRuntime       = 6
)

type options struct {
	jsonMode bool
	wait     bool
	timeout  time.Duration
	ttl      time.Duration
	expectConfigSHA string
}

type StatusSnapshot struct {
	Available      bool           `json:"available"`
	Version        any            `json:"version,omitempty"`
	LogLevel       any            `json:"log_level,omitempty"`
	Mode           string         `json:"mode"`
	LastReloadTime string         `json:"last_reload_time,omitempty"`
	ConfigSHA256   string         `json:"config_sha256,omitempty"`
	ActiveEvents   []string       `json:"active_events"`
	Detail         map[string]any `json:"detail,omitempty"`
	Raw            map[string]any `json:"raw,omitempty"`
}

func Main(args []string, stdout io.Writer, stderr io.Writer) int {
	opts, clean, err := parse(args)
	if err != nil {
		return emitError(stdout, opts, "Unknown argument: "+err.Error(), ExitInvalidArgs)
	}
	if len(clean) == 0 || clean[0] == "-h" || clean[0] == "--help" || clean[0] == "help" {
		printHelp(stdout)
		return 0
	}
	if opts.expectConfigSHA != "" && (!opts.wait || len(clean) != 1 || clean[0] != "reload") {
		return emitError(stdout, opts, "--expect-config-sha256 requires reload --wait without additional arguments", ExitInvalidArgs)
	}
	switch clean[0] {
	case "active":
		snap, code, msg := readSnapshot()
		if code != 0 {
			return emitError(stdout, opts, msg, code)
		}
		return emit(stdout, opts, "ok", "Active events", strings.Join(snap.ActiveEvents, " "), false)
	case "detail":
		snap, code, msg := readSnapshot()
		if code != 0 {
			return emitError(stdout, opts, msg, code)
		}
		return emit(stdout, opts, "ok", "detail", snap.Detail, true)
	case "status":
		snap, code, msg := readSnapshot()
		if code != 0 {
			return emitError(stdout, opts, msg, code)
		}
		message := fmt.Sprintf("version=%v log_level=%v mode=%s last_reload=%s active=%s", snap.Version, snap.LogLevel, snap.Mode, snap.LastReloadTime, strings.Join(snap.ActiveEvents, " "))
		return emit(stdout, opts, "ok", message, nil, false)
	case "validate":
		cfg, code, msg := loadConfigRequireExists()
		if code != 0 {
			return emitError(stdout, opts, msg, code)
		}
		if errs := config.Validate(cfg); len(errs) > 0 {
			return emitError(stdout, opts, "Validation failed: "+strings.Join(errs, "; "), ExitRuntime)
		}
		return emit(stdout, opts, "ok", "Validation OK", nil, false)
	case "start", "stop":
		return queueEvent(stdout, opts, clean)
	case "cancel":
		if len(clean) != 2 {
			return emitError(stdout, opts, "cancel requires one request ID", ExitInvalidArgs)
		}
		cfg, code, msg := loadConfigRequireExists()
		if code != 0 {
			return emitError(stdout, opts, msg, code)
		}
		ctl, err := ctlPath(cfg)
		if err != nil {
			return emitError(stdout, opts, err.Error(), ExitParseError)
		}
		if err = CancelQueuedRequest(ctl, clean[1]); err != nil {
			return emitError(stdout, opts, "Cannot cancel claimed or unsafe request: "+err.Error(), ExitRuntime)
		}
		return emitControl(stdout, opts, "ok", "Cancelled before claim", "cancelled", "cancel", "", 0)
	case "recovery":
		if len(clean) != 1 {
			return emitError(stdout, opts, "recovery is read-only; no arguments allowed", ExitInvalidArgs)
		}
		cfg, code, msg := loadConfigRequireExists()
		if code != 0 {
			return emitError(stdout, opts, msg, code)
		}
		ctl, err := ctlPath(cfg)
		if err != nil {
			return emitError(stdout, opts, err.Error(), ExitParseError)
		}
		report, err := InspectRecovery(ctl, config.ResolvePaths().Runtime)
		if err != nil {
			return emitError(stdout, opts, err.Error(), ExitRuntime)
		}
		return emit(stdout, opts, "ok", "Read-only recovery inspection", report, true)
	case "reload", "pause", "resume", "safe":
		return queueMode(stdout, opts, clean)
	default:
		return emitError(stdout, opts, "Unknown command: "+clean[0], ExitInvalidArgs)
	}
}

func parse(args []string) (options, []string, error) {
	opts := options{timeout: 8 * time.Second}
	clean := make([]string, 0, len(args))
	for i := 0; i < len(args); i++ {
		a := args[i]
		switch a {
		case "--json":
			opts.jsonMode = true
		case "--expect-config-sha256":
			if i+1 >= len(args) { return opts, clean, errors.New("--expect-config-sha256 requires 64 lowercase hex characters") }
			i++
			if !r05bValidGeneration(args[i]) { return opts, clean, errors.New("invalid --expect-config-sha256") }
			opts.expectConfigSHA = args[i]
		case "--wait":
			opts.wait = true
		case "--ttl":
			if i+1 >= len(args) {
				return opts, clean, errors.New("--ttl requires seconds")
			}
			i++
			sec, err := strconv.Atoi(args[i])
			if err != nil || sec < 1 || sec > 3600 {
				return opts, clean, errors.New("invalid --ttl (1-3600 seconds)")
			}
			opts.ttl = time.Duration(sec) * time.Second
		case "--timeout":
			if i+1 >= len(args) {
				return opts, clean, errors.New("--timeout")
			}
			i++
			sec, err := strconv.Atoi(args[i])
			if err != nil || sec < 0 {
				return opts, clean, errors.New("invalid --timeout")
			}
			opts.timeout = time.Duration(sec) * time.Second
		default:
			clean = append(clean, a)
		}
	}
	return opts, clean, nil
}

func queueEvent(stdout io.Writer, opts options, clean []string) int {
	cfg, code, msg := loadConfigRequireExists()
	if code != 0 {
		return emitError(stdout, opts, msg, code)
	}
	action := clean[0]
	event := "manual"
	if len(clean) > 1 && !strings.HasPrefix(clean[1], "--") {
		event = clean[1]
	}
	if !eventExists(cfg, event) {
		return emitError(stdout, opts, "Unknown event: "+event, ExitInvalidEvent)
	}
	ctl, err := ctlPath(cfg)
	if err != nil {
		return emitError(stdout, opts, err.Error(), ExitParseError)
	}
	before, _, _ := readSnapshot()
	req, err := QueueRequestWithDeadline(ctl, action, event, opts.ttl)
	if err != nil {
		return emitError(stdout, opts, err.Error(), ExitRuntime)
	}
	if opts.wait {
		if ack, found := waitForRequest(ctl, req.ID, opts.timeout); found {
			if ack.State == "applied" {
				if !waitForObservedState(action, event, before, opts.timeout) {
					return emitControl(stdout, opts, "unknown", "Daemon acknowledged but matching runtime state was not observed", "unknown", action, event, ExitRuntime, req.ID)
				}
				return emitControl(stdout, opts, "ok", "Applied: "+action+" "+event, "applied", action, event, 0, req.ID)
			}
			if ack.State == "unknown" {
				return emitControl(stdout, opts, "unknown", "Outcome unknown: "+ack.Error, "unknown", action, event, ExitRuntime, req.ID)
			}
			return emitControl(stdout, opts, "failed", "Daemon refused: "+ack.Error, "failed", action, event, ExitRuntime, req.ID)
		}
		return emitControl(stdout, opts, "timeout", "Timed out waiting for "+action+" "+event, "timeout", action, event, ExitRuntime, req.ID)
	}
	return emitControl(stdout, opts, "pending", "Queued: "+action+" "+event, "queued", action, event, 0, req.ID)
}

func queueMode(stdout io.Writer, opts options, clean []string) int {
	cfg, code, msg := loadConfigRequireExists()
	if code != 0 {
		return emitError(stdout, opts, msg, code)
	}
	action := clean[0]
	if len(clean) > 1 {
		return emitError(stdout, opts, "Command does not take arguments", ExitInvalidArgs)
	}
	ctl, err := ctlPath(cfg)
	if err != nil {
		return emitError(stdout, opts, err.Error(), ExitParseError)
	}
	before, _, _ := readSnapshot()
	req, err := QueueRequestWithDeadline(ctl, action, "", opts.ttl)
	if err != nil {
		return emitError(stdout, opts, err.Error(), ExitRuntime)
	}
	if opts.wait {
		if ack, found := waitForRequest(ctl, req.ID, opts.timeout); found {
			if ack.State == "applied" {
				if !waitForObservedState(action, "", before, opts.timeout) {
					return emitControl(stdout, opts, "unknown", "Daemon acknowledged but matching runtime state was not observed", "unknown", action, "", ExitRuntime, req.ID)
				}
				if opts.expectConfigSHA != "" {
					observed, code, _ := readSnapshot()
					if code != 0 || !r05bMatchingGeneration(opts.expectConfigSHA, observed.ConfigSHA256) {
						return emitControl(stdout, opts, "unknown", "Reload generation does not match the validated loaded configuration", "unknown", action, "", ExitRuntime, req.ID)
					}
				}
				return emitControl(stdout, opts, "ok", "Applied: "+action, "applied", action, "", 0, req.ID)
			}
			if ack.State == "unknown" {
				return emitControl(stdout, opts, "unknown", "Outcome unknown: "+ack.Error, "unknown", action, "", ExitRuntime, req.ID)
			}
			return emitControl(stdout, opts, "failed", "Daemon refused: "+ack.Error, "failed", action, "", ExitRuntime, req.ID)
		}
		return emitControl(stdout, opts, "timeout", "Timed out waiting for "+action, "timeout", action, "", ExitRuntime, req.ID)
	}
	return emitControl(stdout, opts, "pending", "Queued: "+action, "queued", action, "", 0, req.ID)
}

func loadConfigRequireExists() (map[string]any, int, string) {
	p := config.ResolvePaths()
	if _, err := os.Stat(p.Config); err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil, ExitMissingConfig, "Config not found: " + p.Config
		}
		return nil, ExitParseError, err.Error()
	}
	cfg, err := config.Load()
	if err != nil {
		return nil, ExitParseError, err.Error()
	}
	return cfg, 0, ""
}

func ctlPath(cfg map[string]any) (string, error) {
	if v, ok := config.GetByKey(cfg, "header.ctl_file"); ok {
		if s, ok := v.(string); ok && strings.TrimSpace(s) != "" {
			return s, nil
		}
	}
	return filepath.Join(config.ResolvePaths().Runtime, "xbs.ctl"), nil
}

func eventExists(cfg map[string]any, name string) bool {
	if name == "manual" {
		return true
	}
	if events, ok := cfg["events"].(map[string]any); ok {
		_, exists := events[name]
		return exists
	}
	return false
}

func writeCtl(path, value string) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	return os.WriteFile(path, []byte(value+"\n"), 0o644)
}

func readSnapshot() (StatusSnapshot, int, string) {
	p := config.ResolvePaths()
	data, err := os.ReadFile(p.Status)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return StatusSnapshot{}, ExitRuntime, "No status file found"
		}
		return StatusSnapshot{}, ExitRuntime, err.Error()
	}
	var raw map[string]any
	if err := json.Unmarshal(data, &raw); err != nil {
		return StatusSnapshot{}, ExitParseError, "Status parse error"
	}
	return snapshotFromRaw(raw), 0, ""
}

func snapshotFromRaw(raw map[string]any) StatusSnapshot {
	s := StatusSnapshot{Available: true, Mode: stringValue(raw["mode"]), ActiveEvents: stringSlice(raw["active_events"]), Raw: raw}
	if s.Mode == "" {
		s.Mode = "normal"
	}
	s.Version = raw["version"]
	s.LogLevel = raw["log_level"]
	s.LastReloadTime = stringValue(raw["last_reload_time"])
	s.ConfigSHA256 = stringValue(raw["config_sha256"])
	if detail, ok := raw["detail"].(map[string]any); ok {
		s.Detail = detail
	} else {
		s.Detail = map[string]any{}
	}
	return s
}

func waitForRequest(ctl, id string, timeout time.Duration) (Ack, bool) {
	deadline := time.Now().Add(timeout)
	for {
		if ack, ok := ReadAck(ctl, id); ok {
			return ack, true
		}
		if time.Now().After(deadline) {
			return Ack{}, false
		}
		time.Sleep(100 * time.Millisecond)
	}
}

func waitForEvent(action, event string, timeout time.Duration) bool {
	deadline := time.Now().Add(timeout)
	for {
		snap, code, _ := readSnapshot()
		if code == 0 {
			active := contains(snap.ActiveEvents, event)
			if action == "start" && active {
				return true
			}
			if action == "stop" && !active {
				return true
			}
		}
		if time.Now().After(deadline) {
			return false
		}
		time.Sleep(200 * time.Millisecond)
	}
}

func waitForMode(expected string, timeout time.Duration) bool {
	deadline := time.Now().Add(timeout)
	for {
		snap, code, _ := readSnapshot()
		if code == 0 && snap.Mode == expected {
			return true
		}
		if time.Now().After(deadline) {
			return false
		}
		time.Sleep(200 * time.Millisecond)
	}
}

func contains(items []string, needle string) bool {
	for _, item := range items {
		if item == needle {
			return true
		}
	}
	return false
}
func stringValue(v any) string {
	if v == nil {
		return ""
	}
	return fmt.Sprint(v)
}
func stringSlice(v any) []string {
	out := []string{}
	switch x := v.(type) {
	case []string:
		return append(out, x...)
	case []any:
		for _, item := range x {
			out = append(out, fmt.Sprint(item))
		}
	case string:
		for _, item := range strings.Fields(x) {
			out = append(out, item)
		}
	}
	return out
}

func emitError(stdout io.Writer, opts options, message string, code int) int {
	emit(stdout, opts, "error", message, nil, false)
	return code
}

func emit(stdout io.Writer, opts options, status, message string, data any, dataIsJSON bool) int {
	if opts.jsonMode {
		obj := map[string]any{"status": status, "message": message}
		if data != nil {
			obj["data"] = data
		}
		encoded, _ := json.Marshal(obj)
		fmt.Fprintln(stdout, string(encoded))
	} else {
		fmt.Fprintln(stdout, message)
		if data != nil && !dataIsJSON {
			fmt.Fprintln(stdout, data)
		}
	}
	if status == "error" {
		return ExitRuntime
	}
	if status == "timeout" {
		return ExitRuntime
	}
	return 0
}

func emitControl(stdout io.Writer, opts options, status, message, completion, action, event string, exit int, requestIDs ...string) int {
	snap, _, _ := readSnapshot()
	if opts.jsonMode {
		obj := map[string]any{"status": status, "message": message, "completion": completion, "requested_action": action, "event": event, "observed": snap}
		if len(requestIDs)>0 { obj["request_id"]=requestIDs[0] }
		encoded, _ := json.Marshal(obj)
		fmt.Fprintln(stdout, string(encoded))
	} else {
		fmt.Fprintln(stdout, message)
	}
	return exit
}

func printHelp(w io.Writer) {
	fmt.Fprintln(w, `XtremeBS Controller Options:

  start <event>    starts the manual event or a custom event
  stop <event>     stops the manual event or a custom event
  reload           reloads the config file
  pause            pauses the daemon
  resume           resumes the daemon or exits safe mode
  safe             enters safe mode
  active           shows active events
  status           summary (version, active, log level, mode, last reload)
  detail           shows detailed status
  validate         validates config structure and file paths

  Flags:
    --json          emit JSON output
    --wait          wait for the requested state transition
    --timeout SEC   wait timeout in seconds (default: 8)
    --ttl SEC       optional pre-execution queue deadline (1-3600 seconds)
    cancel ID       cancel a request only if not yet claimed
    recovery        read-only unknown-outcome and WAL inspection`)
}
