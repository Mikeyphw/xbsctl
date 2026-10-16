package schema

import "testing"

func TestCommandSchemaHasFrozenCommandCount(t *testing.T) {
	if len(Commands()) != 17 {
		t.Fatalf("commands=%d", len(Commands()))
	}
	frozen, err := FrozenCLICommandCount()
	if err != nil {
		t.Fatal(err)
	}
	if frozen != len(Commands()) {
		t.Fatalf("frozen=%d go=%d", frozen, len(Commands()))
	}
}

func TestConfigSchemaContainsCoreFields(t *testing.T) {
	payload, err := Render("config", "")
	if err != nil {
		t.Fatal(err)
	}
	entries := payload["entries"].([]map[string]any)
	keys := map[string]bool{}
	for _, row := range entries {
		keys[row["key"].(string)] = true
	}
	for _, key := range []string{"header.version", "header.delay", "events.template.allowlist"} {
		if !keys[key] {
			t.Fatalf("missing %s", key)
		}
	}
}
