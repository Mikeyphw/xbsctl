package tuning

import "testing"

func TestR09ETuningAndroidProbeOutputRefusals(t *testing.T) {
	name := "com.google.android.gms"
	for _, tc := range []struct {
		raw   string
		found bool
		bad   bool
	}{
		{"", false, false}, {"package:" + name + "\n", true, false},
		{"package:" + name + ".clone\n", false, true},
		{"package:" + name + "\npackage:" + name + "\n", false, true},
		{"garbage", false, true}, {"package:other", false, true},
	} {
		found, err := exactPackageListing(tc.raw, name)
		if (err != nil) != tc.bad || (!tc.bad && found != tc.found) {
			t.Fatalf("listing %q -> %t %v", tc.raw, found, err)
		}
	}
	for _, tc := range []struct {
		raw     string
		enabled bool
		bad     bool
	}{
		{"Wi-Fi is enabled", true, false}, {"Wi-Fi is disabled", false, false},
		{"Wi-Fi is enabled\r\nadditional status", true, false},
		{"Wi-Fi is not enabled", false, true}, {"", false, true},
	} {
		got, err := originalWiFiEnabled(tc.raw)
		if (err != nil) != tc.bad || (!tc.bad && got != tc.enabled) {
			t.Fatalf("wifi status %q -> %t %v", tc.raw, got, err)
		}
	}
}
