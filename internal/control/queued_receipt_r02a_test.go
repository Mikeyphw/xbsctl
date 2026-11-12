package control

import (
	"errors"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestR02AFailedReceiptDistinctFromTimeout(t *testing.T) {
	ctl := filepath.Join(t.TempDir(), "ctl")
	r, err := QueueRequest(ctl, "reload", "")
	if err != nil {
		t.Fatal(err)
	}
	if err := CompleteRequest(ctl, r, errors.New("recovery required")); err != nil {
		t.Fatal(err)
	}
	ack, found := waitForRequest(ctl, r.ID, 0*time.Second)
	if !found || ack.State != "failed" || !strings.Contains(ack.Error, "recovery required") {
		t.Fatalf("failure confused with timeout: %+v %v", ack, found)
	}
	_, found = waitForRequest(ctl, "123-456-789", 0*time.Second)
	if found {
		t.Fatal("unknown receipt accepted")
	}
}
