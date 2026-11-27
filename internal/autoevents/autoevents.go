// Package autoevents turns read-only Android power observations into
// deterministic, debounced event desires. It cannot perform root mutations.
package autoevents

import (
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"
)

// Reader is deliberately compatible with tuning.Root; no new shell runner is used.
type Reader interface {
	Read(path string) (string, error)
	Run(name string, args ...string) (string, error)
}

type Observation struct {
	Charging       bool   `json:"charging"`
	ScreenOff      bool   `json:"screen_off"`
	BatteryPercent int    `json:"battery_percent"`
	Source         string `json:"source"`
}

// Observe refuses missing or ambiguous sensor results. It never infers a
// transition from an error; errors leave the daemon's last applied state alone.
func Observe(r Reader) (Observation, error) {
	status, err := r.Read("/sys/class/power_supply/battery/status")
	if err != nil {
		return Observation{}, fmt.Errorf("battery status: %w", err)
	}
	charging, err := parseCharging(status)
	if err != nil {
		return Observation{}, err
	}
	rawLevel, err := r.Read("/sys/class/power_supply/battery/capacity")
	if err != nil {
		return Observation{}, fmt.Errorf("battery capacity: %w", err)
	}
	level, err := parsePercent(rawLevel)
	if err != nil {
		return Observation{}, err
	}
	display, err := r.Run("dumpsys", "power")
	if err != nil {
		return Observation{}, fmt.Errorf("screen state: %w", err)
	}
	off, err := parseWakefulness(display)
	if err != nil {
		return Observation{}, err
	}
	return Observation{Charging: charging, ScreenOff: off, BatteryPercent: level, Source: "battery-sysfs+dumpsys-power"}, nil
}

func parseCharging(s string) (bool, error) {
	switch strings.ToLower(strings.TrimSpace(s)) {
	case "charging", "full":
		return true, nil
	case "discharging", "not charging":
		return false, nil
	default:
		return false, fmt.Errorf("unknown battery charging state %q", strings.TrimSpace(s))
	}
}
func parsePercent(s string) (int, error) {
	n, e := strconv.Atoi(strings.TrimSpace(s))
	if e != nil || n < 0 || n > 100 {
		return 0, fmt.Errorf("invalid battery capacity %q", strings.TrimSpace(s))
	}
	return n, nil
}
func parseWakefulness(s string) (bool, error) {
	seen := false
	var off bool
	for _, line := range strings.Split(s, "\n") {
		line = strings.TrimSpace(line)
		if !strings.HasPrefix(line, "mWakefulness=") {
			continue
		}
		if seen {
			return false, errors.New("ambiguous duplicate wakefulness result")
		}
		seen = true
		switch strings.TrimSpace(strings.TrimPrefix(line, "mWakefulness=")) {
		case "Asleep":
			off = true
		case "Awake":
			off = false
		default:
			return false, fmt.Errorf("unrecognized wakefulness %q", line)
		}
	}
	if !seen {
		return false, errors.New("missing explicit wakefulness state")
	}
	return off, nil
}

type Policy struct {
	LowThreshold     int           `json:"low_threshold"`
	ReleaseThreshold int           `json:"release_threshold"`
	Delay            time.Duration `json:"-"`
	StableSamples    int           `json:"stable_samples"`
	KeepOnCharge     bool          `json:"keep_on_charge"`
}

func (p Policy) Validate() error {
	if p.LowThreshold < 1 || p.LowThreshold > 95 || p.ReleaseThreshold <= p.LowThreshold || p.ReleaseThreshold > 100 {
		return errors.New("invalid low-power hysteresis bounds")
	}
	if p.Delay < 0 || p.Delay > time.Hour {
		return errors.New("invalid automatic event delay")
	}
	if p.StableSamples < 1 || p.StableSamples > 100 {
		return errors.New("invalid observation stability threshold")
	}
	return nil
}

// Desired is a priority-ordered, single-profile intention. The daemon remains
// responsible for resource leases, execution, WAL and acknowledgement receipts.
type Desired struct {
	Event       string      `json:"event"`
	Events      []string    `json:"events"`
	Observation Observation `json:"observation"`
	Stable      bool        `json:"stable"`
}

type Planner struct {
	policy      Policy
	prev        Observation
	hasPrev     bool
	stableCount int
	since       time.Time
	low         bool
}

func New(p Policy) (*Planner, error) {
	if err := p.Validate(); err != nil {
		return nil, err
	}
	return &Planner{policy: p}, nil
}

// Next is clock-injected for deterministic tests. Invalid/old clocks cannot
// shorten debounce. The first sample never immediately causes root effects.
func (p *Planner) Next(obs Observation, at time.Time) (Desired, error) {
	if obs.BatteryPercent < 0 || obs.BatteryPercent > 100 || obs.Source == "" {
		return Desired{}, errors.New("invalid or incomplete observation")
	}
	if at.IsZero() {
		return Desired{}, errors.New("missing sample time")
	}
	same := p.hasPrev && p.prev.Charging == obs.Charging && p.prev.ScreenOff == obs.ScreenOff && p.prev.BatteryPercent == obs.BatteryPercent
	// Battery percentage changes often; stability should depend only on the
	// candidate low/high band rather than requiring an identical integer.
	if p.hasPrev {
		same = p.prev.Charging == obs.Charging && p.prev.ScreenOff == obs.ScreenOff && (p.prev.BatteryPercent <= p.policy.LowThreshold) == (obs.BatteryPercent <= p.policy.LowThreshold) && (p.prev.BatteryPercent >= p.policy.ReleaseThreshold) == (obs.BatteryPercent >= p.policy.ReleaseThreshold)
	}
	if !same {
		p.stableCount = 1
		p.since = at
	} else {
		p.stableCount++
	}
	if at.Before(p.since) {
		return Desired{}, errors.New("nonmonotonic observation time")
	}
	p.prev = obs
	p.hasPrev = true
	if p.stableCount < p.policy.StableSamples || at.Sub(p.since) < p.policy.Delay {
		return Desired{Observation: obs}, nil
	}
	if obs.BatteryPercent <= p.policy.LowThreshold {
		p.low = true
	} else if obs.BatteryPercent >= p.policy.ReleaseThreshold {
		p.low = false
	}
	// The order is priority, NOT a request to mutate overlapping resources.
	// The daemon applies the resource-lease gate to this ordered candidate set.
	// Charging suppresses low-power; screen_off can remain during charging
	// only if the resolved screen_off event explicitly sets keep_on_charge.
	events := []string{}
	if obs.Charging {
		events = append(events, "charging")
	}
	if obs.ScreenOff && (!obs.Charging || p.policy.KeepOnCharge) {
		events = append(events, "screen_off")
	}
	if !obs.Charging && p.low {
		events = append(events, "low_power")
	}
	event := ""
	if len(events) > 0 {
		event = events[0]
	}
	return Desired{Event: event, Events: events, Observation: obs, Stable: true}, nil
}
