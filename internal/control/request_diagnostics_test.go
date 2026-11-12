package control

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestR03CInspectorLifecycleReadOnly(t *testing.T) {
	ctl := filepath.Join(t.TempDir(), "ctl")
	r, err := QueueAutomaticRequest(ctl, "start", "charging")
	if err != nil {
		t.Fatal(err)
	}
	for _, state := range []string{"queued", "claimed", "applied"} {
		d, err := InspectRequest(ctl, r.ID)
		if err != nil || d.State != state {
			t.Fatalf("%s: %+v %v", state, d, err)
		}
		if state == "queued" {
			n, p, e := NextRequest(ctl)
			if e != nil || n.ID != r.ID || !strings.HasSuffix(p, ".work") {
				t.Fatalf("claim %v %s %v", n, p, e)
			}
		}
		if state == "claimed" {
			if err := CompleteRequest(ctl, r, nil); err != nil {
				t.Fatal(err)
			}
		}
	}
}
func TestR03CInspectorDoesNotCreateDirectories(t *testing.T) {
	ctl := filepath.Join(t.TempDir(), "ctl")
	d, e := InspectRequest(ctl, "00000000000000000001-1-000001")
	if e != nil || d.State != "missing" {
		t.Fatalf("%+v %v", d, e)
	}
	for _, dir := range []string{ctl + ".d", ctl + ".ack"} {
		if _, e := os.Lstat(dir); !os.IsNotExist(e) {
			t.Fatalf("inspection wrote %s: %v", dir, e)
		}
	}
}
func TestR03CInspectorSymlinkAndPermissionsRefuse(t *testing.T) {
	ctl := filepath.Join(t.TempDir(), "ctl")
	req, e := QueueAutomaticRequest(ctl, "start", "charging")
	if e != nil {
		t.Fatal(e)
	}
	path := filepath.Join(ctl+".d", req.ID+".json")
	if err := os.Remove(path); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink("/proc/self/stat", path); err != nil {
		t.Fatal(err)
	}
	if _, err := InspectRequest(ctl, req.ID); err == nil {
		t.Fatal("symlink accepted")
	}
	if err := os.Remove(path); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(ctl+".d", 0755); err != nil {
		t.Fatal(err)
	}
	if _, err := InspectRequest(ctl, req.ID); err == nil {
		t.Fatal("unsafe directory repaired by inspection")
	}
	if st, e := os.Stat(ctl + ".d"); e != nil || st.Mode().Perm() != 0755 {
		t.Fatalf("inspection modified permissions %v %v", st, e)
	}
}
func TestR03CInspectorCannotHideMalformedReceipt(t *testing.T) {
	ctl := filepath.Join(t.TempDir(), "ctl")
	req, e := QueueAutomaticRequest(ctl, "start", "charging")
	if e != nil {
		t.Fatal(e)
	}
	if err := os.MkdirAll(ctl+".ack", 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(ctl+".ack", req.ID+".json"), []byte("{"), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := InspectRequest(ctl, req.ID); err == nil {
		t.Fatal("corrupt receipt accepted")
	}
}
func TestR03CInspectorRejectsTraversal(t *testing.T) {
	if _, err := InspectRequest(filepath.Join(t.TempDir(), "ctl"), "../evil"); err == nil {
		t.Fatal("traversal accepted")
	}
}
