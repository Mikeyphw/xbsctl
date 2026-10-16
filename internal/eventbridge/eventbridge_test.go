package eventbridge

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func testPaths(t *testing.T) Paths {
	t.Helper()
	root := filepath.Join(t.TempDir(), "bridge")
	runtime := filepath.Join(t.TempDir(), "runtime")
	if err := os.MkdirAll(runtime, 0o700); err != nil {
		t.Fatal(err)
	}
	paths := Paths{
		Root:     root,
		Runtime:  runtime,
		Key:      filepath.Join(root, "event_bridge_hmac_v1.key"),
		Envelope: filepath.Join(runtime, "event_bridge_v1.json"),
		Sequence: filepath.Join(runtime, "event_bridge_v1.seq"),
		Event:    filepath.Join(runtime, "event_changes.json"),
		Status:   filepath.Join(runtime, "status.json"),
	}
	if err := os.WriteFile(paths.Event, []byte(`{"safe_mode":false,"active":["manual"],"inactive":[]}`), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(paths.Status, []byte(`{"mode":"manual","active_profiles":[]}`), 0o600); err != nil {
		t.Fatal(err)
	}
	return paths
}

func TestPublishAndVerifyAuthenticatedEnvelope(t *testing.T) {
	paths := testPaths(t)
	now := int64(1234567890)
	published, err := Publish(paths, &now)
	if err != nil {
		t.Fatal(err)
	}
	if published["authenticated"] != true || published["sequence"] != 1 {
		t.Fatalf("unexpected publish payload: %#v", published)
	}
	verified, err := Verify(paths)
	if err != nil {
		t.Fatal(err)
	}
	if verified["authenticated"] != true || verified["sequence"] != 1 {
		t.Fatalf("unexpected verify payload: %#v", verified)
	}
	info, err := os.Stat(paths.Key)
	if err != nil {
		t.Fatal(err)
	}
	if info.Mode().Perm() != 0o600 {
		t.Fatalf("key mode = %o", info.Mode().Perm())
	}
	info, err = os.Stat(paths.Envelope)
	if err != nil {
		t.Fatal(err)
	}
	if info.Mode().Perm() != 0o600 {
		t.Fatalf("envelope mode = %o", info.Mode().Perm())
	}
}

func TestPublishIncrementsSequence(t *testing.T) {
	paths := testPaths(t)
	if _, err := Publish(paths, nil); err != nil {
		t.Fatal(err)
	}
	second, err := Publish(paths, nil)
	if err != nil {
		t.Fatal(err)
	}
	if second["sequence"] != 2 {
		t.Fatalf("sequence did not increment: %#v", second)
	}
}

func TestVerifyRejectsTamperedEnvelope(t *testing.T) {
	paths := testPaths(t)
	if _, err := Publish(paths, nil); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(paths.Envelope)
	if err != nil {
		t.Fatal(err)
	}
	var envelope map[string]any
	if err := json.Unmarshal(data, &envelope); err != nil {
		t.Fatal(err)
	}
	envelope["sequence"] = float64(999)
	mutated, _ := json.Marshal(envelope)
	if err := os.WriteFile(paths.Envelope, mutated, 0o600); err != nil {
		t.Fatal(err)
	}
	_, err = Verify(paths)
	if err == nil {
		t.Fatal("expected tampered envelope to fail")
	}
	if bridgeErr, ok := err.(*BridgeError); !ok || bridgeErr.Code != "bad_mac" {
		t.Fatalf("unexpected error: %#v", err)
	}
}

func TestPrivateParentRejectsGroupReadableDirectory(t *testing.T) {
	root := t.TempDir()
	bad := filepath.Join(root, "bad")
	if err := os.Mkdir(bad, 0o755); err != nil {
		t.Fatal(err)
	}
	// os.Mkdir honors the process umask; some Termux/root shells run tests with
	// umask 077, which turns the requested 0755 directory into 0700 and makes
	// this regression a false pass. Force the unsafe mode after creation.
	if err := os.Chmod(bad, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := validatePrivateParent(bad); err == nil {
		t.Fatal("expected group-readable private parent to be rejected")
	}
}
