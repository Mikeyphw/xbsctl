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
	return autoevents.Policy{
		LowThreshold:     autoInt(cfg, "low_power_threshold", 20),
		ReleaseThreshold: autoInt(cfg, "low_power_release_threshold", 25),
		StableSamples:    2,
		Delay:            time.Duration(autoInt(cfg, "delay", 3)) * time.Second,
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
		ack, ok := control.ReadAck(ctl, s.AutoPending.ID)
		if !ok {
			return false, nil
		}
		pending := s.AutoPending
		s.AutoPending = nil
		if ack.State != "applied" {
			// A failed automatic command is never retried in a tight loop.
			// Keep the original state and require a deliberate config reload.
			s.AutoBlocked = true
			return true, fmt.Errorf("automatic %s %s rejected: %s", pending.Command, pending.Event, ack.Error)
		}
	}
	if s.AutoBlocked {
		return false, nil
	}
	enabled := autoEnabled(cfg)
	desired := ""
	stable := false
	observation := map[string]any{"enabled": enabled, "source": "disabled"}
	if enabled {
		p := autoPolicy(cfg)
		if e := p.Validate(); e != nil {
			return false, e
		}
		if s.AutoPlanner == nil {
			plan, e := autoevents.New(p)
			if e != nil {
				return false, e
			}
			s.AutoPlanner = plan
		}
		observed, e := autoevents.Observe(engine.R)
		if e != nil {
			return autoReport(s, map[string]any{"enabled": true, "error": e.Error(), "state": "unknown"}), nil
		}
		next, e := s.AutoPlanner.Next(observed, time.Now())
		if e != nil {
			return autoReport(s, map[string]any{"enabled": true, "error": e.Error(), "state": "unknown"}), nil
		}
		if next.Stable {
			desired = next.Event
			stable = true
		}
		observation = map[string]any{"enabled": true, "source": observed.Source, "charging": observed.Charging, "screen_off": observed.ScreenOff, "battery_percent": observed.BatteryPercent, "stable": stable, "desired": desired}
	} else {
		s.AutoPlanner = nil
	}
	changed := autoReport(s, observation)
	if enabled && !stable {
		return changed, nil
	}
	// A durable automatic start must be the only basis for a later stop.
	// Manual starts remain manually owned, even on matching sensor readings.
	for _, name := range []string{"charging", "screen_off", "low_power"} {
		if s.AutoOwned[name] && s.Active[name] && name != desired {
			req, e := control.QueueAutomaticRequest(ctl, "stop", name)
			if e != nil {
				return changed, e
			}
			s.AutoPending = &autoPending{ID: req.ID, Command: "stop", Event: name}
			return true, nil
		}
	}
	if desired != "" && !s.Active[desired] {
		req, e := control.QueueAutomaticRequest(ctl, "start", desired)
		if e != nil {
			return changed, e
		}
		s.AutoPending = &autoPending{ID: req.ID, Command: "start", Event: desired}
		return true, nil
	}
	return changed, nil
}

// validateAutoRequest refuses a stale or forged automatic stop before it can
// restore the effects belonging to a manually controlled profile.
func validateAutoRequest(cfg map[string]any, s *state, req control.Request) error {
	if req.Origin != "automatic" {
		return nil
	}
	if req.Command == "start" && !autoEnabled(cfg) {
		return errors.New("automatic start after automation disabled")
	}
	if req.Command == "stop" && !s.AutoOwned[req.Event] {
		return errors.New("automatic stop has no owned profile")
	}
	return nil
}
