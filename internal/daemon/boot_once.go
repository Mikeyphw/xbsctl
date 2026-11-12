package daemon

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/Mikeyphw/xbsctl/internal/config"
	"github.com/Mikeyphw/xbsctl/internal/tuning"
)

// A per-physical-boot intent is committed before the first boot effect.
// A daemon crash in this window may have changed privileged state: we never
// guess that a boot event can be retried, even after recovering the WAL.
type bootReceipt struct {
	Version int    `json:"version"`
	BootID  string `json:"boot_id"`
	State   string `json:"state"` // intended | applied
}

func configuredBoot(cfg map[string]any) bool {
	h, ok := cfg["header"].(map[string]any)
	if !ok {
		return false
	}
	enabled, _ := h["automatic_boot_enabled"].(bool)
	return enabled && autoEnabled(cfg)
}
func bootReceiptPath() string {
	return filepath.Join(config.ResolvePaths().Runtime, "boot_once_v1.json")
}

func validBootID(id string) bool {
	if len(id) != 36 {
		return false
	}
	for i, c := range id {
		if i == 8 || i == 13 || i == 18 || i == 23 {
			if c != '-' {
				return false
			}
		} else if !(c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F') {
			return false
		}
	}
	return true
}
func currentBootID(engine *tuning.Engine) (string, error) {
	v, err := engine.R.Read("/proc/sys/kernel/random/boot_id")
	if err != nil {
		return "", fmt.Errorf("read device boot identity: %w", err)
	}
	id := strings.TrimSpace(v)
	if !validBootID(id) {
		return "", fmt.Errorf("invalid device boot identity %q", id)
	}
	return strings.ToLower(id), nil
}

func readBootReceipt() (bootReceipt, bool, error) {
	path := bootReceiptPath()
	info, err := os.Lstat(path)
	if errors.Is(err, os.ErrNotExist) {
		return bootReceipt{}, false, nil
	}
	if err != nil {
		return bootReceipt{}, false, err
	}
	if !info.Mode().IsRegular() || info.Mode().Perm() != 0600 || info.Size() > 1024 || info.Size() == 0 {
		return bootReceipt{}, false, fmt.Errorf("unsafe boot receipt type, size or permissions")
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return bootReceipt{}, false, err
	}
	var receipt bootReceipt
	if err := json.Unmarshal(data, &receipt); err != nil {
		return bootReceipt{}, false, err
	}
	if receipt.Version != 1 || !validBootID(receipt.BootID) || (receipt.State != "intended" && receipt.State != "applied") {
		return bootReceipt{}, false, fmt.Errorf("invalid boot receipt content")
	}
	return receipt, true, nil
}
func commitBootReceipt(b bootReceipt) error {
	if !validBootID(b.BootID) || b.Version != 1 || (b.State != "intended" && b.State != "applied") {
		return errors.New("invalid boot receipt")
	}
	if err := atomicJSON(bootReceiptPath(), b, 0600); err != nil {
		return err
	}
	return syncRuntimeDirectory()
}

// startBootOnce invokes boot only if the explicit opt-ins are true. It is
// called after the R02 unknown-outcome barrier and WAL recovery, but before
// normal automatic observation. It is intentionally not called in --once mode.
func startBootOnce(engine *tuning.Engine, cfg map[string]any, s *state, dry bool) (bool, error) {
	if !configuredBoot(cfg) || s.Mode != "normal" {
		return false, nil
	}
	id, err := currentBootID(engine)
	if err != nil {
		return false, err
	}
	last, exists, err := readBootReceipt()
	if err != nil {
		return false, err
	}
	if exists && last.BootID == id {
		if last.State == "applied" {
			return false, nil
		}
		return false, fmt.Errorf("boot action outcome unknown on this device boot; manual recovery required")
	}
	if dry {
		// A dry-run may preview later through the CLI but never claims that
		// boot actions were performed or consumes the once-per-boot receipt.
		return false, nil
	}
	if s.Active["boot"] {
		return false, errors.New("unowned or ambiguous boot profile in runtime state")
	}
	if err := checkStartOwnership(cfg, s.Active, "boot"); err != nil {
		return false, err
	}
	ev, err := tuning.Resolve(cfg, "boot")
	if err != nil {
		return false, err
	}
	if err := commitBootReceipt(bootReceipt{Version: 1, BootID: id, State: "intended"}); err != nil {
		return false, err
	}
	result, err := engine.Apply(ev, dry)
	if err != nil || !result.Applied {
		s.Mode = "recovery_required"
		if err == nil {
			err = errors.New("boot apply did not commit")
		}
		if persistErr := persistRuntimeState(engine, cfg, s); persistErr != nil {
			return false, errors.Join(err, persistErr)
		}
		return false, fmt.Errorf("boot execution outcome requires reconciliation: %w", err)
	}
	s.Active["boot"] = true
	s.EffectResults["boot"] = tuning.MarshalResult(result)
	s.LastAppliedEvent = "boot"
	s.LastAppliedTime = now()
	s.QuitRequested = ev.Quit
	if err := persistRuntimeState(engine, cfg, s); err != nil {
		s.Mode = "recovery_required"
		return false, fmt.Errorf("boot state WAL uncertain: %w", err)
	}
	if err := commitBootReceipt(bootReceipt{Version: 1, BootID: id, State: "applied"}); err != nil {
		s.Mode = "recovery_required"
		return false, fmt.Errorf("boot completion receipt uncertain: %w", err)
	}
	return true, nil
}

// During a daemon restart, pre-boot root originals are restored by the
// existing WAL recovery. Do not reapply a boot-once profile afterwards.
// Never dismiss a committed-but-incomplete boot intent as successful.
func guardBootReplay(engine *tuning.Engine, cfg map[string]any, s *state) error {
	if !configuredBoot(cfg) {
		return nil
	}
	id, err := currentBootID(engine)
	if err != nil {
		return err
	}
	last, exists, err := readBootReceipt()
	if err != nil {
		return err
	}
	if !exists {
		if s.Active["boot"] {
			return errors.New("boot active without durable boot receipt")
		}
		return nil
	}
	if last.BootID == id && last.State == "intended" {
		return errors.New("unresolved boot intent forbids automatic reactivation")
	}
	if s.Active["boot"] {
		delete(s.Active, "boot")
	}
	return nil
}

// quit-on-boot must NOT abandon privileged effects. Restore all owned effects,
// journal the empty active set, and publish final status before exiting.
func finishBootQuit(engine *tuning.Engine, cfg map[string]any, s *state) error {
	if !s.QuitRequested {
		return nil
	}
	if err := restoreActive(engine, cfg, s); err != nil {
		return fmt.Errorf("quit-on-boot restore: %w", err)
	}
	s.Active = map[string]bool{}
	s.AutoOwned = map[string]bool{}
	s.AutoDesired = nil
	s.QuitRequested = false
	if err := persistRuntimeState(engine, cfg, s); err != nil {
		return fmt.Errorf("quit-on-boot WAL: %w", err)
	}
	if err := writeStatus(cfg, s); err != nil {
		return fmt.Errorf("quit-on-boot status: %w", err)
	}
	return nil
}
