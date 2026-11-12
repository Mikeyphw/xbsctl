package tuning

import "strings"

// RecoveryResource maps a durable original-value key to the one domain which
// may release it. Unknown keys are never interpreted as safe scoped writes.
func RecoveryResource(key string) string {
	switch {
	case strings.HasPrefix(key, "file:/sys/devices/system/cpu/") && strings.HasSuffix(key, "/cpufreq/scaling_governor"):
		return "cpu-governor"
	case strings.HasPrefix(key, "file:/sys/devices/system/cpu/") && strings.HasSuffix(key, "/online"):
		return "cpu-online"
	case strings.HasPrefix(key, "suspend:user0:"):
		return "app-packages"
	case strings.HasPrefix(key, "nice:"):
		return "process-nice"
	case key == "gms-enabled":
		return "gms"
	case key == "low_ram":
		return "low-ram"
	case key == "doze:owned":
		return "doze"
	case key == "wifi-enabled":
		return "wifi"
	default:
		return ""
	}
}
