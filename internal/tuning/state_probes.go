package tuning

import (
	"fmt"
	"strings"
)

const gmsPackage = "com.google.android.gms"

// Android's package-manager filter output must match an exact package line.
// Substring searches can mistake com.google.android.gms.clone for real GMS.
func exactPackageListing(output, packageName string) (bool, error) {
	found := false
	for _, raw := range strings.Split(output, "\n") {
		line := strings.TrimSpace(raw)
		if line == "" {
			continue
		}
		if !strings.HasPrefix(line, "package:") {
			return false, fmt.Errorf("invalid package-manager listing line %q", line)
		}
		if line != "package:"+packageName {
			return false, fmt.Errorf("unexpected package-manager listing item %q", line)
		}
		if found {
			return false, fmt.Errorf("duplicate package listing %q", packageName)
		}
		found = true
	}
	return found, nil
}

func (e *Engine) initiallyEnabledGMS() (bool, error) {
	disabled, err := e.R.Run("pm", "list", "packages", "-d", gmsPackage)
	if err != nil {
		return false, fmt.Errorf("query disabled GMS: %w", err)
	}
	enabled, err := e.R.Run("pm", "list", "packages", "-e", gmsPackage)
	if err != nil {
		return false, fmt.Errorf("query enabled GMS: %w", err)
	}
	isDisabled, err := exactPackageListing(disabled, gmsPackage)
	if err != nil {
		return false, err
	}
	isEnabled, err := exactPackageListing(enabled, gmsPackage)
	if err != nil {
		return false, err
	}
	if isDisabled == isEnabled {
		return false, fmt.Errorf("GMS enablement ambiguous (enabled=%t disabled=%t)", isEnabled, isDisabled)
	}
	return isEnabled, nil
}

func originalWiFiEnabled(output string) (bool, error) {
	lines := strings.Split(strings.ReplaceAll(output, "\r\n", "\n"), "\n")
	first := strings.ToLower(strings.TrimSpace(lines[0]))
	switch first {
	case "wi-fi is enabled":
		return true, nil
	case "wi-fi is disabled":
		return false, nil
	default:
		return false, fmt.Errorf("unknown Wi-Fi state: %q", strings.TrimSpace(lines[0]))
	}
}

// DeviceIdleController exposes 'get force' and 'unforce' in Android's shell
// contract. We only claim a reversible forced-idle transition if the device
// wasn't already externally forced; unexpected outputs must fail closed.
func (e *Engine) initiallyForcedDoze() (bool, error) {
	output, err := e.R.Run("dumpsys", "deviceidle", "get", "force")
	if err != nil {
		return false, fmt.Errorf("query forced Doze state: %w", err)
	}
	switch strings.TrimSpace(output) {
	case "true":
		return true, nil
	case "false":
		return false, nil
	default:
		return false, fmt.Errorf("unrecognized forced Doze state %q", strings.TrimSpace(output))
	}
}
