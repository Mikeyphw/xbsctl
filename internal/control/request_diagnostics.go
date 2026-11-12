package control

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
)

// RequestDiagnostic is a read-only observation of one durable request.
// Missing evidence is never proof that privileged work did not execute.
type RequestDiagnostic struct {
	Version int    `json:"version"`
	ID      string `json:"id"`
	State   string `json:"state"` // queued | claimed | applied | failed | unknown | missing
	Origin  string `json:"origin,omitempty"`
	Command string `json:"command,omitempty"`
	Event   string `json:"event,omitempty"`
}

func InspectRequest(ctl, id string) (RequestDiagnostic, error) {
	out := RequestDiagnostic{Version: 1, ID: id, State: "missing"}
	if !validRequestID(id) {
		return out, errors.New("invalid request inspection identity")
	}
	ack, present, err := strictAck(ctl, id)
	if err != nil {
		return out, fmt.Errorf("read acknowledgement: %w", err)
	}
	if present {
		out.State = ack.State
		return out, nil
	}
	dir := ctl + ".d"
	if err := inspectSpoolDir(dir); errors.Is(err, os.ErrNotExist) {
		return out, nil
	} else if err != nil {
		return out, err
	}
	for _, suffix := range []string{".work", ".json"} {
		b, err := readSpoolFile(filepath.Join(dir, id+suffix), maxRequestBytes)
		if errors.Is(err, os.ErrNotExist) {
			continue
		}
		if err != nil {
			return out, fmt.Errorf("unsafe inspection of %s: %w", suffix, err)
		}
		var r Request
		if err := json.Unmarshal(b, &r); err != nil || r.ID != id || !validCommand(r.Command) {
			return out, errors.New("invalid queued request evidence")
		}
		out.Origin, out.Command, out.Event = r.Origin, r.Command, r.Event
		if suffix == ".work" {
			out.State = "claimed"
		} else {
			out.State = "queued"
		}
		// A concurrent claim/ack may race this snapshot. Never use it to replay.
		return out, nil
	}
	return out, nil
}
