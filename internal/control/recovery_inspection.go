package control

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

// RecoveryInspection is read-only evidence: inspecting never acknowledges,
// clears, or replays an uncertain privileged operation.
type RecoveryInspection struct {
	Version          int      `json:"version"`
	Unknown          []string `json:"unknown"`
	PendingClaims    []string `json:"pending_claims"`
	JournalEntries   int      `json:"journal_entries"`
	ActiveProfiles   int      `json:"active_profiles"`
	RuntimeMode      string   `json:"runtime_mode"`
	RequiresOperator bool     `json:"requires_operator"`
	CanAutoClear     bool     `json:"can_auto_clear"`
}

// InspectRecovery must not call PendingUnknown: that routine intentionally
// writes immutable unknown receipts, while inspection is strictly read-only.
func InspectRecovery(ctl, runtime string) (RecoveryInspection, error) {
	report := RecoveryInspection{Version: 1, Unknown: []string{}, PendingClaims: []string{}}
	dir := ctl + ".d"
	if err := inspectSpoolDir(dir); err != nil && !errors.Is(err, os.ErrNotExist) {
		return report, err
	}
	if _, err := os.Lstat(dir); err == nil {
		entries, err := os.ReadDir(dir)
		if err != nil {
			return report, err
		}
		for _, entry := range entries {
			if !strings.HasSuffix(entry.Name(), ".work") {
				continue
			}
			id := requestBase(entry.Name())
			if !validRequestID(id) {
				return report, fmt.Errorf("unsafe pending claim %s", entry.Name())
			}
			data, err := readSpoolFile(filepath.Join(dir, entry.Name()), maxRequestBytes)
			if err != nil {
				return report, err
			}
			var req Request
			if err := json.Unmarshal(data, &req); err != nil || req.ID != id || !validCommand(req.Command) {
				return report, fmt.Errorf("corrupt pending claim %s", id)
			}
			ack, present, err := strictAck(ctl, id)
			if err != nil {
				return report, err
			}
			if present && (ack.State == "applied" || ack.State == "failed") {
				continue
			}
			report.PendingClaims = append(report.PendingClaims, id)
			claimed, err := readPhase(ctl, req, "claimed")
			if err != nil {
				return report, err
			}
			applying, err := readPhase(ctl, req, "applying")
			if err != nil {
				return report, err
			}
			if (present && ack.State == "unknown") || applying || !claimed {
				report.Unknown = append(report.Unknown, id)
			}
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		return report, err
	}
	wal := filepath.Join(runtime, "daemon_state_v1.json")
	b, err := readSpoolFile(wal, 1<<20)
	if err == nil {
		var v struct {
			Version  int               `json:"version"`
			Mode     string            `json:"mode"`
			Original map[string]string `json:"original"`
			Active   []string          `json:"active"`
		}
		if err := json.Unmarshal(b, &v); err != nil || v.Version != 1 {
			return report, fmt.Errorf("untrusted daemon recovery journal: %v", err)
		}
		report.JournalEntries = len(v.Original)
		report.ActiveProfiles = len(v.Active)
		report.RuntimeMode = v.Mode
	} else if !errors.Is(err, os.ErrNotExist) {
		return report, err
	}
	// Read the independent stores without ever publishing a new receipt.
	// An unknown ack or applying phase remains blocking without its .work.
	orphans, err := unknownEvidence(ctl)
	if err != nil {
		return report, err
	}
	seen := make(map[string]bool, len(report.Unknown)+len(orphans))
	for _, id := range report.Unknown {
		seen[id] = true
	}
	for _, id := range orphans {
		seen[id] = true
	}
	report.Unknown = report.Unknown[:0]
	for id := range seen {
		report.Unknown = append(report.Unknown, id)
	}
	sort.Strings(report.Unknown)
	sort.Strings(report.PendingClaims)
	report.RequiresOperator = len(report.Unknown) > 0 || report.JournalEntries > 0 || report.RuntimeMode == "recovery_required"
	// No automatic clearance: non-reversible effects may already have happened.
	report.CanAutoClear = false
	return report, nil
}
