package tuning

import (
	"strings"
	"testing"
)

func TestR01DRecoveryResourceMapping(t *testing.T) {
	cases := map[string]string{
		"file:/sys/devices/system/cpu/cpu1/cpufreq/scaling_governor": "cpu-governor",
		"file:/sys/devices/system/cpu/cpu2/online":                   "cpu-online",
		"suspend:user0:org.example":                                  "app-packages",
		"nice:101":                                                   "process-nice", "gms-enabled": "gms", "low_ram": "low-ram", "doze:owned": "doze", "wifi-enabled": "wifi",
		"file:/tmp/malicious": "",
	}
	for k, w := range cases {
		if got := RecoveryResource(k); got != w {
			t.Errorf("%q: %q want %q", k, got, w)
		}
	}
}
func TestR01DScopedRestorationLeavesUnrelatedSettingsUntouched(t *testing.T) {
	r := &fakeRunner{files: map[string]string{"/sys/devices/system/cpu/cpu1/cpufreq/scaling_governor": "schedutil"}}
	e := New(r)
	cpu := Event{Name: "cpu", HandleCores: "cpu1", DisableCores: "false", HandleApps: "false", HandleGMS: "false", Doze: "false"}
	wifi := Event{Name: "wifi", HandleCores: "false", DisableCores: "false", HandleApps: "false", HandleGMS: "false", Doze: "false", KillWiFi: true}
	if _, err := e.Apply(cpu, false); err != nil {
		t.Fatal(err)
	}
	if _, err := e.Apply(wifi, false); err != nil {
		t.Fatal(err)
	}
	if _, err := e.RestoreResources(wifi, []string{"wifi"}); err != nil {
		t.Fatal(err)
	}
	if r.files["/sys/devices/system/cpu/cpu1/cpufreq/scaling_governor"] != "powersave" {
		t.Fatal("reverted peer governor")
	}
	if _, ok := e.Snapshot()["wifi-enabled"]; ok {
		t.Fatal("Wi-Fi journal not released")
	}
	if _, ok := e.Snapshot()["file:/sys/devices/system/cpu/cpu1/cpufreq/scaling_governor"]; !ok {
		t.Fatal("peer journal lost")
	}
	if _, err := e.RestoreResources(cpu, []string{"cpu-governor"}); err != nil {
		t.Fatal(err)
	}
	if got := r.files["/sys/devices/system/cpu/cpu1/cpufreq/scaling_governor"]; got != "schedutil" {
		t.Fatalf("governor=%q", got)
	}
	if len(e.Snapshot()) != 0 {
		t.Fatalf("journal remains: %v", e.Snapshot())
	}
}
func TestR01DUnknownRecoveryKeyRefusesBeforeAnyMutation(t *testing.T) {
	r := &fakeRunner{files: map[string]string{}}
	e := New(r)
	e.LoadSnapshot(map[string]string{"file:/tmp/not-trusted": "true", "wifi-enabled": "true"})
	_, err := e.RestoreResources(Event{Name: "wifi"}, []string{"wifi"})
	if err == nil || !strings.Contains(err.Error(), "unrecognized recovery key") {
		t.Fatalf("unknown journal accepted: %v", err)
	}
	for _, c := range r.calls {
		if strings.HasPrefix(c, "svc wifi enable") {
			t.Fatalf("wrote state after refusal: %v", r.calls)
		}
	}
}
