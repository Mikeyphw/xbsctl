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
		if strings.HasSuffix(name, ".json") {
			claimed := filepath.Join(dir, strings.TrimSuffix(name, ".json")+".work")
			if err := os.Rename(p, claimed); err != nil {
				if os.IsNotExist(err) { // another claimant won
					continue
				}
				return Request{}, "", err
			}
			p = claimed
		}
		b, err := os.ReadFile(p)
		if err != nil {
			if os.IsNotExist(err) {
				continue
			}
			return Request{}, "", err
		}
		var r Request
		if err = json.Unmarshal(b, &r); err != nil || r.ID == "" || r.Command == "" {
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
		return r, p, nil
	}
}

func requestBase(name string) string {
	name = strings.TrimSuffix(name, ".json")
	name = strings.TrimSuffix(name, ".work")
	return name
}

func CompleteRequest(ctl string, r Request, err error) error {
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
	if e := os.WriteFile(tmp, append(b, '\n'), 0644); e != nil {
		return e
	}
	return os.Rename(tmp, dst)
}
func ReadAck(ctl, id string) (Ack, bool) {
	b, e := os.ReadFile(filepath.Join(ctl+".ack", id+".json"))
	if e != nil {
		return Ack{}, false
	}
	var a Ack
	if json.Unmarshal(b, &a) != nil {
		return Ack{}, false
	}
	return a, true
}
