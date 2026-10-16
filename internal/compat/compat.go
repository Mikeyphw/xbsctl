package compat

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"

	"github.com/Mikeyphw/xbsctl/internal/config"
	"github.com/Mikeyphw/xbsctl/internal/version"
)

const (
	ContractVersion     = "1.1.0"
	CLICompatibilityVer = "1.3.0"
	AndroidContractMin  = 1
	AndroidContractMax  = 1
	ConfigVersionMax    = config.ConfigVersion
	maxPropertiesBytes  = 32 * 1024
	maxSchemaBytes      = 256 * 1024
	maxJSONStringChars  = 8192
	maxEventFields      = 64
)

var safeID = regexp.MustCompile(`^[A-Za-z][A-Za-z0-9_]{0,95}$`)

var commandIDs = []string{
	"help", "status", "init", "paths", "validate", "capabilities",
	"config_get", "config_set", "config_validate", "config_edit_cli", "config_schema",
	"runtime_reload", "runtime_pause", "runtime_resume", "runtime_safe",
	"event_list", "event_run", "event_stop",
	"ctl_help", "ctl_start", "ctl_stop", "ctl_reload", "ctl_pause", "ctl_resume",
	"ctl_safe", "ctl_active", "ctl_status", "ctl_detail", "ctl_validate",
	"schema_root", "schema_commands", "schema_command", "schema_output", "schema_config",
	"completion_zsh", "completion_install", "completion_complete_zsh",
	"manpage_show", "manpage_install",
}

var commandPaths = map[string][]string{
	"help":                    {"--help"},
	"status":                  {"status"},
	"init":                    {"init"},
	"paths":                   {"paths"},
	"validate":                {"validate"},
	"capabilities":            {"capabilities"},
	"config_get":              {"config", "get"},
	"config_set":              {"config", "set"},
	"config_validate":         {"config", "validate"},
	"config_edit_cli":         {"config", "edit"},
	"config_schema":           {"config", "schema"},
	"runtime_reload":          {"runtime", "reload"},
	"runtime_pause":           {"runtime", "pause"},
	"runtime_resume":          {"runtime", "resume"},
	"runtime_safe":            {"runtime", "safe"},
	"event_list":              {"event", "list"},
	"event_run":               {"event", "run"},
	"event_stop":              {"event", "stop"},
	"ctl_help":                {"--help"},
	"ctl_start":               {"start"},
	"ctl_stop":                {"stop"},
	"ctl_reload":              {"reload"},
	"ctl_pause":               {"pause"},
	"ctl_resume":              {"resume"},
	"ctl_safe":                {"safe"},
	"ctl_active":              {"active"},
	"ctl_status":              {"status"},
	"ctl_detail":              {"detail"},
	"ctl_validate":            {"validate"},
	"schema_root":             {"schema"},
	"schema_commands":         {"schema", "commands"},
	"schema_command":          {"schema", "command"},
	"schema_output":           {"schema", "output"},
	"schema_config":           {"schema", "config"},
	"completion_zsh":          {"completion", "zsh"},
	"completion_install":      {"completion", "install"},
	"completion_complete_zsh": {"completion", "complete-zsh"},
	"manpage_show":            {"manpage", "show"},
	"manpage_install":         {"manpage", "install"},
}

func Payload(configVersion *int) (map[string]any, error) {
	root := moduleRoot()
	props := properties(filepath.Join(root, "module.prop"))
	schema := loadSchema(root)
	eventFields := eventFields(schema)
	resolvedConfigVersion := ConfigVersionMax
	if configVersion != nil {
		resolvedConfigVersion = safeInt(*configVersion, ConfigVersionMax, 1, ConfigVersionMax)
	}
	commands, err := commandRows()
	if err != nil {
		return nil, err
	}
	schemaDigest := ""
	if len(schema) > 0 {
		schemaDigest = sha256JSON(schema)
	}
	digestInput := map[string]any{
		"contract_version":       ContractVersion,
		"command_schema_version": version.CommandSchemaVersion,
		"commands":               commands,
		"event_fields":           eventFields,
		"config_version_max":     ConfigVersionMax,
	}
	moduleID := props["id"]
	if !safeID.MatchString(moduleID) {
		moduleID = config.ModuleID
	}
	compatibility := map[string]any{
		"contract_version": ContractVersion,
		"generated_by":     "xbs-cli",
		"module": map[string]any{
			"id":           moduleID,
			"version":      truncate(props["version"], 120, "unknown"),
			"version_code": safeAtoi(props["versionCode"], 0, 0, 0),
		},
		"cli": map[string]any{
			"version":                       CLICompatibilityVer,
			"command_schema_version":        version.CommandSchemaVersion,
			"machine_output_schema_version": version.MachineOutputVersion,
		},
		"android_contract": map[string]any{
			"minimum":                  AndroidContractMin,
			"maximum":                  AndroidContractMax,
			"minimum_app_version_code": 1,
		},
		"config": map[string]any{
			"active_version":            resolvedConfigVersion,
			"maximum_supported_version": ConfigVersionMax,
			"schema_digest":             schemaDigest,
			"event_fields":              eventFields,
		},
		"commands": commands,
		"capabilities": map[string]any{
			"schema_negotiation":           true,
			"schema_digest":                schemaDigest != "",
			"typed_machine_output":         true,
			"custom_events":                true,
			"simultaneous_events":          true,
			"transactional_android_editor": true,
			"authenticated_event_bridge":   true,
		},
		"runtime": map[string]any{
			"kind":            "go-native",
			"executables":     []string{"xbs", "XBSctl", "XtremeBSd"},
			"python_required": false,
			"yq_required":     false,
			"bash_required":   false,
			"bc_required":     false,
		},
		"event_bridge": map[string]any{
			"format_versions":     []int{1},
			"algorithms":          []string{"hmac-sha256"},
			"preferred_transport": "root_verified_envelope",
			"fallback_transport":  "bounded_direct_poll",
			"replay_protection":   true,
			"trust_boundary":      "protects against non-root runtime-file tampering; root compromise is out of scope",
		},
		"digest_algorithm": "sha256",
		"digest":           sha256JSON(digestInput),
	}
	return map[string]any{"compatibility": compatibility}, nil
}

func moduleRoot() string {
	if configured := strings.TrimSpace(os.Getenv("XBS_MODULE_DIR")); configured != "" {
		return configured
	}
	wd, err := os.Getwd()
	if err != nil {
		return "."
	}
	for dir := wd; ; dir = filepath.Dir(dir) {
		if _, err := os.Stat(filepath.Join(dir, "module.prop")); err == nil {
			return dir
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			break
		}
	}
	return wd
}

func properties(path string) map[string]string {
	data, err := os.ReadFile(path)
	if err != nil || len(data) > maxPropertiesBytes {
		return map[string]string{}
	}
	values := map[string]string{}
	for _, raw := range strings.Split(string(data), "\n") {
		if len(raw) > 2048 {
			continue
		}
		line := strings.TrimSpace(raw)
		if line == "" || strings.HasPrefix(line, "#") || !strings.Contains(line, "=") {
			continue
		}
		parts := strings.SplitN(line, "=", 2)
		key := strings.TrimSpace(parts[0])
		value := strings.TrimSpace(parts[1])
		if safeID.MatchString(key) && len(value) <= maxJSONStringChars {
			values[key] = value
		}
	}
	return values
}

func loadSchema(root string) map[string]any {
	for _, path := range []string{filepath.Join(root, "schema.json"), filepath.Join(root, "module", "schema.json")} {
		data, err := os.ReadFile(path)
		if err != nil || len(data) > maxSchemaBytes || len(data) == 0 {
			continue
		}
		var parsed map[string]any
		if err := json.Unmarshal(data, &parsed); err == nil {
			return parsed
		}
	}
	return map[string]any{}
}

func eventFields(schema map[string]any) []string {
	props, ok := lookupMap(schema, "properties", "events", "additionalProperties", "properties")
	if !ok {
		return []string{}
	}
	fields := make([]string, 0, len(props))
	for key := range props {
		if safeID.MatchString(key) {
			fields = append(fields, key)
		}
		if len(fields) >= maxEventFields {
			break
		}
	}
	sort.Strings(fields)
	return fields
}

func lookupMap(root map[string]any, path ...string) (map[string]any, bool) {
	var cur any = root
	for _, part := range path {
		obj, ok := cur.(map[string]any)
		if !ok {
			return nil, false
		}
		cur, ok = obj[part]
		if !ok {
			return nil, false
		}
	}
	obj, ok := cur.(map[string]any)
	return obj, ok
}

func commandRows() ([]map[string]any, error) {
	rows := make([]map[string]any, 0, len(commandIDs))
	for _, id := range commandIDs {
		if !safeID.MatchString(id) {
			return nil, fmt.Errorf("unsafe command id in compatibility contract: %q", id)
		}
		path := commandPaths[id]
		if len(path) == 0 {
			return nil, fmt.Errorf("missing command path in compatibility contract: %q", id)
		}
		for _, part := range path {
			if part == "" || len(part) > 96 || strings.ContainsAny(part, "\r\n\t\x00") {
				return nil, fmt.Errorf("unsafe command path in compatibility contract: %q", id)
			}
		}
		exe := "xbs"
		if strings.HasPrefix(id, "ctl_") {
			exe = "XBSctl"
		}
		rows = append(rows, map[string]any{"id": id, "executable": exe, "path": append([]string(nil), path...)})
	}
	return rows, nil
}

func sha256JSON(value any) string {
	encoded, err := json.Marshal(value)
	if err != nil {
		return ""
	}
	digest := sha256.Sum256(encoded)
	return hex.EncodeToString(digest[:])
}

func safeInt(value int, def int, minimum int, maximum int) int {
	if value < minimum {
		return def
	}
	if maximum > 0 && value > maximum {
		return maximum
	}
	return value
}

func safeAtoi(value string, def int, minimum int, maximum int) int {
	parsed, err := strconv.Atoi(strings.TrimSpace(value))
	if err != nil || parsed < minimum {
		return def
	}
	if maximum > 0 && parsed > maximum {
		return maximum
	}
	return parsed
}

func truncate(value string, n int, def string) string {
	if value == "" {
		return def
	}
	if len(value) > n {
		return value[:n]
	}
	return value
}
