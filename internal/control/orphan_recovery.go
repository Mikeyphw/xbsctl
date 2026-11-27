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

// unknownEvidence scans the independent, durable phase and acknowledgement
// stores. A missing .work claim must never erase evidence that execution may
// have started. This scan is read-only; it is safe for the recovery UI.
//
// A terminal applied/failed acknowledgement is authoritative. An applying
// phase lacking a terminal acknowledgement, or any unknown acknowledgement,
// requires operator reconciliation even if the .work path is missing.
func unknownEvidence(ctl string) ([]string, error) {
	pending := make(map[string]bool)
	phaseDir := ctl + ".phase"
	if err := inspectSpoolDir(phaseDir); err != nil {
		if !errors.Is(err, os.ErrNotExist) {
			return nil, fmt.Errorf("inspect phase directory: %w", err)
		}
	} else {
		entries, err := os.ReadDir(phaseDir)
		if err != nil {
			return nil, err
		}
		for _, entry := range entries {
			name := entry.Name()
			if !strings.HasSuffix(name, ".applying") {
				continue
			}
			id := strings.TrimSuffix(name, ".applying")
			if !validRequestID(id) {
				return nil, fmt.Errorf("invalid applying phase filename %q", name)
			}
			b, err := readSpoolFile(filepath.Join(phaseDir, name), maxRequestBytes)
			if err != nil {
				return nil, fmt.Errorf("unsafe applying phase %s: %w", id, err)
			}
			var phase commandPhase
			if err := json.Unmarshal(b, &phase); err != nil {
				return nil, fmt.Errorf("corrupt applying phase %s: %w", id, err)
			}
			if phase.Version != 1 || phase.ID != id || phase.Phase != "applying" ||
				!validCommand(phase.Command) || len(phase.Event) > 128 || strings.ContainsAny(phase.Event, "\x00\r\n\t ") || phase.Recorded <= 0 {
				return nil, fmt.Errorf("invalid applying phase identity %s", id)
			}
			ack, found, err := strictAck(ctl, id)
			if err != nil {
				return nil, err
			}
			if !found || ack.State == "unknown" {
				pending[id] = true
			}
		}
	}
	ackDir := ctl + ".ack"
	if err := inspectSpoolDir(ackDir); err != nil {
		if !errors.Is(err, os.ErrNotExist) {
			return nil, fmt.Errorf("inspect acknowledgement directory: %w", err)
		}
	} else {
		entries, err := os.ReadDir(ackDir)
		if err != nil {
			return nil, err
		}
		for _, entry := range entries {
			name := entry.Name()
			if !strings.HasSuffix(name, ".json") {
				continue
			}
			id := strings.TrimSuffix(name, ".json")
			if !validRequestID(id) {
				return nil, fmt.Errorf("invalid acknowledgement filename %q", name)
			}
			// Includes no-follow and strict ID/state/schema checks.
			ack, found, err := strictAck(ctl, id)
			if err != nil {
				return nil, err
			}
			if !found {
				return nil, fmt.Errorf("acknowledgement disappeared during inspection %s", id)
			}
			if ack.State == "unknown" {
				pending[id] = true
			}
		}
	}
	ids := make([]string, 0, len(pending))
	for id := range pending {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	return ids, nil
}

// persistUnknownEvidence preserves an independent, immutable unknown receipt
// for every unresolved phase, including when a claim file was lost. No device
// mutation, command replay, or automatic clearance occurs here.
func persistUnknownEvidence(ctl string) ([]string, error) {
	ids, err := unknownEvidence(ctl)
	if err != nil {
		return nil, err
	}
	for _, id := range ids {
		if err := CompleteUnknownRequest(ctl, Request{ID: id}); err != nil {
			return nil, fmt.Errorf("retain orphaned unknown outcome %s: %w", id, err)
		}
	}
	return ids, nil
}
