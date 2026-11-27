package control

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

// ErrUnknownOutcome means a claimed request may already have executed a root
// operation. Retrying it or automatically restoring active profiles is unsafe.
var ErrUnknownOutcome = errors.New("privileged command outcome unknown; reconciliation required")

const unknownReceiptMessage = "command outcome unknown; privileged effects may have been applied; manual reconciliation required"

type commandPhase struct {
	Version  int    `json:"version"`
	ID       string `json:"id"`
	Command  string `json:"command"`
	Event    string `json:"event,omitempty"`
	Phase    string `json:"phase"`
	Recorded int64  `json:"recorded"`
}

func phaseMarker(ctl string, r Request, phase string) string {
	return filepath.Join(ctl+".phase", r.ID+"."+phase)
}

// writePhase is append-only: claimed and applying are distinct, fsynced,
// immutable records. If applying cannot become durable, no effects may start.
func writePhase(ctl string, r Request, phase string) error {
	if !validRequestID(r.ID) || !validCommand(r.Command) || (phase != "claimed" && phase != "applying") {
		return errors.New("invalid command phase identity")
	}
	dir := ctl + ".phase"
	if err := privateSpoolDir(dir, true); err != nil {
		return err
	}
	p := phaseMarker(ctl, r, phase)
	rec := commandPhase{Version: 1, ID: r.ID, Command: r.Command, Event: r.Event, Phase: phase, Recorded: time.Now().Unix()}
	b, err := json.Marshal(rec)
	if err != nil {
		return err
	}
	tmp := filepath.Join(dir, "."+r.ID+"."+phase+".tmp")
	if err = writeNewFile(tmp, append(b, '\n')); err != nil {
		return err
	}
	if err = publishNewFile(tmp, p); err != nil {
		_ = os.Remove(tmp)
		return fmt.Errorf("durable %s phase publication: %w", phase, err)
	}
	return nil
}

func readPhase(ctl string, r Request, phase string) (bool, error) {
	if !validRequestID(r.ID) {
		return false, errors.New("invalid phase id")
	}
	if err := inspectSpoolDir(ctl + ".phase"); errors.Is(err, os.ErrNotExist) {
		return false, nil
	} else if err != nil {
		return false, err
	}
	b, err := readSpoolFile(phaseMarker(ctl, r, phase), maxRequestBytes)
	if errors.Is(err, os.ErrNotExist) {
		return false, nil
	}
	if err != nil {
		return false, fmt.Errorf("read %s phase: %w", phase, err)
	}
	var rec commandPhase
	if err = json.Unmarshal(b, &rec); err != nil {
		return false, fmt.Errorf("corrupt command phase: %w", err)
	}
	if rec.Version != 1 || rec.ID != r.ID || rec.Command != r.Command || rec.Event != r.Event || rec.Phase != phase || rec.Recorded <= 0 {
		return false, errors.New("command phase identity mismatch")
	}
	return true, nil
}

// MarkApplying is the mandatory write-ahead phase immediately before any
// privileged operation, including settings restoration. It is called only
// after NextRequest has claimed and validated this exact command.
func MarkApplying(ctl string, r Request) error {
	if !validRequestID(r.ID) || !validCommand(r.Command) {
		return errors.New("invalid applying identity")
	}
	b, err := readSpoolFile(filepath.Join(ctl+".d", r.ID+".work"), maxRequestBytes)
	if err != nil {
		return fmt.Errorf("applying claim missing: %w", err)
	}
	var onDisk Request
	if err = json.Unmarshal(b, &onDisk); err != nil || onDisk != r {
		return errors.New("applying claim identity mismatch")
	}
	if r.Deadline > 0 && time.Now().UnixNano() > r.Deadline {
		return errors.New("command deadline expired before privileged execution")
	}
	if _, present, err := strictAck(ctl, r.ID); err != nil {
		return err
	} else if present {
		return errors.New("applying acknowledged command forbidden")
	}
	claimed, err := readPhase(ctl, r, "claimed")
	if err != nil {
		return err
	}
	if !claimed {
		return errors.New("claim phase not durable; root mutation refused")
	}
	applying, err := readPhase(ctl, r, "applying")
	if err != nil {
		return err
	}
	if applying {
		return errors.New("duplicate applying phase refused")
	}
	return writePhase(ctl, r, "applying")
}

// CompleteUnknownRequest publishes an immutable UNKNOWN receipt. It cannot be
// converted into a success or failure automatically; an operator must first
// reconcile the device and its WAL out-of-band.
func CompleteUnknownRequest(ctl string, r Request) error {
	if !validRequestID(r.ID) {
		return errors.New("invalid unknown outcome id")
	}
	dir := ctl + ".ack"
	if err := privateSpoolDir(dir, true); err != nil {
		return err
	}
	a := Ack{ID: r.ID, State: "unknown", Error: unknownReceiptMessage, Completed: time.Now().Unix()}
	if existing, present, err := strictAck(ctl, r.ID); err != nil {
		return err
	} else if present {
		if existing.State == "unknown" && existing.Error == unknownReceiptMessage {
			return nil
		}
		return fmt.Errorf("immutable acknowledgement conflict for %s", r.ID)
	}
	b, err := json.Marshal(a)
	if err != nil {
		return err
	}
	tmp := filepath.Join(dir, "."+r.ID+".tmp")
	if err = writeNewFile(tmp, append(b, '\n')); err != nil {
		return err
	}
	if err = publishNewFile(tmp, filepath.Join(dir, r.ID+".json")); err != nil {
		_ = os.Remove(tmp)
		if existing, present, checkErr := strictAck(ctl, r.ID); checkErr == nil && present && existing.State == "unknown" && existing.Error == unknownReceiptMessage {
			return nil
		}
		return fmt.Errorf("unknown acknowledgement publication uncertain: %w", err)
	}
	return nil
}

// PendingUnknown is a read-safe startup reconciliation barrier. It publishes
// UNKNOWN receipts before returning unresolved claims; it never replays them
// and never removes the .work evidence. The daemon must call this BEFORE
// recoverRuntimeState can reapply formerly active profiles.
func PendingUnknown(ctl string) ([]string, error) {
	dir := ctl + ".d"
	if err := privateSpoolDir(dir, false); errors.Is(err, os.ErrNotExist) {
		return persistUnknownEvidence(ctl)
	} else if err != nil {
		return nil, err
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil, err
	}
	unknown := []string{}
	for _, entry := range entries {
		name := entry.Name()
		if !strings.HasSuffix(name, ".work") {
			continue
		}
		id := requestBase(name)
		if !validRequestID(id) {
			return nil, fmt.Errorf("unsafe interrupted claim filename %q", name)
		}
		b, err := readSpoolFile(filepath.Join(dir, name), maxRequestBytes)
		if err != nil {
			return nil, err
		}
		var r Request
		if err = json.Unmarshal(b, &r); err != nil || r.ID != id || !validCommand(r.Command) {
			return nil, fmt.Errorf("unsafe interrupted claim %s", id)
		}
		ack, present, err := strictAck(ctl, id)
		if err != nil {
			return nil, err
		}
		if present && ack.State != "unknown" {
			continue
		}
		claimed, err := readPhase(ctl, r, "claimed")
		if err != nil {
			return nil, err
		}
		applying, err := readPhase(ctl, r, "applying")
		if err != nil {
			return nil, err
		}
		if present && ack.State == "unknown" || applying || !claimed {
			if err := CompleteUnknownRequest(ctl, r); err != nil {
				return nil, err
			}
			unknown = append(unknown, id)
		}
	}
	// Independent phase and ack evidence must keep blocking even after a
	// .work file is lost, deleted, or not yet durable on the filesystem.
	orphans, err := persistUnknownEvidence(ctl)
	if err != nil {
		return nil, err
	}
	present := make(map[string]bool, len(unknown)+len(orphans))
	for _, id := range unknown {
		present[id] = true
	}
	for _, id := range orphans {
		present[id] = true
	}
	unknown = unknown[:0]
	for id := range present {
		unknown = append(unknown, id)
	}
	sort.Strings(unknown)
	return unknown, nil
}
