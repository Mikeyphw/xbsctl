package control

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Mikeyphw/xbsctl/internal/config"
)

func withEnv(t *testing.T) string {
	t.Helper()
	root := t.TempDir()
	t.Setenv("XBS_EXTERNAL_ROOT", filepath.Join(root, "external"))
	t.Setenv("XBS_RUNTIME_DIR", filepath.Join(root, "runtime"))
	cfg := config.Default()
	if _, err := config.Save(cfg); err != nil {
		t.Fatal(err)
	}
	return root
}

func TestControllerQueuesStartCommand(t *testing.T) {
	withEnv(t)
	var out strings.Builder
	code := Main([]string{"--json", "start", "manual"}, &out, &out)
	if code != 0 {
		t.Fatalf("code=%d out=%s", code, out.String())
	}
	ctl, _ := config.GetByKey(config.Default(), "header.ctl_file")
	req, _, err := NextRequest(ctl.(string))
	if err != nil {
		t.Fatal(err)
	}
	if req.Command != "start" || req.Event != "manual" || req.ID == "" {
		t.Fatalf("request=%+v", req)
	}
	var obj map[string]any
	if err := json.Unmarshal([]byte(out.String()), &obj); err != nil {
		t.Fatal(err)
	}
	if obj["completion"] != "queued" {
		t.Fatalf("json=%v", obj)
	}
}

func TestControllerStatusReadsDaemonSnapshot(t *testing.T) {
	withEnv(t)
	p := config.ResolvePaths()
	if err := os.MkdirAll(filepath.Dir(p.Status), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(p.Status, []byte(`{"version":2,"log_level":3,"mode":"normal","active_events":["manual"],"last_reload_time":"now","detail":{"source":"test"}}`), 0o644); err != nil {
		t.Fatal(err)
	}
	var out strings.Builder
	code := Main([]string{"--json", "active"}, &out, &out)
	if code != 0 {
		t.Fatalf("code=%d out=%s", code, out.String())
	}
	if !strings.Contains(out.String(), "manual") {
		t.Fatalf("out=%s", out.String())
	}
}

func TestInboxPreservesConcurrentRequestsAndOwnAcknowledgements(t *testing.T) {
	root := withEnv(t)
	ctl := filepath.Join(root, "runtime", "xbs.ctl")
	const n = 20
	ids := make(chan string, n)
	errs := make(chan error, n)
	for i := 0; i < n; i++ {
		go func(i int) {
			r, e := QueueRequest(ctl, "start", fmt.Sprintf("event_%02d", i))
			if e != nil {
				errs <- e
				return
			}
			ids <- r.ID
		}(i)
	}
	seen := map[string]bool{}
	for i := 0; i < n; i++ {
		select {
		case e := <-errs:
			t.Fatal(e)
		case id := <-ids:
			if seen[id] {
				t.Fatalf("duplicate id %s", id)
			}
			seen[id] = true
		}
	}
	for i := 0; i < n; i++ {
		r, p, e := NextRequest(ctl)
		if e != nil || p == "" {
			t.Fatalf("next %d: %v %q", i, e, p)
		}
		if !seen[r.ID] {
			t.Fatalf("unknown request %+v", r)
		}
		if e = CompleteRequest(ctl, r, nil); e != nil {
			t.Fatal(e)
		}
		if e = os.Remove(p); e != nil {
			t.Fatal(e)
		}
		a, ok := ReadAck(ctl, r.ID)
		if !ok || a.State != "applied" {
			t.Fatalf("ack=%+v ok=%v", a, ok)
		}
		delete(seen, r.ID)
	}
	if len(seen) != 0 {
		t.Fatalf("lost requests: %v", seen)
	}
}

func TestInboxRecoversClaimAndSkipsAcknowledgedRequest(t *testing.T) {
	d := t.TempDir()
	ctl := filepath.Join(d, "xbs.ctl")
	r, err := QueueRequest(ctl, "reload", "")
	if err != nil {
		t.Fatal(err)
	}
	got, work, err := NextRequest(ctl)
	if err != nil || got.ID != r.ID || !strings.HasSuffix(work, ".work") {
		t.Fatalf("claim=%+v path=%q err=%v", got, work, err)
	}
	// Simulate daemon restart before completion: the .work claim is recoverable.
	got, work2, err := NextRequest(ctl)
	if err != nil || got.ID != r.ID || work2 != work {
		t.Fatalf("recovered=%+v path=%q err=%v", got, work2, err)
	}
	if err := CompleteRequest(ctl, got, nil); err != nil {
		t.Fatal(err)
	}
	// Simulate crash after ack but before deleting .work: it must not re-execute.
	got, path, err := NextRequest(ctl)
	if err != nil || path != "" || got.ID != "" {
		t.Fatalf("acknowledged request replayed: %+v %q %v", got, path, err)
	}
}

func TestInboxQuarantinesMalformedRequest(t *testing.T) {
	d := t.TempDir()
	ctl := filepath.Join(d, "xbs.ctl")
	dir := ctl + ".d"
	if err := os.MkdirAll(dir, 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "00000000000000000001-1-000001.json"), []byte("{bad"), 0644); err != nil {
		t.Fatal(err)
	}
	_, bad, err := NextRequest(ctl)
	if err == nil || !strings.HasSuffix(bad, ".bad") {
		t.Fatalf("bad=%q err=%v", bad, err)
	}
	if _, statErr := os.Stat(bad); statErr != nil {
		t.Fatalf("quarantine missing: %v", statErr)
	}
	// A bad request cannot permanently head-of-line block later valid work.
	r, err := QueueRequest(ctl, "reload", "")
	if err != nil {
		t.Fatal(err)
	}
	got, _, err := NextRequest(ctl)
	if err != nil || got.ID != r.ID {
		t.Fatalf("next valid=%+v err=%v", got, err)
	}
}
