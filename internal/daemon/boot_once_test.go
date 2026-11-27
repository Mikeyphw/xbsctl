package daemon

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Mikeyphw/xbsctl/internal/tuning"
)

const sampleBootID = "123e4567-e89b-12d3-a456-426614174000"

type bootProbe struct {
	*r03aPowerFixture
	id string
}

func (f *bootProbe) Read(path string) (string, error) {
	if path == "/proc/sys/kernel/random/boot_id" {
		return f.id, nil
	}
	return f.r03aPowerFixture.Read(path)
}
func bootSetup(t *testing.T) (map[string]any, *state, *tuning.Engine) {
	t.Helper()
	cfg, s, _, _ := r03aSetup(t)
	cfg["header"].(map[string]any)["automatic_boot_enabled"] = true
	cfg["events"].(map[string]any)["boot"] = map[string]any{}
	return cfg, s, tuning.New(&bootProbe{r03aPowerFixture: &r03aPowerFixture{charging: "Charging", capacity: "80", screen: "Awake"}, id: sampleBootID})
}
func TestR03BBootOnceAndReplaySkipped(t *testing.T) {
	cfg, s, e := bootSetup(t)
	applied, err := startBootOnce(e, cfg, s, false)
	if err != nil || !applied || !s.Active["boot"] {
		t.Fatalf("boot run %v %v", applied, err)
	}
	receipt, exists, err := readBootReceipt()
	if err != nil || !exists || receipt.State != "applied" {
		t.Fatalf("receipt %+v %v %v", receipt, exists, err)
	}
	again, err := startBootOnce(e, cfg, s, false)
	if err != nil || again {
		t.Fatalf("duplicate boot applied %v %v", again, err)
	}
	if err := guardBootReplay(e, cfg, s); err != nil || s.Active["boot"] {
		t.Fatalf("replayed boot on restart %v", err)
	}
}
func TestR03BBootIntentCrashRefused(t *testing.T) {
	cfg, s, e := bootSetup(t)
	if err := commitBootReceipt(bootReceipt{Version: 1, BootID: sampleBootID, State: "intended"}); err != nil {
		t.Fatal(err)
	}
	if ok, err := startBootOnce(e, cfg, s, false); ok || err == nil {
		t.Fatalf("unknown boot was executed %v %v", ok, err)
	}
	if err := guardBootReplay(e, cfg, s); err == nil {
		t.Fatal("unknown boot intent ignored during recovery")
	}
}
func TestR03BInvalidBootReceiptAndSymlinkRefused(t *testing.T) {
	_, _, _ = bootSetup(t)
	if err := os.Symlink(filepath.Join(t.TempDir(), "target"), bootReceiptPath()); err != nil {
		t.Fatal(err)
	}
	_, _, err := readBootReceipt()
	if err == nil || !strings.Contains(err.Error(), "unsafe") {
		t.Fatalf("boot symlink accepted %v", err)
	}
}
func TestR03BDryRunDoesNotClaimBoot(t *testing.T) {
	cfg, s, e := bootSetup(t)
	applied, err := startBootOnce(e, cfg, s, true)
	if err != nil || applied || s.Active["boot"] {
		t.Fatalf("dry boot mutated %v %v", applied, err)
	}
	if _, err := os.Stat(bootReceiptPath()); !os.IsNotExist(err) {
		t.Fatalf("dry-run claimed boot marker %v", err)
	}
}
