package control

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestR03AAutomaticQueueSourceAndDurability(t *testing.T) {
	ctl := filepath.Join(t.TempDir(), "ctl")
	req, e := QueueAutomaticRequest(ctl, "start", "charging")
	if e != nil || req.Origin != "automatic" {
		t.Fatalf("automatic enqueue %+v %v", req, e)
	}
	got, p, e := NextRequest(ctl)
	if e != nil || !strings.HasSuffix(p, ".work") || got.Origin != "automatic" || got.ID != req.ID {
		t.Fatalf("not source bound %+v %s %v", got, p, e)
	}
	if e := MarkApplying(ctl, got); e != nil {
		t.Fatal(e)
	}
	if e := CompleteRequest(ctl, got, nil); e != nil {
		t.Fatal(e)
	}
	if e := os.Remove(p); e != nil {
		t.Fatal(e)
	}
	if ack, found := ReadAck(ctl, got.ID); !found || ack.State != "applied" {
		t.Fatalf("missing receipt %+v %v", ack, found)
	}
}
func TestR03AAutomaticRequestAllowlist(t *testing.T) {
	ctl := filepath.Join(t.TempDir(), "ctl")
	for _, tc := range []struct{ cmd, event string }{{"reload", "charging"}, {"start", "manual"}, {"start", "boot"}, {"stop", "bogus"}, {"safe", "low_power"}} {
		if _, e := QueueAutomaticRequest(ctl, tc.cmd, tc.event); e == nil {
			t.Fatalf("accepted %q %q", tc.cmd, tc.event)
		}
	}
}
func TestR03AForgedOriginRejected(t *testing.T) {
	ctl := filepath.Join(t.TempDir(), "ctl")
	r, e := QueueRequest(ctl, "start", "manual")
	if e != nil {
		t.Fatal(e)
	}
	p := filepath.Join(ctl+".d", r.ID+".json")
	b, e := os.ReadFile(p)
	if e != nil {
		t.Fatal(e)
	}
	var payload map[string]any
	if e := json.Unmarshal(b, &payload); e != nil {
		t.Fatal(e)
	}
	payload["origin"] = "admin-root"
	b, _ = json.Marshal(payload)
	if e := os.WriteFile(p, b, 0600); e != nil {
		t.Fatal(e)
	}
	got, bad, e := NextRequest(ctl)
	if e == nil || got.ID != "" || !strings.HasSuffix(bad, ".bad") {
		t.Fatalf("forged origin accepted: %+v %s %v", got, bad, e)
	}
}
func TestR03AManualQueueRemainsDistinct(t *testing.T) {
	ctl := filepath.Join(t.TempDir(), "ctl")
	r, e := QueueRequest(ctl, "start", "manual")
	if e != nil {
		t.Fatal(e)
	}
	if r.Origin != "" {
		t.Fatal("manual origin changed")
	}
	got, _, e := NextRequest(ctl)
	if e != nil || got.Origin != "" {
		t.Fatalf("manual request altered: %+v %v", got, e)
	}
}
