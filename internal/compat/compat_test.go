package compat

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestPayloadMatchesFrozenCompatibilityDigest(t *testing.T) {
	payload, err := Payload(nil)
	if err != nil {
		t.Fatal(err)
	}
	compatibility := payload["compatibility"].(map[string]any)
	if compatibility["digest"] != "c38faee1997d7c1d9f382dd572bc44c494aab675fc523e7577dcf0eecba54d0b" {
		t.Fatalf("digest drift: %v", compatibility["digest"])
	}
	config := compatibility["config"].(map[string]any)
	if config["schema_digest"] != "237caba0ac9dfd8e9ecd61ea97bcad375a3ce1e7a6fbbc6d77082c78a605f318" {
		t.Fatalf("schema digest drift: %v", config["schema_digest"])
	}
	commands := compatibility["commands"].([]map[string]any)
	if len(commands) != 39 {
		t.Fatalf("command count = %d", len(commands))
	}
	runtime := compatibility["runtime"].(map[string]any)
	if runtime["kind"] != "go-native" || runtime["python_required"] != false || runtime["yq_required"] != false {
		t.Fatalf("unexpected runtime contract: %#v", runtime)
	}
}

func TestPayloadReadsModulePropertiesAndSchemaFromModuleRoot(t *testing.T) {
	root := t.TempDir()
	t.Setenv("XBS_MODULE_DIR", root)
	if err := os.WriteFile(filepath.Join(root, "module.prop"), []byte("id=XtremeBS\nversion=v9\nversionCode=900\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "schema.json"), []byte(`{"properties":{"events":{"additionalProperties":{"properties":{"allowlist":{},"denylist":{}}}}}}`), 0o644); err != nil {
		t.Fatal(err)
	}
	payload, err := Payload(nil)
	if err != nil {
		t.Fatal(err)
	}
	encoded, _ := json.Marshal(payload)
	if string(encoded) == "{}" {
		t.Fatal("empty payload")
	}
	compatibility := payload["compatibility"].(map[string]any)
	module := compatibility["module"].(map[string]any)
	if module["version"] != "v9" || module["version_code"] != 900 {
		t.Fatalf("module properties not reflected: %#v", module)
	}
}
