package contracts

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

const Revision = "v1"

var RequiredFiles = []string{
	"android_catalog.json",
	"capabilities.json",
	"cli_commands.json",
	"command_status_schema.json",
	"config_schema.json",
	"event_bridge_envelope.json",
	"machine_output_status_schema.json",
	"xbsctl_commands.json",
}

type Summary struct {
	Revision          string   `json:"revision"`
	Directory         string   `json:"directory"`
	Files             []string `json:"files"`
	CLICommands       int      `json:"cli_commands"`
	BridgeCommands    int      `json:"bridge_commands"`
	XBSctlCommands    int      `json:"xbsctl_commands"`
	AndroidCommands   int      `json:"android_commands"`
	EventBridgeFormat int      `json:"event_bridge_format"`
}

func FindDir(start string) (string, error) {
	if start == "" {
		var err error
		start, err = os.Getwd()
		if err != nil {
			return "", err
		}
	}
	start, err := filepath.Abs(start)
	if err != nil {
		return "", err
	}
	info, err := os.Stat(start)
	if err != nil {
		return "", err
	}
	if !info.IsDir() {
		start = filepath.Dir(start)
	}
	for dir := start; ; dir = filepath.Dir(dir) {
		candidate := filepath.Join(dir, "migration", "contracts", Revision)
		if hasRequiredFiles(candidate) {
			return candidate, nil
		}
		candidate = filepath.Join(dir, "apps", "xbs", "migration", "contracts", Revision)
		if hasRequiredFiles(candidate) {
			return candidate, nil
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			break
		}
	}
	return "", fmt.Errorf("XBS %s contract directory not found from %s", Revision, start)
}

func Check(start string) (Summary, error) {
	dir, err := FindDir(start)
	if err != nil {
		return Summary{}, err
	}
	for _, name := range RequiredFiles {
		if _, err := os.Stat(filepath.Join(dir, name)); err != nil {
			return Summary{}, fmt.Errorf("missing frozen XBS contract %s: %w", name, err)
		}
	}

	cliEnvelope, err := readObject(filepath.Join(dir, "cli_commands.json"))
	if err != nil {
		return Summary{}, err
	}
	cliCommands, err := commandCount(cliEnvelope, "data", "commands")
	if err != nil {
		return Summary{}, fmt.Errorf("cli_commands.json: %w", err)
	}
	if cliCommands != 17 {
		return Summary{}, fmt.Errorf("cli command count drift: expected 17, got %d", cliCommands)
	}

	capEnvelope, err := readObject(filepath.Join(dir, "capabilities.json"))
	if err != nil {
		return Summary{}, err
	}
	bridgeCommands, err := commandCount(capEnvelope, "data", "compatibility", "commands")
	if err != nil {
		return Summary{}, fmt.Errorf("capabilities.json: %w", err)
	}
	if bridgeCommands != 39 {
		return Summary{}, fmt.Errorf("compatibility command count drift: expected 39, got %d", bridgeCommands)
	}

	ctlFixture, err := readObject(filepath.Join(dir, "xbsctl_commands.json"))
	if err != nil {
		return Summary{}, err
	}
	xbsctlCommands, err := commandCount(ctlFixture, "commands")
	if err != nil {
		return Summary{}, fmt.Errorf("xbsctl_commands.json: %w", err)
	}
	if xbsctlCommands != 11 {
		return Summary{}, fmt.Errorf("XBSctl command count drift: expected 11, got %d", xbsctlCommands)
	}

	androidFixture, err := readObject(filepath.Join(dir, "android_catalog.json"))
	if err != nil {
		return Summary{}, err
	}
	androidCommands, err := commandCount(androidFixture, "commands")
	if err != nil {
		return Summary{}, fmt.Errorf("android_catalog.json: %w", err)
	}
	if androidCommands < 20 {
		return Summary{}, fmt.Errorf("android command catalog is unexpectedly small: %d", androidCommands)
	}

	eventFixture, err := readObject(filepath.Join(dir, "event_bridge_envelope.json"))
	if err != nil {
		return Summary{}, err
	}
	formatVersion := intNumber(eventFixture["format_version"])
	if formatVersion != 1 {
		return Summary{}, fmt.Errorf("event bridge format drift: expected 1, got %d", formatVersion)
	}

	files := append([]string(nil), RequiredFiles...)
	sort.Strings(files)
	return Summary{
		Revision:          Revision,
		Directory:         dir,
		Files:             files,
		CLICommands:       cliCommands,
		BridgeCommands:    bridgeCommands,
		XBSctlCommands:    xbsctlCommands,
		AndroidCommands:   androidCommands,
		EventBridgeFormat: formatVersion,
	}, nil
}

func readObject(path string) (map[string]any, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var obj map[string]any
	if err := json.Unmarshal(data, &obj); err != nil {
		return nil, fmt.Errorf("%s is not valid JSON: %w", path, err)
	}
	return obj, nil
}

func hasRequiredFiles(dir string) bool {
	info, err := os.Stat(dir)
	if err != nil || !info.IsDir() {
		return false
	}
	for _, name := range RequiredFiles {
		if _, err := os.Stat(filepath.Join(dir, name)); err != nil {
			return false
		}
	}
	return true
}

func commandCount(root map[string]any, path ...string) (int, error) {
	var current any = root
	for _, key := range path {
		obj, ok := current.(map[string]any)
		if !ok {
			return 0, fmt.Errorf("%s is not an object", strings.Join(path, "."))
		}
		current = obj[key]
	}
	rows, ok := current.([]any)
	if !ok {
		return 0, fmt.Errorf("%s is not an array", strings.Join(path, "."))
	}
	return len(rows), nil
}

func intNumber(value any) int {
	switch typed := value.(type) {
	case float64:
		return int(typed)
	case int:
		return typed
	default:
		return 0
	}
}

func ValidateRefusesProduction(command []string) error {
	if len(command) == 0 {
		return errors.New("command is required")
	}
	if command[0] == "contracts" {
		return nil
	}
	return fmt.Errorf("production command %q is intentionally disabled in XGO01", strings.Join(command, " "))
}
