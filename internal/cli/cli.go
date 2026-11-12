package cli

import (
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"

	"github.com/Mikeyphw/xbsctl/internal/compat"
	"github.com/Mikeyphw/xbsctl/internal/config"
	"github.com/Mikeyphw/xbsctl/internal/contracts"
	"github.com/Mikeyphw/xbsctl/internal/control"
	"github.com/Mikeyphw/xbsctl/internal/eventbridge"
	"github.com/Mikeyphw/xbsctl/internal/machine"
	"github.com/Mikeyphw/xbsctl/internal/schema"
	"github.com/Mikeyphw/xbsctl/internal/version"
)

const exitUsage = 2

type globals struct{ jsonMode, quiet, noInteractive bool }

func Main(args []string, stdout io.Writer, stderr io.Writer) int {
	g, clean := parseGlobals(args)
	if len(clean) == 0 || clean[0] == "--help" || clean[0] == "-h" {
		printHelp(stdout)
		return 0
	}
	if clean[0] == "--version" || clean[0] == "version" {
		fmt.Fprintf(stdout, "%s %s %s\n", version.Name, version.GoBackendVersion, version.MigrationPhase)
		return 0
	}
	switch clean[0] {
	case "contracts":
		return contractsCommand(clean[1:], g, stdout, stderr)
	case "paths":
		return emitOK(stdout, g, "paths", map[string]any{"paths": config.PathPayload()})
	case "init":
		return initCommand(stdout, g)
	case "status":
		return emitOK(stdout, g, "status", map[string]any{"status": config.StatusFallback()})
	case "validate":
		return validateCommand(clean[1:], stdout, g)
	case "config":
		return configCommand(clean[1:], stdout, stderr, g)
	case "schema":
		return schemaCommand(clean[1:], stdout, stderr, g)
	case "event":
		return eventCommand(clean[1:], stdout, stderr, g)
	case "runtime":
		return runtimeCommand(clean[1:], stdout, stderr, g)
	case "capabilities":
		return capabilitiesCommand(stdout, g)
	case "event-bridge":
		return eventBridgeCommand(clean[1:], stdout, stderr, g)
	case "completion":
		return completionCommand(clean[1:], stdout, stderr, g)
	case "manpage":
		return manpageCommand(clean[1:], stdout, stderr, g)
	default:
		return usage(stderr, g, actionName(clean), fmt.Sprintf("unknown command %q", clean[0]))
	}
}

func parseGlobals(args []string) (globals, []string) {
	var g globals
	clean := make([]string, 0, len(args))
	for _, arg := range args {
		switch arg {
		case "--json":
			g.jsonMode = true
		case "--quiet":
			g.quiet = true
		case "--no-interactive":
			g.noInteractive = true
		default:
			clean = append(clean, arg)
		}
	}
	return g, clean
}

func contractsCommand(args []string, g globals, stdout io.Writer, stderr io.Writer) int {
	if len(args) == 0 || args[0] == "check" {
		summary, err := contracts.Check("")
		if err != nil {
			return machine.Fail(stdout, g.jsonMode, g.quiet, "contracts.check", err.Error(), 1, nil)
		}
		if g.jsonMode {
			return emitOK(stdout, g, "contracts.check", map[string]any{"summary": summary})
		}
		fmt.Fprintf(stdout, "XBS frozen contracts valid: cli=%d bridge=%d xbsctl=%d android=%d event_bridge=v%d\n", summary.CLICommands, summary.BridgeCommands, summary.XBSctlCommands, summary.AndroidCommands, summary.EventBridgeFormat)
		return 0
	}
	if args[0] == "summary" {
		summary, err := contracts.Check("")
		if err != nil {
			fmt.Fprintf(stderr, "xbs contracts summary failed: %v\n", err)
			return 1
		}
		enc, _ := json.MarshalIndent(summary, "", "  ")
		fmt.Fprintln(stdout, string(enc))
		return 0
	}
	return usage(stderr, g, "contracts", fmt.Sprintf("unknown contracts command %q", args[0]))
}

func initCommand(stdout io.Writer, g globals) int {
	p := config.ResolvePaths()
	created := []string{}
	skipped := []string{}
	if _, err := config.Load(); err != nil {
		return machine.Fail(stdout, g.jsonMode, g.quiet, "init", err.Error(), 1, nil)
	}
	if exists(p.Config) {
		skipped = append(skipped, p.Config)
	} else {
		cfg := config.Default()
		if _, err := config.Save(cfg); err != nil {
			return machine.Fail(stdout, g.jsonMode, g.quiet, "init", err.Error(), 1, nil)
		}
		created = append(created, p.Config)
	}
	return emitOK(stdout, g, "init", map[string]any{"config_paths": []string{p.Config}, "created": created, "skipped": skipped, "updated": []string{}, "tasker_dir": "", "paths": config.PathPayload()})
}

func validateCommand(args []string, stdout io.Writer, g globals) int {
	cfg, err := configFromArg(args)
	if err != nil {
		return machine.Fail(stdout, g.jsonMode, g.quiet, "validate", err.Error(), 1, nil)
	}
	errs := config.Validate(cfg)
	return machine.Emit(stdout, g.jsonMode, g.quiet, "validate", len(errs) == 0, "", boolExit(errs), map[string]any{"errors": errs})
}

func configCommand(args []string, stdout io.Writer, stderr io.Writer, g globals) int {
	if len(args) == 0 {
		return usage(stderr, g, "config", "config subcommand is required")
	}
	switch args[0] {
	case "get":
		cfg, err := config.Load()
		if err != nil {
			return machine.Fail(stdout, g.jsonMode, g.quiet, "config.get", err.Error(), 1, nil)
		}
		key := ""
		if len(args) > 1 {
			key = args[1]
		}
		value, ok := config.GetByKey(cfg, key)
		if !ok {
			return machine.Fail(stdout, g.jsonMode, g.quiet, "config.get", fmt.Sprintf("config key not found: %s", key), 1, map[string]any{"key": key})
		}
		return emitOK(stdout, g, "config.get", map[string]any{"key": key, "value": value})
	case "set":
		if len(args) < 3 {
			return usage(stderr, g, "config.set", "config set requires KEY and VALUE")
		}
		cfg, err := config.Load()
		if err != nil {
			return machine.Fail(stdout, g.jsonMode, g.quiet, "config.set", err.Error(), 1, nil)
		}
		if err := config.SetByKey(cfg, args[1], args[2]); err != nil {
			return machine.Fail(stdout, g.jsonMode, g.quiet, "config.set", err.Error(), 1, nil)
		}
		saved, err := config.Save(cfg)
		if err != nil {
			return machine.Fail(stdout, g.jsonMode, g.quiet, "config.set", err.Error(), 1, nil)
		}
		return emitOK(stdout, g, "config.set", saved)
	case "validate":
		cfg, err := configFromArg(args[1:])
		if err != nil {
			return machine.Fail(stdout, g.jsonMode, g.quiet, "config.validate", err.Error(), 1, nil)
		}
		errs := config.Validate(cfg)
		return machine.Emit(stdout, g.jsonMode, g.quiet, "config.validate", len(errs) == 0, "", boolExit(errs), map[string]any{"errors": errs})
	case "schema":
		payload, err := schema.Render("config", "")
		if err != nil {
			return machine.Fail(stdout, g.jsonMode, g.quiet, "schema.config", err.Error(), 1, nil)
		}
		return emitOK(stdout, g, "schema.config", payload)
	case "edit":
		p := config.ResolvePaths()
		if g.jsonMode || (len(args) > 1 && args[1] == "--print-path") {
			return emitOK(stdout, g, "config.edit", map[string]any{"path": p.Config, "print_path": true, "interactive": false})
		}
		fmt.Fprintln(stdout, p.Config)
		return 0
	default:
		return usage(stderr, g, "config", fmt.Sprintf("unknown config command %q", args[0]))
	}
}

func schemaCommand(args []string, stdout io.Writer, stderr io.Writer, g globals) int {
	kind := "root"
	key := ""
	if len(args) > 0 {
		kind = args[0]
	}
	if kind == "command" && len(args) > 1 {
		key = strings.Join(args[1:], ".")
	}
	payload, err := schema.Render(kind, key)
	if err != nil {
		return usage(stderr, g, "schema", fmt.Sprintf("unknown schema command %q", kind))
	}
	action := "schema"
	if kind != "root" && kind != "" {
		action = "schema." + kind
	}
	return emitOK(stdout, g, action, payload)
}

func eventCommand(args []string, stdout io.Writer, stderr io.Writer, g globals) int {
	if len(args) == 0 {
		return usage(stderr, g, "event", "event subcommand is required")
	}
	switch args[0] {
	case "list":
		cfg, err := config.Load()
		if err != nil {
			return machine.Fail(stdout, g.jsonMode, g.quiet, "event.list", err.Error(), 1, nil)
		}
		events := []string{"manual"}
		if m, ok := cfg["events"].(map[string]any); ok {
			for n := range m {
				if n != "template" && n != "manual" {
					events = append(events, n)
				}
			}
		}
		return emitOK(stdout, g, "event.list", map[string]any{"events": events})
	case "run", "stop":
		if len(args) > 1 && !strings.HasPrefix(args[1], "--") {
			cfg, err := config.Load()
			if err != nil {
				return machine.Fail(stdout, g.jsonMode, g.quiet, "event."+args[0], err.Error(), 1, nil)
			}
			name := args[1]
			found := name == "manual"
			if m, ok := cfg["events"].(map[string]any); ok {
				_, foundCfg := m[name]
				found = found || foundCfg
			}
			if !found || !config.ValidEventName(name) {
				return usage(stderr, g, "event."+args[0], "invalid event: "+name)
			}
		}
		ctl := []string{}
		if g.jsonMode {
			ctl = append(ctl, "--json")
		}
		if args[0] == "run" {
			ctl = append(ctl, "start")
		} else {
			ctl = append(ctl, "stop")
		}
		ctl = append(ctl, args[1:]...)
		return control.Main(ctl, stdout, stderr)
	default:
		return usage(stderr, g, "event", fmt.Sprintf("unknown event command %q", args[0]))
	}
}

func runtimeCommand(args []string, stdout io.Writer, stderr io.Writer, g globals) int {
	if len(args) == 0 {
		return usage(stderr, g, "runtime", "runtime subcommand is required")
	}
	switch args[0] {
	case "reload", "pause", "resume", "cancel", "recovery":
		ctl := []string{}
		if g.jsonMode {
			ctl = append(ctl, "--json")
		}
		ctl = append(ctl, args...)
		return control.Main(ctl, stdout, stderr)
	case "safe":
		ctlAction := "safe"
		rest := args[1:]
		if len(rest) > 0 && (rest[0] == "on" || rest[0] == "off") {
			if rest[0] == "off" {
				ctlAction = "resume"
			}
			rest = rest[1:]
		} else if len(rest) > 0 && !strings.HasPrefix(rest[0], "--") {
			return usage(stderr, g, "runtime.safe", "safe mode must be on or off")
		}
		ctl := []string{}
		if g.jsonMode {
			ctl = append(ctl, "--json")
		}
		ctl = append(ctl, ctlAction)
		ctl = append(ctl, rest...)
		return control.Main(ctl, stdout, stderr)
	default:
		return usage(stderr, g, "runtime", fmt.Sprintf("unknown runtime command %q", args[0]))
	}
}

func capabilitiesCommand(stdout io.Writer, g globals) int {
	var configVersion *int
	if cfg, err := config.Load(); err == nil {
		if header, ok := cfg["header"].(map[string]any); ok {
			if value, ok := intFromAny(header["version"]); ok {
				configVersion = &value
			}
		}
	}
	payload, err := compat.Payload(configVersion)
	if err != nil {
		return machine.Fail(stdout, g.jsonMode, g.quiet, "capabilities", err.Error(), 1, nil)
	}
	return emitOK(stdout, g, "capabilities", payload)
}

func eventBridgeCommand(args []string, stdout io.Writer, stderr io.Writer, g globals) int {
	paths := eventbridge.DefaultPaths()
	if len(args) == 0 {
		payload := eventbridge.VerificationPayload(paths)
		bridge, _ := payload["bridge"].(map[string]any)
		ok, _ := bridge["authenticated"].(bool)
		if ok {
			return emitOK(stdout, g, "event-bridge", payload)
		}
		return machine.Emit(stdout, g.jsonMode, g.quiet, "event-bridge", false, "Authenticated XBS event snapshot unavailable", 1, payload)
	}
	sub := args[0]
	for i := 1; i < len(args); i++ {
		arg := args[i]
		value := func() string {
			if i+1 >= len(args) {
				return ""
			}
			i++
			return args[i]
		}
		switch arg {
		case "--event":
			paths.Event = value()
		case "--status":
			paths.Status = value()
		case "--key":
			paths.Key = value()
		case "--envelope":
			paths.Envelope = value()
		case "--sequence":
			paths.Sequence = value()
		case "--quiet":
			g.quiet = true
		default:
			return usage(stderr, g, "event-bridge."+sub, "unknown event-bridge option: "+arg)
		}
	}
	switch sub {
	case "publish":
		result, err := eventbridge.Publish(paths, nil)
		if err != nil {
			return machine.Fail(stdout, g.jsonMode, g.quiet, "event-bridge.publish", err.Error(), 2, bridgeErrorData(err))
		}
		return emitOK(stdout, g, "event-bridge.publish", map[string]any{"bridge": result})
	case "verify":
		result, err := eventbridge.Verify(paths)
		if err != nil {
			return machine.Fail(stdout, g.jsonMode, g.quiet, "event-bridge.verify", err.Error(), 2, bridgeErrorData(err))
		}
		return emitOK(stdout, g, "event-bridge.verify", map[string]any{"bridge": result})
	default:
		return usage(stderr, g, "event-bridge", "unknown event-bridge command: "+sub)
	}
}

func completionCommand(args []string, stdout io.Writer, stderr io.Writer, g globals) int {
	kind := "zsh"
	if len(args) > 0 {
		kind = args[0]
	}
	switch kind {
	case "zsh", "complete-zsh":
		root := findAppRoot()
		data, err := os.ReadFile(filepath.Join(root, "completions", "zsh", "_xbs"))
		if err != nil {
			return machine.Fail(stdout, g.jsonMode, g.quiet, "completion."+kind, err.Error(), 1, nil)
		}
		if g.jsonMode {
			return emitOK(stdout, g, "completion."+kind, map[string]any{"shell": "zsh", "script": string(data)})
		}
		_, _ = stdout.Write(data)
		if len(data) == 0 || data[len(data)-1] != '\n' {
			fmt.Fprintln(stdout)
		}
		return 0
	case "install":
		root := findAppRoot()
		path := filepath.Join(root, "completions", "zsh", "_xbs")
		return emitOK(stdout, g, "completion.install", map[string]any{"installed": false, "source": path, "message": "copy the completion file into a directory on your fpath"})
	default:
		return usage(stderr, g, "completion", "unknown completion command: "+kind)
	}
}

func manpageCommand(args []string, stdout io.Writer, stderr io.Writer, g globals) int {
	kind := "show"
	if len(args) > 0 {
		kind = args[0]
	}
	switch kind {
	case "show":
		text := "XBS Go-native runtime\n\nUse xbs --help, XBSctl --help, and XtremeBSd --help for command details.\n"
		if g.jsonMode {
			return emitOK(stdout, g, "manpage.show", map[string]any{"text": text})
		}
		fmt.Fprint(stdout, text)
		return 0
	case "install":
		return emitOK(stdout, g, "manpage.install", map[string]any{"installed": false, "message": "manual page installation is not required for the Android module"})
	default:
		return usage(stderr, g, "manpage", "unknown manpage command: "+kind)
	}
}

func findAppRoot() string {
	wd, err := os.Getwd()
	if err != nil {
		return "."
	}
	for dir := wd; ; dir = filepath.Dir(dir) {
		if exists(filepath.Join(dir, "go.mod")) && exists(filepath.Join(dir, "cmd", "xbs", "main.go")) {
			return dir
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			return wd
		}
	}
}

func bridgeErrorData(err error) map[string]any {
	code := "error"
	if varr, ok := err.(*eventbridge.BridgeError); ok {
		code = varr.Code
	}
	return map[string]any{"reason_code": code}
}

func intFromAny(v any) (int, bool) {
	switch x := v.(type) {
	case int:
		return x, true
	case int64:
		return int(x), true
	case float64:
		if x == float64(int(x)) {
			return int(x), true
		}
	case json.Number:
		parsed, err := x.Int64()
		return int(parsed), err == nil
	case string:
		var parsed int
		_, err := fmt.Sscanf(strings.TrimSpace(x), "%d", &parsed)
		return parsed, err == nil
	}
	return 0, false
}

func configFromArg(args []string) (map[string]any, error) {
	if len(args) == 0 {
		return config.Load()
	}
	var cfg map[string]any
	if err := json.Unmarshal([]byte(args[0]), &cfg); err != nil {
		return nil, err
	}
	return cfg, nil
}
func emitOK(stdout io.Writer, g globals, action string, data map[string]any) int {
	return machine.OK(stdout, g.jsonMode, g.quiet, action, data)
}
func boolExit(errs []string) int {
	if len(errs) == 0 {
		return 0
	}
	return 1
}
func exists(path string) bool { _, err := os.Stat(path); return err == nil }

func usage(stderr io.Writer, g globals, action string, message string) int {
	if g.jsonMode {
		return machine.Fail(stderr, true, g.quiet, action, message, exitUsage, nil)
	}
	fmt.Fprintf(stderr, "xbs: %s\n", message)
	return exitUsage
}

func actionName(args []string) string {
	if len(args) == 0 {
		return ""
	}
	if len(args) >= 2 && (args[0] == "config" || args[0] == "runtime" || args[0] == "event" || args[0] == "schema" || args[0] == "completion" || args[0] == "manpage") {
		return args[0] + "." + args[1]
	}
	return args[0]
}

func printHelp(w io.Writer) {
	fmt.Fprintf(w, `xbs Go backend (%s)

Usage:
  xbs [--json] [--quiet] [--no-interactive] COMMAND

Implemented in %s:
  status, init, paths, validate, capabilities, event-bridge, config get/set/validate/schema,
  event list/run/stop, runtime reload/pause/resume/safe, completion, manpage, schema, contracts

The Python backend has been retired from the production module path.
`, version.GoBackendVersion, version.MigrationPhase)
}
