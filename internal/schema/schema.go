package schema

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sort"

	"github.com/Mikeyphw/xbsctl/internal/config"
	"github.com/Mikeyphw/xbsctl/internal/contracts"
)

type Field struct {
	Key         string `json:"key"`
	Title       string `json:"title"`
	Description string `json:"description"`
	Type        string `json:"type"`
	Default     any    `json:"default"`
	Editable    bool   `json:"editable"`
	Examples    []any  `json:"examples,omitempty"`
}

type Command struct {
	Path        []string         `json:"path"`
	Summary     string           `json:"summary"`
	Args        []map[string]any `json:"args,omitempty"`
	Flags       []map[string]any `json:"flags,omitempty"`
	Interactive bool             `json:"interactive,omitempty"`
}

func Commands() []Command {
	return []Command{
		{Path: []string{"status"}, Summary: "Show current runtime status."},
		{Path: []string{"init"}, Summary: "Seed the XBS config when missing."},
		{Path: []string{"paths"}, Summary: "Show resolved paths."},
		{Path: []string{"capabilities"}, Summary: "Publish the module and CLI compatibility contract."},
		{Path: []string{"validate"}, Summary: "Validate config from disk or JSON.", Args: []map[string]any{{"name": "json_text", "required": false}}},
		{Path: []string{"config", "get"}, Summary: "Read a config value.", Args: []map[string]any{{"name": "key", "required": false}}},
		{Path: []string{"config", "set"}, Summary: "Write a config value.", Args: []map[string]any{{"name": "key", "required": true}, {"name": "value", "required": true}}},
		{Path: []string{"config", "validate"}, Summary: "Validate config payload.", Args: []map[string]any{{"name": "json_text", "required": false}}},
		{Path: []string{"config", "edit"}, Summary: "Open the active config in an editor.", Flags: []map[string]any{{"name": "--editor", "required": false, "kind": "flag"}, {"name": "--print-path", "type": "boolean", "required": false, "kind": "flag"}}, Interactive: true},
		{Path: []string{"config", "schema"}, Summary: "Show the config payload JSON schema."},
		{Path: []string{"runtime", "reload"}, Summary: "Reload the runtime."},
		{Path: []string{"runtime", "pause"}, Summary: "Pause the runtime."},
		{Path: []string{"runtime", "resume"}, Summary: "Resume the runtime."},
		{Path: []string{"runtime", "safe"}, Summary: "Apply runtime safe mode.", Args: []map[string]any{{"name": "mode", "required": false}}},
		{Path: []string{"event", "list"}, Summary: "List event keys."},
		{Path: []string{"event", "run"}, Summary: "Start an event runner.", Args: []map[string]any{{"name": "event", "required": false}}},
		{Path: []string{"event", "stop"}, Summary: "Stop an event runner.", Args: []map[string]any{{"name": "event", "required": false}}},
	}
}

func ConfigEntries() []Field {
	d := config.Default()
	return []Field{
		{Key: "header.version", Title: "Config version", Description: "Version marker for the XBS config structure.", Type: "integer", Default: 2, Editable: false, Examples: []any{2}},
		{Key: "header.delay", Title: "Boot delay seconds", Description: "Delay before the XBS action set runs after an event is triggered.", Type: "integer", Default: 3, Editable: true, Examples: []any{0, 3, 10}},
		{Key: "header.log_file", Title: "header.log_file", Description: "", Type: "string", Default: mustGet(d, "header.log_file"), Editable: true},
		{Key: "header.log_level", Title: "Log verbosity", Description: "Numeric verbosity level used by the XBS runtime.", Type: "integer", Default: 3, Editable: true, Examples: []any{1, 3, 5}},
		{Key: "header.log_json", Title: "header.log_json", Description: "", Type: "boolean", Default: false, Editable: true},
		{Key: "header.dry_run", Title: "header.dry_run", Description: "", Type: "boolean", Default: false, Editable: true},
		{Key: "header.lock_file", Title: "header.lock_file", Description: "", Type: "string", Default: mustGet(d, "header.lock_file"), Editable: true},
		{Key: "header.ctl_file", Title: "header.ctl_file", Description: "", Type: "string", Default: mustGet(d, "header.ctl_file"), Editable: true},
		{Key: "header.essentials_allowlist", Title: "header.essentials_allowlist", Description: "", Type: "string", Default: mustGet(d, "header.essentials_allowlist"), Editable: true},
		{Key: "header.notify", Title: "Notifications enabled", Description: "Enable or disable user-facing notifications for XBS actions.", Type: "boolean", Default: true, Editable: true, Examples: []any{true, false}},
		{Key: "events.template.allowlist", Title: "Default allowlist", Description: "Default package allowlist inherited by event-specific sections.", Type: "string", Default: "", Editable: true, Examples: []any{"com.termux\norg.mozilla.firefox"}},
		{Key: "events.template.denylist", Title: "events.template.denylist", Description: "", Type: "string", Default: "", Editable: true},
		{Key: "events.template.proc_file", Title: "events.template.proc_file", Description: "", Type: "string", Default: "", Editable: true},
	}
}

func mustGet(root map[string]any, key string) any { v, _ := config.GetByKey(root, key); return v }

func Render(kind string, key string) (map[string]any, error) {
	switch kind {
	case "", "root":
		return map[string]any{"schema_version": "1.0.0", "schemas": []string{"commands", "command", "output", "config"}}, nil
	case "commands":
		rows := []map[string]any{}
		for _, c := range Commands() {
			rows = append(rows, commandMap(c))
		}
		return map[string]any{"schema_version": "1.0.0", "commands": rows}, nil
	case "command":
		for _, c := range Commands() {
			if join(c.Path) == key {
				return map[string]any{"schema_version": "1.0.0", "command": commandMap(c)}, nil
			}
		}
		return map[string]any{"schema_version": "1.0.0", "command": nil}, nil
	case "output":
		return map[string]any{"schema_version": "2.0.0", "fields": []string{"schema_version", "ok", "app", "action", "message", "exit_code", "data", "error"}}, nil
	case "config":
		entries := []map[string]any{}
		for _, e := range ConfigEntries() {
			entries = append(entries, fieldMap(e))
		}
		return map[string]any{"schema_version": "1.0.0", "entries": entries}, nil
	default:
		return nil, os.ErrInvalid
	}
}

func commandMap(c Command) map[string]any {
	m := map[string]any{"path": c.Path, "summary": c.Summary}
	if len(c.Args) > 0 {
		m["args"] = c.Args
	}
	if len(c.Flags) > 0 {
		m["flags"] = c.Flags
	}
	if c.Interactive {
		m["interactive"] = true
	}
	return m
}
func fieldMap(f Field) map[string]any {
	m := map[string]any{"key": f.Key, "title": f.Title, "description": f.Description, "type": f.Type, "default": f.Default, "editable": f.Editable}
	if len(f.Examples) > 0 {
		m["examples"] = f.Examples
	}
	return m
}
func join(parts []string) string {
	out := ""
	for i, p := range parts {
		if i > 0 {
			out += "."
		}
		out += p
	}
	return out
}

func FrozenCLICommandCount() (int, error) {
	dir, err := contracts.FindDir("")
	if err != nil {
		return 0, err
	}
	data, err := os.ReadFile(filepath.Join(dir, "cli_commands.json"))
	if err != nil {
		return 0, err
	}
	var obj map[string]any
	if err := json.Unmarshal(data, &obj); err != nil {
		return 0, err
	}
	dataObj, _ := obj["data"].(map[string]any)
	rows, _ := dataObj["commands"].([]any)
	return len(rows), nil
}

func CommandPaths() []string {
	rows := []string{}
	for _, c := range Commands() {
		rows = append(rows, join(c.Path))
	}
	sort.Strings(rows)
	return rows
}
