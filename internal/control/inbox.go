package control

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync/atomic"
	"time"
)

type Request struct {
	ID      string `json:"id"`
	Command string `json:"command"`
	Event   string `json:"event,omitempty"`
	Created int64  `json:"created"`
}

var requestCounter uint64

type Ack struct {
	ID        string `json:"id"`
	State     string `json:"state"`
	Error     string `json:"error,omitempty"`
	Completed int64  `json:"completed"`
}

func QueueRequest(ctl, command, event string) (Request, error) {
	dir := ctl + ".d"
	if err := os.MkdirAll(dir, 0755); err != nil {
		return Request{}, err
	}
	id := fmt.Sprintf("%020d-%d-%06d", time.Now().UnixNano(), os.Getpid(), atomic.AddUint64(&requestCounter, 1))
	r := Request{ID: id, Command: command, Event: event, Created: time.Now().Unix()}
	b, _ := json.Marshal(r)
	tmp := filepath.Join(dir, "."+id+".tmp")
	dst := filepath.Join(dir, id+".json")
	if err := os.WriteFile(tmp, append(b, '\n'), 0644); err != nil {
		return Request{}, err
	}
	if err := os.Rename(tmp, dst); err != nil {
		_ = os.Remove(tmp)
		return Request{}, err
	}
	return r, nil
}
func NextRequest(ctl string) (Request, string, error) {
	dir := ctl + ".d"
	for {
		es, err := os.ReadDir(dir)
		if os.IsNotExist(err) {
			return Request{}, "", nil
		}
		if err != nil {
			return Request{}, "", err
		}
		names := []string{}
		for _, e := range es {
			if e.IsDir() {
				continue
			}
			name := e.Name()
			if strings.HasSuffix(name, ".json") || strings.HasSuffix(name, ".work") {
				names = append(names, name)
			}
		}
		sort.Slice(names, func(i, j int) bool {
			return requestBase(names[i]) < requestBase(names[j])
		})
		if len(names) == 0 {
			return Request{}, "", nil
		}

		name := names[0]
		p := filepath.Join(dir, name)
		// A claim created by this invocation is fresh work. Only an older
		// unacknowledged .work file can be treated as an interrupted claim.
		claimedHere := false
		if strings.HasSuffix(name, ".json") {
			claimed := filepath.Join(dir, strings.TrimSuffix(name, ".json")+".work")
			if err := os.Rename(p, claimed); err != nil {
				if os.IsNotExist(err) { // another claimant won
					continue
				}
				return Request{}, "", err
			}
			p = claimed
			claimedHere = true
		}
		b, err := os.ReadFile(p)
		if err != nil {
			if os.IsNotExist(err) {
				continue
			}
			return Request{}, "", err
		}
		var r Request
		if err = json.Unmarshal(b, &r); err != nil || !validRequestID(r.ID) || r.ID != requestBase(name) || r.Command == "" {
			bad := p + ".bad"
			_ = os.Rename(p, bad)
			if err != nil {
				return Request{}, bad, fmt.Errorf("invalid control request: %w", err)
			}
			return Request{}, bad, fmt.Errorf("invalid control request")
		}
		// A crash after writing the acknowledgement but before deleting the claim
		// must not execute the same request again.
		if _, ok := ReadAck(ctl, r.ID); ok {
			_ = os.Remove(p)
			continue
		}
		if !claimedHere && strings.HasSuffix(p, ".work") {
			// The previous process may have applied root effects before dying.
			// Fail closed: never replay this request with an unknown commit state.
			cause := fmt.Errorf("interrupted command %s requires reconciliation; not replayed", r.ID)
			if err := CompleteRequest(ctl, r, cause); err != nil {
				return Request{}, p, fmt.Errorf("persist interrupted request acknowledgement: %w", err)
			}
			interrupted := strings.TrimSuffix(p, ".work") + ".interrupted"
			if err := os.Rename(p, interrupted); err != nil {
				return Request{}, p, fmt.Errorf("quarantine interrupted request: %w", err)
			}
			return Request{}, interrupted, cause
		}
		return r, p, nil
	}
}

func requestBase(name string) string {
	name = strings.TrimSuffix(name, ".json")
	name = strings.TrimSuffix(name, ".work")
	return name
}

// validRequestID allows only the local queue ID grammar. In particular it
// cannot escape the ack directory via slashes, traversal or encoded paths.
func validRequestID(id string) bool {
	if len(id) < 3 || len(id) > 96 {
		return false
	}
	for _, c := range id {
		if c != '-' && (c < '0' || c > '9') {
			return false
		}
	}
	return true
}

func CompleteRequest(ctl string, r Request, err error) error {
	if !validRequestID(r.ID) {
		return fmt.Errorf("invalid acknowledgement id")
	}
	dir := ctl + ".ack"
	if e := os.MkdirAll(dir, 0755); e != nil {
		return e
	}
	a := Ack{ID: r.ID, State: "applied", Completed: time.Now().Unix()}
	if err != nil {
		a.State = "failed"
		a.Error = err.Error()
	}
	b, _ := json.Marshal(a)
	tmp := filepath.Join(dir, "."+r.ID+".tmp")
	dst := filepath.Join(dir, r.ID+".json")
	f, e := os.OpenFile(tmp, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0644)
	if e != nil {
		return e
	}
	defer os.Remove(tmp)
	if _, e = f.Write(append(b, '\n')); e != nil {
		_ = f.Close()
		return e
	}
	if e = f.Sync(); e != nil {
		_ = f.Close()
		return e
	}
	if e = f.Close(); e != nil {
		return e
	}
	if e = os.Rename(tmp, dst); e != nil {
		return e
	}
	d, e := os.Open(dir)
	if e != nil {
		return e
	}
	e = d.Sync()
	closeErr := d.Close()
	if e != nil {
		return e
	}
	return closeErr
}
func ReadAck(ctl, id string) (Ack, bool) {
	if !validRequestID(id) {
		return Ack{}, false
	}
	b, e := os.ReadFile(filepath.Join(ctl+".ack", id+".json"))
	if e != nil {
		return Ack{}, false
	}
	var a Ack
	if json.Unmarshal(b, &a) != nil || a.ID != id {
		return Ack{}, false
	}
	return a, true
}
