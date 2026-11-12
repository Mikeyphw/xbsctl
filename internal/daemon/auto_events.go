package daemon

import (
	"errors"
	"fmt"
	"sort"
	"time"

	"github.com/Mikeyphw/xbsctl/internal/autoevents"
	"github.com/Mikeyphw/xbsctl/internal/control"
	"github.com/Mikeyphw/xbsctl/internal/tuning"
)

// autoPending is volatile queue bookkeeping. The privileged decision and
// claim are durable in R02's spool and engine WAL, never in this cache.
type autoPending struct {
	ID      string
	Command string
	Event   string
}

func autoEnabled(cfg map[string]any) bool {
	hdr, ok := cfg["header"].(map[string]any)
	if !ok {
		return false
	}
	enabled, _ := hdr["automatic_events_enabled"].(bool)
	return enabled
}
func autoInt(cfg map[string]any, key string, fallback int) int {
	hdr, ok := cfg["header"].(map[string]any)
	if !ok {
		return fallback
	}
	switch v := hdr[key].(type) {
	case int:
		return v
	case float64:
		return int(v)
	}
	return fallback
}
func autoOwnedList(s *state) []string {
	names := []string{}
	for name, owned := range s.AutoOwned {
		if owned {
			names = append(names, name)
		}
	}
	sort.Strings(names)
	return names
}
func autoPolicy(cfg map[string]any) autoevents.Policy {
	keep := false
	if ev, err := tuning.Resolve(cfg, "screen_off"); err == nil {
		keep = ev.KeepOnCharge
	}
	return autoevents.Policy{
		LowThreshold:     autoInt(cfg, "low_power_threshold", 20),
		ReleaseThreshold: autoInt(cfg, "low_power_release_threshold", 25),
		StableSamples:    2,
		Delay:            time.Duration(autoInt(cfg, "delay", 3)) * time.Second,
		KeepOnCharge:     keep,
	}
}
func autoReport(s *state, fields map[string]any) bool {
	// Status is deliberately separate from actual applied events. Never
	// record a candidate profile as active merely because it was observed.
	old := fmt.Sprint(s.AutoReport)
	s.AutoReport = fields
	return fmt.Sprint(fields) != old
}

// automaticCycle is called ONLY from the daemon's running loop after R02
// command processing. It queues one transition per iteration and never
// bypasses the durable command applying barrier or resource-lease checks.
// selectAutoDesired resolves lease conflicts in priority order and preserves
// manual profiles. A manually owned domain is never stolen by automation.
func selectAutoDesired(cfg map[string]any, s *state, candidates []string) ([]string, []string, error) {
	manual := map[string]bool{}
	for name, active := range s.Active {
		if active && !s.AutoOwned[name] {
			manual[name] = true
		}
	}
	leased, err := activeResourceLeases(cfg, manual)
	if err != nil {
		return nil, nil, err
	}
	used := map[string]string{}
	for name, resources := range leased {
		for _, r := range resources {
			used[r] = name
		}
	}
	accepted, deferred := []string{}, []string{}
	for _, name := range candidates {
		ev, err := tuning.Resolve(cfg, name)
		if err != nil {
			return nil, nil, err
		}
		conflict := ""
		if s.Active[name] && !s.AutoOwned[name] {
			conflict = "manually active"
		}
		for _, resource := range resourceLeases(ev) {
			if prior := used[resource]; prior != "" {
				conflict = "resource " + resource + " owned by " + prior
				break
			}
		}
		if conflict != "" {
			deferred = append(deferred, name+": "+conflict)
			continue
		}
		accepted = append(accepted, name)
		for _, resource := range resourceLeases(ev) {
			used[resource] = name
		}
	}
	return accepted, deferred, nil
}

// automaticCycle runs after control processing. It never issues root commands,
// marks profiles active, or stops a profile it does not own.
func automaticCycle(engine *tuning.Engine, cfg map[string]any, s *state) (bool, error) {
	if s.AutoOwned == nil {
		s.AutoOwned = map[string]bool{}
	}
	if s.Mode != "normal" {
		s.AutoPlanner = nil
		return false, nil
	}
	ctl := ctlPath(cfg)
	if s.AutoPending != nil {
		ack, present := control.ReadAck(ctl, s.AutoPending.ID)
		if !present {
			return false, nil
		}
		pending := s.AutoPending
		s.AutoPending = nil
		if ack.State != "applied" {
			s.AutoBlocked = true
			return true, fmt.Errorf("automatic %s %s rejected: %s", pending.Command, pending.Event, ack.Error)
		}
	}
	if s.AutoBlocked {
		return false, nil
	}
	enabled := autoEnabled(cfg)
	var candidates []string
	stable := false
	observation := map[string]any{"enabled": enabled, "source": "disabled"}
	if enabled {
		policy := autoPolicy(cfg)
		if err := policy.Validate(); err != nil {
			return false, err
		}
		if s.AutoPlanner == nil {
			planner, err := autoevents.New(policy)
			if err != nil {
				return false, err
			}
			s.AutoPlanner = planner
		}
		observed, err := autoevents.Observe(engine.R)
		if err != nil {
			s.AutoPlanner = nil // an unknown sample cannot bridge two stable epochs
			return autoReport(s, map[string]any{"enabled": true, "state": "unknown", "error": err.Error()}), nil
		}
		decision, err := s.AutoPlanner.Next(observed, time.Now())
		if err != nil {
			s.AutoPlanner = nil
			return autoReport(s, map[string]any{"enabled": true, "state": "unknown", "error": err.Error()}), nil
		}
		stable = decision.Stable
		if stable {
			candidates = decision.Events
		}
		observation = map[string]any{"enabled": true, "source": observed.Source, "charging": observed.Charging, "screen_off": observed.ScreenOff, "battery_percent": observed.BatteryPercent, "stable": stable, "candidates": candidates}
	} else {
		s.AutoPlanner = nil
	}
	if enabled && !stable {
		return autoReport(s, observation), nil
	}
	selected, deferred, err := selectAutoDesired(cfg, s, candidates)
	if err != nil {
		return false, err
	}
	s.AutoDesired = append([]string{}, selected...)
	observation["desired"] = selected
	observation["deferred"] = deferred
	changed := autoReport(s, observation)
	wanted := map[string]bool{}
	for _, name := range selected {
		wanted[name] = true
	}
	// Stop lower-priority or no-longer-desired automatic profiles first.
	for _, name := range []string{"low_power", "screen_off", "charging"} {
		if s.AutoOwned[name] && s.Active[name] && !wanted[name] {
			req, err := control.QueueAutomaticRequest(ctl, "stop", name)
			if err != nil {
				return changed, err
			}
			s.AutoPending = &autoPending{ID: req.ID, Command: "stop", Event: name}
			return true, nil
		}
	}
	for _, name := range selected {
		if !s.Active[name] {
			// A previously active but soon-to-be-stopped automatic lease can
			// conflict. Stop it first and retry next cycle, not as a failed start.
			if err := checkStartOwnership(cfg, s.Active, name); err != nil {
				return changed, nil
			}
			req, err := control.QueueAutomaticRequest(ctl, "start", name)
			if err != nil {
				return changed, err
			}
			s.AutoPending = &autoPending{ID: req.ID, Command: "start", Event: name}
			return true, nil
		}
	}
	return changed, nil
}

// validateAutoRequest refuses a stale or forged automatic stop before it can
// restore the effects belonging to a manually controlled profile.
func validateAutoRequest(cfg map[string]any, s *state, req control.Request) error {
	if req.Origin != "automatic" {
		return nil
	}
	if req.Command == "start" {
		if !autoEnabled(cfg) {
			return errors.New("automatic start after automation disabled")
		}
		if s.Active[req.Event] && !s.AutoOwned[req.Event] {
			return errors.New("automatic start cannot claim a manually owned profile")
		}
	}
	if req.Command == "stop" && !s.AutoOwned[req.Event] {
		return errors.New("automatic stop has no owned profile")
	}
	return nil
}
