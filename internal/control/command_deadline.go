package control

import (
	"encoding/json"
	"errors"
	"fmt"
	"path/filepath"
)

// CancelQueuedRequest is only safe while the source is still a .json item.
// A race won by NextRequest leaves no .json and is refused without ack.
func CancelQueuedRequest(ctl, id string) error {
	if !validRequestID(id) {
		return errors.New("invalid cancellation identity")
	}
	dir := ctl + ".d"
	if err := privateSpoolDir(dir, false); err != nil {
		return err
	}
	src := filepath.Join(dir, id+".json")
	data, err := readSpoolFile(src, maxRequestBytes)
	if err != nil {
		return fmt.Errorf("request already claimed or unsafe: %w", err)
	}
	var req Request
	if err := json.Unmarshal(data, &req); err != nil || req.ID != id || !validCommand(req.Command) {
		return errors.New("invalid queued request identity")
	}
	if _, present, err := strictAck(ctl, id); err != nil {
		return err
	} else if present {
		return errors.New("request already acknowledged")
	}
	dst := filepath.Join(dir, id+".cancelled")
	if err := renameNoReplace(src, dst); err != nil {
		return fmt.Errorf("request not cancellable after claim: %w", err)
	}
	if err := syncSpoolDir(dir); err != nil {
		return err
	}
	// A failure here retains .cancelled evidence and prevents execution.
	return CompleteRequest(ctl, req, errors.New("request cancelled before claim; no privileged effects executed"))
}
