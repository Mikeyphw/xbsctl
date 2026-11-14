package control

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestR09EControlAckIdentifierBoundary(t *testing.T) {
	for _, id := range []string{"123", "1-2", "000001-0000000001", strings.Repeat("1", 96)} {
		if !validRequestID(id) {
			t.Fatalf("valid id refused %q", id)
		}
	}
	for _, id := range []string{"", "1", "12", strings.Repeat("1", 97), "a12", "1/2", "../escape", "12_", "12\n"} {
		if validRequestID(id) {
			t.Fatalf("unsafe id accepted %q", id)
		}
		ctl := filepath.Join(t.TempDir(), "ctl")
		if _, found, err := strictAck(ctl, id); err == nil || found {
			t.Fatalf("unsafe ack id read %q err=%v", id, err)
		}
		if _, err := os.Stat(ctl + ".ack"); !os.IsNotExist(err) {
			t.Fatalf("refusal created ack directory %q: %v", id, err)
		}
	}
	ctl := filepath.Join(t.TempDir(), "ctl")
	if _, found, err := strictAck(ctl, "123"); err != nil || found {
		t.Fatalf("missing ack misreported found=%t err=%v", found, err)
	}
}
