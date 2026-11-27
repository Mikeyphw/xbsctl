package control

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestR02APrivateSpoolAndDurableReceipts(t *testing.T) {
	ctl := filepath.Join(t.TempDir(), "control")
	req, err := QueueRequest(ctl, "start", "manual")
	if err != nil {
		t.Fatal(err)
	}
	for _, dir := range []string{ctl + ".d"} {
		st, err := os.Lstat(dir)
		if err != nil || st.Mode().Perm() != 0700 {
			t.Fatalf("unsafe spool dir %s %v %v", dir, st, err)
		}
	}
	claimed, path, err := NextRequest(ctl)
	if err != nil || claimed.ID != req.ID || !strings.HasSuffix(path, ".work") {
		t.Fatalf("fresh claim %v %v %q", claimed, err, path)
	}
	if err := CompleteRequest(ctl, claimed, nil); err != nil {
		t.Fatal(err)
	}
	st, err := os.Stat(filepath.Join(ctl+".ack", req.ID+".json"))
	if err != nil || st.Mode().Perm() != 0600 {
		t.Fatalf("receipt mode %v %v", st, err)
	}
	if err := CompleteRequest(ctl, claimed, nil); err != nil {
		t.Fatalf("idempotent receipt: %v", err)
	}
	if err := CompleteRequest(ctl, claimed, errors.New("rewritten")); err == nil {
		t.Fatal("applied receipt overwritten")
	}
	a, ok := ReadAck(ctl, req.ID)
	if !ok || a.State != "applied" {
		t.Fatalf("receipt changed: %v %v", a, ok)
	}
	next, p, err := NextRequest(ctl)
	if err != nil || next.ID != "" || p != "" {
		t.Fatalf("acknowledged work replayed: %v %q %v", next, p, err)
	}
}
func TestR02AQueueDirectorySymlinkRefused(t *testing.T) {
	root := t.TempDir()
	target := filepath.Join(root, "target")
	if err := os.Mkdir(target, 0700); err != nil {
		t.Fatal(err)
	}
	ctl := filepath.Join(root, "ctl")
	if err := os.Symlink(target, ctl+".d"); err != nil {
		t.Skip(err)
	}
	if _, err := QueueRequest(ctl, "start", "manual"); err == nil {
		t.Fatal("symlink queue accepted")
	}
	if _, _, err := NextRequest(ctl); err == nil {
		t.Fatal("symlink queue opened")
	}
}
func TestR02AAckDirectorySymlinkRefused(t *testing.T) {
	root := t.TempDir()
	target := filepath.Join(root, "target")
	if err := os.Mkdir(target, 0700); err != nil {
		t.Fatal(err)
	}
	ctl := filepath.Join(root, "ctl")
	if err := os.Symlink(target, ctl+".ack"); err != nil {
		t.Skip(err)
	}
	if err := CompleteRequest(ctl, Request{ID: "00000000000000000001-1-000001"}, nil); err == nil {
		t.Fatal("symlink receipt directory accepted")
	}
}
func TestR02AMaliciousRequestSymlinkQuarantined(t *testing.T) {
	root := t.TempDir()
	ctl := filepath.Join(root, "ctl")
	dir := ctl + ".d"
	if err := os.Mkdir(dir, 0700); err != nil {
		t.Fatal(err)
	}
	secret := filepath.Join(root, "secret")
	if err := os.WriteFile(secret, []byte("do not follow"), 0600); err != nil {
		t.Fatal(err)
	}
	p := filepath.Join(dir, "00000000000000000001-1-000001.json")
	if err := os.Symlink(secret, p); err != nil {
		t.Skip(err)
	}
	_, bad, err := NextRequest(ctl)
	if err == nil || !strings.HasSuffix(bad, ".bad") {
		t.Fatalf("symlink not quarantined: %q %v", bad, err)
	}
	b, e := os.ReadFile(secret)
	if e != nil || string(b) != "do not follow" {
		t.Fatalf("symlink target touched: %q %v", b, e)
	}
}
func TestR02AOversizeRequestAndForgedCommandQuarantined(t *testing.T) {
	ctl := filepath.Join(t.TempDir(), "ctl")
	dir := ctl + ".d"
	if err := os.Mkdir(dir, 0700); err != nil {
		t.Fatal(err)
	}
	id := "00000000000000000001-1-000001"
	if err := os.WriteFile(filepath.Join(dir, id+".json"), []byte(strings.Repeat("x", maxRequestBytes+1)), 0600); err != nil {
		t.Fatal(err)
	}
	if _, p, err := NextRequest(ctl); err == nil || !strings.HasSuffix(p, ".bad") {
		t.Fatalf("oversized request accepted %s %v", p, err)
	}
	id = "00000000000000000002-1-000002"
	if err := os.WriteFile(filepath.Join(dir, id+".json"), []byte(`{"id":"`+id+`","command":"do-something-unsafe"}`), 0600); err != nil {
		t.Fatal(err)
	}
	if _, p, err := NextRequest(ctl); err == nil || !strings.HasSuffix(p, ".bad") {
		t.Fatalf("invalid command accepted %s %v", p, err)
	}
}
func TestR02AInterruptedClaimCannotOverwriteReceipt(t *testing.T) {
	ctl := filepath.Join(t.TempDir(), "ctl")
	r, err := QueueRequest(ctl, "start", "manual")
	if err != nil {
		t.Fatal(err)
	}
	_, work, err := NextRequest(ctl)
	if err != nil {
		t.Fatal(err)
	}
	if _, p, err := NextRequest(ctl); err == nil || !strings.HasSuffix(p, ".interrupted") {
		t.Fatalf("orphan replay %s %v", p, err)
	}
	if _, err := os.Stat(work); !os.IsNotExist(err) {
		t.Fatalf("unquarantined work %v", err)
	}
	ack, ok := ReadAck(ctl, r.ID)
	if !ok || ack.State != "failed" {
		t.Fatalf("missing failure receipt: %v %v", ack, ok)
	}
	if err := CompleteRequest(ctl, r, nil); err == nil {
		t.Fatal("failed receipt overwritten with success")
	}
}
func TestR02ABrokenReceiptIsNotOverwritten(t *testing.T) {
	ctl := filepath.Join(t.TempDir(), "ctl")
	r, err := QueueRequest(ctl, "reload", "")
	if err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir(ctl+".ack", 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(ctl+".ack", r.ID+".json"), []byte("not-json"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := CompleteRequest(ctl, r, nil); err == nil {
		t.Fatal("corrupt receipt overwritten")
	}
	_, p, err := NextRequest(ctl)
	if err == nil || !strings.HasSuffix(p, ".work") {
		t.Fatalf("corrupt receipt did not block claim %s %v", p, err)
	}
}
func TestR02APermissionUpgradeFromLegacy0755(t *testing.T) {
	ctl := filepath.Join(t.TempDir(), "ctl")
	dir := ctl + ".d"
	if err := os.Mkdir(dir, 0755); err != nil {
		t.Fatal(err)
	}
	if _, err := QueueRequest(ctl, "reload", ""); err != nil {
		t.Fatal(err)
	}
	st, err := os.Stat(dir)
	if err != nil || st.Mode().Perm() != 0700 {
		t.Fatalf("legacy directory not private: %v %v", st, err)
	}
}

// HF01: publication must be atomic and MUST NOT replace an existing receipt,
// even when Linux/Android forbids ordinary file hard-link creation.
func TestR02AHF01NoReplacePublicationCollision(t *testing.T) {
	dir := t.TempDir()
	tmp := filepath.Join(dir, ".unique.tmp")
	dst := filepath.Join(dir, "unique.json")
	if err := writeNewFile(tmp, []byte(`{"state":"new"}`)); err != nil {
		t.Fatal(err)
	}
	if err := writeNewFile(dst, []byte(`{"state":"original"}`)); err != nil {
		t.Fatal(err)
	}
	if err := publishNewFile(tmp, dst); err == nil {
		t.Fatal("no-replace publication overwrote existing receipt")
	} else if !os.IsExist(err) {
		t.Fatalf("collision returned unexpected error: %v", err)
	}
	old, err := os.ReadFile(dst)
	if err != nil || string(old) != `{"state":"original"}` {
		t.Fatalf("existing receipt changed: %q %v", old, err)
	}
	if _, err := os.Stat(tmp); err != nil {
		t.Fatalf("failed no-replace publish destroyed temp data: %v", err)
	}
	if err := os.Remove(dst); err != nil {
		t.Fatal(err)
	}
	if err := publishNewFile(tmp, dst); err != nil {
		t.Fatal(err)
	}
	fresh, err := os.ReadFile(dst)
	if err != nil || string(fresh) != `{"state":"new"}` {
		t.Fatalf("atomic publication lost content: %q %v", fresh, err)
	}
	if _, err := os.Stat(tmp); !os.IsNotExist(err) {
		t.Fatalf("published temp name was not consumed: %v", err)
	}
}

func TestR02AHF01NoReplaceRejectsCrossDirectory(t *testing.T) {
	root := t.TempDir()
	one, two := filepath.Join(root, "one"), filepath.Join(root, "two")
	if err := os.Mkdir(one, 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir(two, 0700); err != nil {
		t.Fatal(err)
	}
	tmp, dst := filepath.Join(one, "item.tmp"), filepath.Join(two, "item.json")
	if err := writeNewFile(tmp, []byte("intact")); err != nil {
		t.Fatal(err)
	}
	if err := publishNewFile(tmp, dst); err == nil {
		t.Fatal("cross-directory publication accepted")
	}
	if data, err := os.ReadFile(tmp); err != nil || string(data) != "intact" {
		t.Fatalf("refused cross-directory publication destroyed data: %q %v", data, err)
	}
}
