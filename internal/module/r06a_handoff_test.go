package module

import (
    "os"
    "path/filepath"
    "strings"
    "testing"
)

// R06A must accept the first-class Go runner, and reject a native manifest
// that has lost its test, vet, or final-seal step. Legacy compatibility is
// covered by the pre-existing handoff tests in module_test.go.
func TestR06ANativeDevtoolHandoffRequiresRealGoAndFinalSeal(t *testing.T) {
    root := t.TempDir()
    manifest := `[targets.xbs]
root = "."
runner = "go"

[jobs.xbs-go-test]
runner = "go"
root = "."
action = "test"

[jobs.xbs-go-vet]
runner = "go"
root = "."
action = "lint"

[jobs.xbs-final-seal]
runner = "command"
root = "."
command = ["go", "run", "./cmd/xbsrepo", "final-seal"]

[targets.xbs.workflows]
build = ["job:xbs-go-test", "job:xbs-go-vet", "job:xbs-final-seal", "job:xbs-build-module"]
`
    path := filepath.Join(root, ".devtool.toml")
    if err := os.WriteFile(filepath.Join(root, ".gitignore"), []byte(".tmp/\n"), 0o644); err != nil {
        t.Fatal(err)
    }
    check := func(value string) error {
        t.Helper()
        if err := os.WriteFile(path, []byte(value), 0o644); err != nil {
            t.Fatal(err)
        }
        return VerifyRepositoryHandoff(root)
    }
    if err := check(manifest); err != nil {
        t.Fatalf("native Devtool handoff must be accepted: %v", err)
    }
    for _, tc := range []struct{ name, old, replacement string }{
        {"go target", "runner = \"go\"", "runner = \"command\""},
        {"native test", "action = \"test\"", "action = \"run\""},
        {"native vet", "action = \"lint\"", "action = \"run\""},
        {"final seal", "command = [\"go\", \"run\", \"./cmd/xbsrepo\", \"final-seal\"]", "command = [\"go\", \"version\"]"},
    } {
        t.Run(tc.name, func(t *testing.T) {
            altered := strings.Replace(manifest, tc.old, tc.replacement, 1)
            if altered == manifest {
                t.Fatal("mutation did not change the manifest")
            }
            if err := check(altered); err == nil {
                t.Fatal("unsafe native Devtool handoff accepted")
            }
        })
    }
}
