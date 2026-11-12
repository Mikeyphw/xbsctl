package daemon

import (
	"fmt"
	"github.com/Mikeyphw/xbsctl/internal/tuning"
	"sort"
)

// resourceLeases is intentionally conservative: each named domain covers all
// values within that effect family. Separate packages or CPUs do not share a
// domain until more granular recovery and PID-identity gates are qualified.
// A lease may be held by only one active profile.
func resourceLeases(ev tuning.Event) []string {
	leased := map[string]bool{}
	if ev.HandleCores != "" && ev.HandleCores != "false" {
		leased["cpu-governor"] = true
	}
	if ev.DisableCores != "" && ev.DisableCores != "false" {
		leased["cpu-online"] = true
	}
	if ev.HandleApps != "" && ev.HandleApps != "false" {
		leased["app-packages"] = true
		if ev.HandleApps == "nice" {
			leased["process-nice"] = true
		}
	}
	if ev.HandleGMS != "" && ev.HandleGMS != "false" {
		leased["gms"] = true
		if ev.HandleGMS == "nice" {
			leased["process-nice"] = true
		}
	}
	if ev.HandleProc {
		leased["process-nice"] = true
	}
	if ev.LowRAM {
		leased["low-ram"] = true
	}
	if ev.Doze != "" && ev.Doze != "false" {
		leased["doze"] = true
	}
	if ev.KillWiFi {
		leased["wifi"] = true
	}
	result := make([]string, 0, len(leased))
	for k := range leased {
		result = append(result, k)
	}
	sort.Strings(result)
	return result
}

// hasPrivilegedEffects remains the fail-closed runtime classification.
func hasPrivilegedEffects(ev tuning.Event) bool { return len(resourceLeases(ev)) > 0 }

// All profiles are checked before any privilege-changing command. Two profiles
// may coexist only when their claimed effect domains are disjoint.
func activeResourceLeases(cfg map[string]any, active map[string]bool) (map[string][]string, error) {
	owners := map[string]string{}
	result := map[string][]string{}
	names := make([]string, 0, len(active))
	for name, yes := range active {
		if yes {
			names = append(names, name)
		}
	}
	sort.Strings(names)
	for _, name := range names {
		ev, err := tuning.Resolve(cfg, name)
		if err != nil {
			return nil, fmt.Errorf("resolve active profile %q: %w", name, err)
		}
		resources := resourceLeases(ev)
		for _, resource := range resources {
			if prev, exists := owners[resource]; exists {
				return nil, fmt.Errorf("conflicting privileged owners %q and %q for resource %q", prev, name, resource)
			}
			owners[resource] = name
		}
		if len(resources) > 0 {
			result[name] = resources
		}
	}
	return result, nil
}

// The legacy helper is kept for callers/tests that only need to detect whether
// any mutating owner exists. The returned owner is the first in stable order;
// it must never be used to restore all active owners' independent resources.
func exclusiveActiveOwner(cfg map[string]any, active map[string]bool) (string, error) {
	owners, err := activeResourceLeases(cfg, active)
	if err != nil {
		return "", err
	}
	names := make([]string, 0, len(owners))
	for name := range owners {
		names = append(names, name)
	}
	sort.Strings(names)
	if len(names) == 0 {
		return "", nil
	}
	return names[0], nil
}

func checkStartOwnership(cfg map[string]any, active map[string]bool, candidate string) error {
	proposed := make(map[string]bool, len(active)+1)
	for name, yes := range active {
		if yes {
			proposed[name] = true
		}
	}
	proposed[candidate] = true
	_, err := activeResourceLeases(cfg, proposed)
	return err
}

// The durable lease metadata is descriptive and prevents recovering a runtime
// whose active plan has drifted since the last source-bound journal commit.
func declaredLeases(cfg map[string]any, active map[string]bool) map[string][]string {
	out := map[string][]string{}
	for name, yes := range active {
		if !yes {
			continue
		}
		ev, err := tuning.Resolve(cfg, name)
		if err != nil {
			continue
		} // reapply/recovery will refuse unresolved profiles
		if r := resourceLeases(ev); len(r) > 0 {
			out[name] = r
		}
	}
	return out
}
