package daemon

import (
	"errors"
	"fmt"
	"time"

	"github.com/Mikeyphw/xbsctl/internal/control"
)

// A pending automatic command is never replayed after a missing receipt or
// an expired watch window: the state becomes operator-blocked instead.
const autoPendingWatchdog = 10 * time.Minute

// checkAutoPending observes queue evidence but never executes, cancels, or
// acknowledges a command. `changed` asks the daemon to persist a migrated
// legacy timestamp. A nil error and done=true means an applied receipt exists.
func checkAutoPending(ctl string, p *autoPending, at time.Time) (done, changed bool, err error) {
	if p == nil {
		return false, false, errors.New("missing pending automatic identity")
	}
	if at.IsZero() {
		return false, false, errors.New("missing monotonic observation time")
	}
	if p.Command != "start" && p.Command != "stop" {
		return false, false, errors.New("invalid pending command")
	}
	if p.Event != "charging" && p.Event != "screen_off" && p.Event != "low_power" {
		return false, false, errors.New("invalid pending automatic event")
	}
	d, err := control.InspectRequest(ctl, p.ID)
	if err != nil {
		return false, false, fmt.Errorf("read-only pending inspection: %w", err)
	}
	switch d.State {
	case "applied":
		return true, true, nil
	case "failed", "unknown":
		return false, true, fmt.Errorf("automatic %s %s receipt is %s; reconciliation required", p.Command, p.Event, d.State)
	case "queued", "claimed":
		if d.Origin != "automatic" || d.Command != p.Command || d.Event != p.Event {
			return false, true, errors.New("pending request identity differs from durable queue; refusing replay")
		}
		if p.QueuedAt == 0 { // migrate an R03B in-flight record; do not replay
			p.QueuedAt = at.Unix()
			return false, true, nil
		}
		if p.QueuedAt > at.Unix()+2 || at.Unix()-p.QueuedAt > int64(autoPendingWatchdog/time.Second) {
			return false, true, fmt.Errorf("automatic %s %s exceeded pending watchdog; no automatic retry", p.Command, p.Event)
		}
		return false, false, nil
	case "missing":
		return false, true, fmt.Errorf("automatic %s %s missing queue and receipt evidence; outcome unknown", p.Command, p.Event)
	default:
		return false, true, fmt.Errorf("unrecognized automatic queue state %q", d.State)
	}
}
