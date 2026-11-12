package daemon

import (
	"fmt"
	"sort"

	"github.com/Mikeyphw/xbsctl/internal/tuning"
)

// R01C uses an explicit exclusive privileged-effect lease. The tuning engine
// currently stores one global reversible snapshot, not per-resource owner
// reference counts; allowing two mutators would make a stop of either unsafe.
// Benign policy-only events can still coexist. A future lease-based phase may
// safely relax this restriction after its own crash/reload acceptance gates.
func hasPrivilegedEffects(ev tuning.Event) bool {
	return (ev.HandleCores != "" && ev.HandleCores != "false") ||
		(ev.DisableCores != "" && ev.DisableCores != "false") ||
		(ev.HandleApps != "" && ev.HandleApps != "false") ||
		(ev.HandleGMS != "" && ev.HandleGMS != "false") ||
		ev.HandleProc || ev.LowRAM ||
		(ev.Doze != "" && ev.Doze != "false") || ev.KillWiFi
}

// exclusiveActiveOwner validates the complete desired active set, not just a
// candidate. This is called before start, resume/recovery, and reload.
func exclusiveActiveOwner(cfg map[string]any, active map[string]bool) (string, error) {
	names := make([]string, 0, len(active))
	for name, enabled := range active {
		if enabled {
			names = append(names, name)
		}
	}
	sort.Strings(names)
	owner := ""
	for _, name := range names {
		ev, err := tuning.Resolve(cfg, name)
		if err != nil {
			return "", fmt.Errorf("resolve active profile %q: %w", name, err)
		}
		if !hasPrivilegedEffects(ev) {
			continue
		}
		if owner != "" {
			return "", fmt.Errorf("conflicting privileged owners %q and %q: the global effect snapshot permits only one active mutating profile", owner, name)
		}
		owner = name
	}
	return owner, nil
}

func checkStartOwnership(cfg map[string]any, active map[string]bool, candidate string) error {
	proposed := make(map[string]bool, len(active)+1)
	for name, enabled := range active {
		if enabled {
			proposed[name] = true
		}
	}
	proposed[candidate] = true
	_, err := exclusiveActiveOwner(cfg, proposed)
	return err
}
