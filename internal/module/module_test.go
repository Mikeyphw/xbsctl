package module

import (
	"archive/zip"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func fakeArm64ELF(t *testing.T, path string, mode os.FileMode) {
	t.Helper()
	data := make([]byte, 64)
	copy(data[:4], []byte("\x7fELF"))
	data[4] = 2
	data[5] = 1
	data[18] = 183
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, data, mode); err != nil {
		t.Fatal(err)
	}
}

func writeMinimalModule(t *testing.T, root string) {
	t.Helper()
	paths := map[string]string{
		"module/module.prop":          "id=XtremeBS\nname=Xtreme Battery Saver\nversion=v0\nversionCode=1\n",
		"module/service.sh":           "#!/system/bin/sh\nexit 0\n",
		"module/action.sh":            "#!/system/bin/sh\nexit 0\n",
		"module/customize.sh":         "#!/system/bin/sh\nexit 0\n",
		"module/system/bin/xbs":       "#!/system/bin/sh\nexit 0\n",
		"module/system/bin/XtremeBSd": "#!/system/bin/sh\nexit 0\n",
		"module/system/bin/XBSctl":    "#!/system/bin/sh\nexit 0\n",
	}
	for rel, text := range paths {
		p := filepath.Join(root, rel)
		if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
			t.Fatal(err)
		}
		mode := os.FileMode(0o644)
		if strings.Contains(rel, ".sh") || strings.Contains(rel, "system/bin/") {
			mode = 0o755
		}
		if err := os.WriteFile(p, []byte(text), mode); err != nil {
			t.Fatal(err)
		}
	}
	if err := os.WriteFile(filepath.Join(root, "go.mod"), []byte("module github.com/Mikeyphw/xbsctl\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"xbs", "XBSctl", "XtremeBSd"} {
		if err := os.MkdirAll(filepath.Join(root, "cmd", name), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(root, "cmd", name, "main.go"), []byte("package main\nfunc main(){}\n"), 0o644); err != nil {
			t.Fatal(err)
		}
	}
}

func TestStageModuleInstallsExecutableRuntime(t *testing.T) {
	root := t.TempDir()
	writeMinimalModule(t, root)
	runtime := filepath.Join(root, "runtime-bin")
	fakeArm64ELF(t, runtime, 0o755)
	stage := filepath.Join(root, "stage")
	if err := StageModule(root, runtime, stage); err != nil {
		t.Fatal(err)
	}
	info, err := os.Lstat(filepath.Join(stage, RuntimeRel))
	if err != nil {
		t.Fatal(err)
	}
	if info.Mode().Perm() != 0o755 {
		t.Fatalf("runtime mode = %o", info.Mode().Perm())
	}
	if err := ValidateModule(stage); err != nil {
		t.Fatal(err)
	}

	for _, rel := range []string{"yq-update.sh", "bin/yq", "system/bin/bash", "system/bin/bc"} {
		if _, err := os.Stat(filepath.Join(stage, rel)); !os.IsNotExist(err) {
			t.Fatalf("legacy runtime dependency was packaged: %s", rel)
		}
	}
	if _, err := os.Stat(filepath.Join(stage, "runtime/arm64-v8a/runtime-manifest.json")); err != nil {
		t.Fatal(err)
	}
}

func TestZipSkipsDestinationInsideModuleTree(t *testing.T) {
	root := t.TempDir()
	writeMinimalModule(t, root)
	runtime := filepath.Join(root, "runtime-bin")
	fakeArm64ELF(t, runtime, 0o755)
	stage := filepath.Join(root, "stage")
	if err := StageModule(root, runtime, stage); err != nil {
		t.Fatal(err)
	}
	zipPath := filepath.Join(stage, "XtremeBS-go.zip")
	if err := os.WriteFile(zipPath, []byte("stale"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := Zip(stage, zipPath); err != nil {
		t.Fatal(err)
	}
	zr, err := zip.OpenReader(zipPath)
	if err != nil {
		t.Fatal(err)
	}
	defer zr.Close()
	for _, f := range zr.File {
		if f.Name == "XtremeBS-go.zip" {
			t.Fatalf("zip included itself")
		}
		if f.Name == RuntimeRel && f.Mode().Perm() != 0o755 {
			t.Fatalf("runtime zip mode = %o", f.Mode().Perm())
		}
	}
}

func TestValidateRuntimeRejectsNonExecutable(t *testing.T) {
	p := filepath.Join(t.TempDir(), "xbs")
	fakeArm64ELF(t, p, 0o644)
	if err := ValidateRuntime(p); err == nil {
		t.Fatalf("expected non-executable runtime rejection")
	}
}

func TestInstallShellSupportsKernelSUAndMagisk(t *testing.T) {
	shell := installShell("/tmp/Xtreme's.zip")
	for _, want := range []string{"ksud module install", "/data/adb/ksud", "magisk --install-module", "'\\''"} {
		if !strings.Contains(shell, want) {
			t.Fatalf("install shell missing %q in %s", want, shell)
		}
	}
}

func TestRuntimeSetBuildsAllEntryPoints(t *testing.T) {
	root, err := FindAppRoot(".")
	if err != nil {
		t.Fatal(err)
	}
	out := t.TempDir()
	runtimes, err := BuildRuntimeSet(root, out, "linux", "amd64")
	if err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"xbs", "XBSctl", "XtremeBSd"} {
		path := runtimes[name]
		if path == "" {
			t.Fatalf("missing runtime %s", name)
		}
		info, err := os.Stat(path)
		if err != nil {
			t.Fatal(err)
		}
		if info.Mode().Perm()&0o111 == 0 {
			t.Fatalf("runtime %s is not executable: %v", name, info.Mode())
		}
	}
}

func TestVerifyGoNativePurityRejectsLegacyProductionPathsAndTokens(t *testing.T) {
	root := t.TempDir()
	if err := os.MkdirAll(filepath.Join(root, "cmd", "xbs"), 0o755); err != nil {
		t.Fatal(err)
	}
	for _, rel := range goNativeProductionFiles {
		p := filepath.Join(root, rel)
		if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(p, []byte("go-native\n"), 0o755); err != nil {
			t.Fatal(err)
		}
	}
	legacy := filepath.Join(root, "src", "xbs_cli")
	if err := os.MkdirAll(legacy, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(legacy, "cli.py"), []byte("legacy"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := VerifyGoNativePurity(root); err == nil || !strings.Contains(err.Error(), "src/xbs_cli") {
		t.Fatalf("expected legacy path rejection, got %v", err)
	}
	if err := os.RemoveAll(legacy); err != nil {
		t.Fatal(err)
	}
	bad := filepath.Join(root, "internal", "cli", "cli.go")
	if err := os.WriteFile(bad, []byte("python -m xbs_cli.event_bridge\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := VerifyGoNativePurity(root); err == nil || !strings.Contains(err.Error(), "xbs_cli") {
		t.Fatalf("expected legacy token rejection, got %v", err)
	}
}

func TestVerifyGoNativePurityAcceptsCurrentTree(t *testing.T) {
	root, err := FindAppRoot(".")
	if err != nil {
		t.Fatal(err)
	}
	if err := VerifyGoNativePurity(root); err != nil {
		t.Fatal(err)
	}
}

func TestVerifyRepositoryHandoffRejectsLegacySuiteReferences(t *testing.T) {
	root := t.TempDir()
	app := filepath.Join(root, "apps", "xbs")
	if err := os.MkdirAll(filepath.Join(app, "cmd", "xbs"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, ".devtool.toml"), []byte("[targets.xbs]\ntype = \"command\"\ntest_command = \"go test ./...\"\nrestore_command = \"pip install -e apps/xbs\"\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, ".gitignore"), []byte("apps/xbs/.tmp/\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := VerifyRepositoryHandoff(app); err == nil || !strings.Contains(err.Error(), "legacy Python XBS Devtool suite reference") {
		t.Fatalf("expected legacy suite reference rejection, got %v", err)
	}
}

func TestVerifyRepositoryHandoffAcceptsGoNativeSuiteReferences(t *testing.T) {
	root := t.TempDir()
	app := filepath.Join(root, "apps", "xbs")
	if err := os.MkdirAll(filepath.Join(app, "cmd", "xbs"), 0o755); err != nil {
		t.Fatal(err)
	}
	devtool := "[targets.xbs]\ntype = \"command\"\ntest_command = \"go test ./...\"\nrestore_command = \"command -v go\"\n"
	if err := os.WriteFile(filepath.Join(root, ".devtool.toml"), []byte(devtool), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, ".gitignore"), []byte("apps/xbs/.tmp/\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(root, "tools"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "tools", "generate_cli_artifacts.py"), []byte("# xbs is go-native\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "README.md"), []byte("go run ./cmd/xbsrepo build-module\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := VerifyRepositoryHandoff(app); err != nil {
		t.Fatalf("expected handoff to pass: %v", err)
	}
}

func TestVerifyRepositoryHandoffAcceptsDevtoolWorkflowTarget(t *testing.T) {
	root := t.TempDir()
	app := filepath.Join(root, "apps", "xbs")
	if err := os.MkdirAll(filepath.Join(app, "cmd", "xbs"), 0o755); err != nil {
		t.Fatal(err)
	}
	devtool := `[targets.xbs]
type = "command"
run_command = "go run ./cmd/xbs --help"

[jobs.xbs-go-test]
runner = "go"
root = "apps/xbs"
command = ["go", "test", "./..."]

[targets.xbs.workflows]
test = ["job:xbs-go-test"]
validate = ["job:xbs-go-test"]
`
	if err := os.WriteFile(filepath.Join(root, ".devtool.toml"), []byte(devtool), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, ".gitignore"), []byte("apps/xbs/.tmp/\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(root, "tools"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "tools", "generate_cli_artifacts.py"), []byte("# xbs is go-native\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "README.md"), []byte("go run ./cmd/xbsrepo build-module\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := VerifyRepositoryHandoff(app); err != nil {
		t.Fatalf("expected workflow handoff to pass: %v", err)
	}
}
