package daemon

import (
	"testing"
	"time"
)

func TestR09EDaemonArgumentAndEventBoundaries(t *testing.T) {
	for _, tc := range [][]string{{"--interval"}, {"--interval", "0"}, {"--interval", "-1"}, {"--interval", "x"}, {"--unknown"}} {
		if _, err := parse(tc); err == nil {
			t.Fatalf("unsafe daemon arguments accepted: %v", tc)
		}
	}
	opts, err := parse([]string{"--once", "--json", "--dry-run", "--interval", "4"})
	if err != nil || !opts.once || !opts.jsonMode || !opts.dryRun || opts.interval != 4*time.Second {
		t.Fatalf("daemon options %+v err=%v", opts, err)
	}
	cfg := map[string]any{"events": map[string]any{"template": map[string]any{}, "a": map[string]any{}, "z": map[string]any{}}}
	got := eventKeys(cfg)
	if len(got) != 3 || got[0] != "a" || got[1] != "manual" || got[2] != "z" {
		t.Fatalf("unqualified event list %v", got)
	}
	if !eventExists(cfg, "manual") || !eventExists(cfg, "z") || eventExists(cfg, "template") || eventExists(cfg, "missing") {
		t.Fatal("event allowlist drift")
	}
	if contains([]string{"a", "b"}, "c") || !contains([]string{"a", "b"}, "a") {
		t.Fatal("contains drift")
	}
}
