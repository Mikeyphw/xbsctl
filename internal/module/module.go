package module

import (
	"archive/zip"
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

const (
	ModuleID      = "XtremeBS"
	RuntimeRel    = "runtime/arm64-v8a/xbs"
	RuntimeDirRel = "runtime/arm64-v8a"
)

var deterministicTime = time.Unix(1600000000, 0).UTC()

var runtimeBinaries = map[string]string{"xbs": "./cmd/xbs", "XBSctl": "./cmd/XBSctl", "XtremeBSd": "./cmd/XtremeBSd"}

func FindAppRoot(start string) (string, error) {
	abs, err := filepath.Abs(start)
	if err != nil {
		return "", err
	}
	for {
		if exists(filepath.Join(abs, "go.mod")) && exists(filepath.Join(abs, "cmd", "xbs", "main.go")) && exists(filepath.Join(abs, "module", "module.prop")) {
			return abs, nil
		}
		parent := filepath.Dir(abs)
		if parent == abs {
			break
		}
		abs = parent
	}
	return "", fmt.Errorf("XBS app root not found from %s", start)
}

func BuildRuntime(root string, out string, goos string, goarch string) error {
	return buildRuntimePackage(root, "./cmd/xbs", out, goos, goarch)
}

func BuildRuntimeSet(root string, outDir string, goos string, goarch string) (map[string]string, error) {
	outputs := map[string]string{}
	names := make([]string, 0, len(runtimeBinaries))
	for name := range runtimeBinaries {
		names = append(names, name)
	}
	sort.Strings(names)
	for _, name := range names {
		out := filepath.Join(outDir, name)
		if err := buildRuntimePackage(root, runtimeBinaries[name], out, goos, goarch); err != nil {
			return nil, err
		}
		outputs[name] = out
	}
	return outputs, nil
}

func buildRuntimePackage(root string, pkg string, out string, goos string, goarch string) error {
	if goos == "" {
		goos = "android"
	}
	if goarch == "" {
		goarch = "arm64"
	}
	if err := os.MkdirAll(filepath.Dir(out), 0o755); err != nil {
		return err
	}
	cmd := exec.Command("go", "build", "-buildvcs=false", "-trimpath", "-o", out, pkg)
	cmd.Dir = root
	cmd.Env = append(os.Environ(), "GOOS="+goos, "GOARCH="+goarch, "CGO_ENABLED=0")
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		return fmt.Errorf("go build %s failed: %w: %s", pkg, err, strings.TrimSpace(stderr.String()))
	}
	if err := os.Chmod(out, 0o755); err != nil {
		return err
	}
	if goos == "android" && goarch == "arm64" {
		return ValidateRuntime(out)
	}
	info, err := os.Stat(out)
	if err != nil {
		return err
	}
	if !info.Mode().IsRegular() || info.Mode().Perm()&0o111 == 0 {
		return fmt.Errorf("runtime build output is not an executable regular file: %s", out)
	}
	return nil
}

func ValidateRuntime(path string) error {
	info, err := os.Lstat(path)
	if err != nil {
		return err
	}
	if !info.Mode().IsRegular() {
		return fmt.Errorf("runtime is not a regular file: %s", path)
	}
	if info.Mode()&os.ModeSymlink != 0 {
		return fmt.Errorf("runtime must not be a symlink: %s", path)
	}
	if info.Mode().Perm()&0o111 == 0 {
		return fmt.Errorf("runtime is not executable: %s", path)
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	if len(data) < 64 || string(data[:4]) != "\x7fELF" {
		return fmt.Errorf("runtime is not an ELF executable: %s", path)
	}
	if data[4] != 2 || data[5] != 1 {
		return fmt.Errorf("runtime is not 64-bit little-endian ELF: %s", path)
	}
	machine := uint16(data[18]) | uint16(data[19])<<8
	if machine != 183 {
		return fmt.Errorf("runtime is not Android/ARM64 e_machine=183: %s", path)
	}
	return nil
}

func StageModule(root string, runtimeBin string, dest string) error {
	if err := ValidateRuntime(runtimeBin); err != nil {
		return err
	}
	tmp, err := os.MkdirTemp(filepath.Dir(dest), filepath.Base(dest)+"-*.tmp")
	if err != nil {
		return err
	}
	ok := false
	defer func() {
		if !ok {
			_ = os.RemoveAll(tmp)
		}
	}()
	if err := copyTree(filepath.Join(root, "module"), tmp); err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Join(tmp, RuntimeDirRel), 0o755); err != nil {
		return err
	}
	runtimes := map[string]string{"xbs": runtimeBin}
	for _, name := range []string{"XBSctl", "XtremeBSd"} {
		sibling := filepath.Join(filepath.Dir(runtimeBin), name)
		if exists(sibling) {
			runtimes[name] = sibling
			continue
		}
		built := filepath.Join(tmp, ".build", name)
		if err := buildRuntimePackage(root, runtimeBinaries[name], built, "android", "arm64"); err != nil {
			return err
		}
		runtimes[name] = built
	}
	runtimeManifest := map[string]any{}
	for _, name := range []string{"xbs", "XBSctl", "XtremeBSd"} {
		src := runtimes[name]
		if err := ValidateRuntime(src); err != nil {
			return fmt.Errorf("%s runtime invalid: %w", name, err)
		}
		rel := filepath.ToSlash(filepath.Join(RuntimeDirRel, name))
		if err := copyFile(src, filepath.Join(tmp, filepath.FromSlash(rel)), 0o755); err != nil {
			return err
		}
		runtimeManifest[name] = map[string]any{"kind": "go-native", "goos": "android", "goarch": "arm64", "path": rel, "sha256": mustSha256File(src)}
	}
	_ = os.RemoveAll(filepath.Join(tmp, ".build"))
	manifest := map[string]any{
		"schema_version": 2,
		"runtime":        runtimeManifest,
		"module":         map[string]any{"id": ModuleID},
	}
	encoded, _ := json.MarshalIndent(manifest, "", "  ")
	encoded = append(encoded, '\n')
	if err := os.WriteFile(filepath.Join(tmp, "runtime", "arm64-v8a", "runtime-manifest.json"), encoded, 0o644); err != nil {
		return err
	}
	purged := []string{"yq-update.sh", "bin/yq", "system/bin/bash", "system/bin/bc"}
	for _, rel := range purged {
		if err := os.RemoveAll(filepath.Join(tmp, rel)); err != nil {
			return err
		}
	}
	for _, rel := range []string{"action.sh", "customize.sh", "service.sh", "META-INF/com/google/android/update-binary", "system/bin/xbs", "system/bin/XBSctl", "system/bin/XtremeBSd"} {
		p := filepath.Join(tmp, rel)
		if exists(p) {
			_ = os.Chmod(p, 0o755)
		}
	}
	if err := ValidateModule(tmp); err != nil {
		return err
	}
	if err := WriteR04Checksums(tmp); err != nil {
		return fmt.Errorf("R04 checksum staging: %w", err)
	}
	if err := VerifyR04Stage(tmp); err != nil {
		return fmt.Errorf("R04 stage qualification: %w", err)
	}
	if err := CommitR04Stage(tmp, dest); err != nil {
		return fmt.Errorf("R04 transactional stage replacement: %w", err)
	}
	ok = true
	return nil
}

func ValidateModule(root string) error {
	if !exists(filepath.Join(root, "module.prop")) {
		return fmt.Errorf("missing module.prop")
	}
	prop, err := os.ReadFile(filepath.Join(root, "module.prop"))
	if err != nil {
		return err
	}
	if !strings.Contains(string(prop), "id="+ModuleID) {
		return fmt.Errorf("module.prop id is not %s", ModuleID)
	}
	for _, rel := range []string{"system/bin/xbs", "system/bin/XBSctl", "system/bin/XtremeBSd", "service.sh", RuntimeRel, "runtime/arm64-v8a/XBSctl", "runtime/arm64-v8a/XtremeBSd"} {
		info, err := os.Lstat(filepath.Join(root, rel))
		if err != nil {
			return fmt.Errorf("missing %s: %w", rel, err)
		}
		if info.Mode()&os.ModeSymlink != 0 {
			return fmt.Errorf("unsafe symlink: %s", rel)
		}
		if info.Mode().Perm()&0o111 == 0 {
			return fmt.Errorf("not executable: %s", rel)
		}
	}
	for _, rel := range []string{RuntimeRel, "runtime/arm64-v8a/XBSctl", "runtime/arm64-v8a/XtremeBSd"} {
		if err := ValidateRuntime(filepath.Join(root, rel)); err != nil {
			return err
		}
	}
	for _, rel := range []string{"yq-update.sh", "bin/yq", "system/bin/bash", "system/bin/bc"} {
		if exists(filepath.Join(root, rel)) {
			return fmt.Errorf("legacy runtime dependency must not be packaged: %s", rel)
		}
	}
	return nil
}

func Zip(root string, out string) error {
	absRoot, err := filepath.Abs(root)
	if err != nil {
		return err
	}
	absOut, err := filepath.Abs(out)
	if err != nil {
		return err
	}
	var files []string
	if err := filepath.WalkDir(absRoot, func(path string, d os.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if path == absRoot {
			return nil
		}
		absPath, _ := filepath.Abs(path)
		if absPath == absOut {
			return nil
		}
		if d.Type()&os.ModeSymlink != 0 {
			return fmt.Errorf("refusing symlink in module zip: %s", path)
		}
		if d.IsDir() {
			return nil
		}
		// R04: reject device files, FIFOs and sockets BEFORE opening a zip source.
		if !d.Type().IsRegular() {
			return fmt.Errorf("refusing nonregular module zip source: %s", path)
		}
		rel, err := filepath.Rel(absRoot, path)
		if err != nil {
			return err
		}
		files = append(files, filepath.ToSlash(rel))
		return nil
	}); err != nil {
		return err
	}
	sort.Strings(files)
	if err := os.MkdirAll(filepath.Dir(absOut), 0o755); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(filepath.Dir(absOut), filepath.Base(absOut)+"-*.tmp")
	if err != nil {
		return err
	}
	tmpName := tmp.Name()
	ok := false
	defer func() {
		if !ok {
			_ = os.Remove(tmpName)
		}
	}()
	zw := zip.NewWriter(tmp)
	for _, rel := range files {
		full := filepath.Join(absRoot, filepath.FromSlash(rel))
		info, err := os.Stat(full)
		if err != nil {
			_ = zw.Close()
			_ = tmp.Close()
			return err
		}
		header, err := zip.FileInfoHeader(info)
		if err != nil {
			_ = zw.Close()
			_ = tmp.Close()
			return err
		}
		header.Name = rel
		header.Method = zip.Deflate
		header.Modified = deterministicTime
		header.SetMode(info.Mode().Perm())
		w, err := zw.CreateHeader(header)
		if err != nil {
			_ = zw.Close()
			_ = tmp.Close()
			return err
		}
		in, err := os.Open(full)
		if err != nil {
			_ = zw.Close()
			_ = tmp.Close()
			return err
		}
		_, copyErr := io.Copy(w, in)
		closeErr := in.Close()
		if copyErr != nil {
			_ = zw.Close()
			_ = tmp.Close()
			return copyErr
		}
		if closeErr != nil {
			_ = zw.Close()
			_ = tmp.Close()
			return closeErr
		}
	}
	if err := zw.Close(); err != nil {
		_ = tmp.Close()
		return err
	}
	if err := tmp.Sync(); err != nil {
		_ = tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	// Check exact module ZIP bytes before publishing the artifact.
	if err := VerifyModuleZip(tmpName); err != nil {
		return fmt.Errorf("R04 ZIP source and layout safety: %w", err)
	}
	if err := os.Rename(tmpName, absOut); err != nil {
		return err
	}
	if dir, err := os.Open(filepath.Dir(absOut)); err == nil {
		_ = dir.Sync()
		_ = dir.Close()
	}
	ok = true
	return nil
}

func Install(zipPath string) error {
	// Source/CI operation must never launch a live root installer.
	// Prepare an offline exact-hash candidate with xbsrepo install-command.
	return errors.New("R04 refuses implicit live module installation; use install-command for a verified operator-reviewed candidate")
}

func installShell(abs string) string {
	q := shellQuote(abs)
	return strings.Join([]string{
		"if command -v ksud >/dev/null 2>&1; then exec ksud module install " + q + "; fi",
		"if [ -x /data/adb/ksud ]; then exec /data/adb/ksud module install " + q + "; fi",
		"if command -v magisk >/dev/null 2>&1; then exec magisk --install-module " + q + "; fi",
		"echo 'no supported Magisk/KernelSU module installer found in root shell' >&2",
		"exit 127",
	}, "\n")
}

func shellQuote(s string) string { return "'" + strings.ReplaceAll(s, "'", "'\\''") + "'" }

func FinalSeal(root string) error {
	if err := runGoTests(root); err != nil {
		return err
	}
	if err := VerifyGoNativePurity(root); err != nil {
		return err
	}
	if err := VerifyRepositoryHandoff(root); err != nil {
		return err
	}
	runtimeDir := filepath.Join(root, ".tmp", "xbs-finalseal-runtimes")
	stage := filepath.Join(root, ".tmp", "xbs-finalseal-module")
	zipOut := filepath.Join(root, ".tmp", "XtremeBS-finalseal.zip")
	zipOut2 := filepath.Join(root, ".tmp", "XtremeBS-finalseal-repeat.zip")
	runtimes, err := BuildRuntimeSet(root, runtimeDir, "android", "arm64")
	if err != nil {
		return err
	}
	if err := StageModule(root, runtimes["xbs"], stage); err != nil {
		return err
	}
	if err := Zip(stage, zipOut); err != nil {
		return err
	}
	if err := Zip(stage, zipOut2); err != nil {
		return err
	}
	first, err := sha256File(zipOut)
	if err != nil {
		return err
	}
	second, err := sha256File(zipOut2)
	if err != nil {
		return err
	}
	if first != second {
		return fmt.Errorf("module ZIP is not deterministic: %s != %s", first, second)
	}
	if err := ValidateModule(stage); err != nil {
		return err
	}
	for _, rel := range []string{"xbs-finalseal-runtimes", "xbs-finalseal-module", "XtremeBS-finalseal.zip", "XtremeBS-finalseal-repeat.zip"} {
		_ = os.RemoveAll(filepath.Join(root, ".tmp", rel))
	}
	return nil
}

func VerifyRepositoryHandoff(root string) error {
	repo := filepath.Clean(filepath.Join(root, "..", ".."))
	// Standalone xbsctl owns its own Devtool manifest. Retain the legacy
	// parent lookup for historical tests and monorepo-shaped fixtures.
	if exists(filepath.Join(root, ".devtool.toml")) {
		repo = root
	}
	devtool := filepath.Join(repo, ".devtool.toml")
	if exists(devtool) {
		data, err := os.ReadFile(devtool)
		if err != nil {
			return fmt.Errorf("repository handoff scan failed for .devtool.toml: %w", err)
		}
		text := string(data)
		for _, token := range []string{"-e apps/xbs", "compileall -q apps/flowspace apps/http-cmd apps/ksun apps/sortify apps/wallglide apps/xbs"} {
			if strings.Contains(text, token) {
				return fmt.Errorf("legacy Python XBS Devtool suite reference remains: %s", token)
			}
		}
		hasCommandTarget := strings.Contains(text, "[targets.xbs]") && (strings.Contains(text, "type = \"command\"") || strings.Contains(text, "runner = \"command\""))
		hasLegacyGoTest := strings.Contains(text, "go test ./...")
		hasWorkflowGoTest := strings.Contains(text, "[targets.xbs.workflows]") &&
			strings.Contains(text, "\"job:xbs-go-test\"") &&
			strings.Contains(text, "[jobs.xbs-go-test]") &&
			strings.Contains(text, "command = [\"go\", \"test\", \"./...\"]")
		hasNativeGoTarget := strings.Contains(text, `[targets.xbs]
root = "."
runner = "go"`)
		hasNativeGoTest := strings.Contains(text, `[jobs.xbs-go-test]
runner = "go"
root = "."
action = "test"`)
		hasNativeGoVet := strings.Contains(text, `[jobs.xbs-go-vet]
runner = "go"
root = "."
action = "lint"`)
		hasNativeGoSeal := strings.Contains(text, `[jobs.xbs-final-seal]
runner = "command"
root = "."
command = ["go", "run", "./cmd/xbsrepo", "final-seal"]`)
		hasNativeGoWorkflows := strings.Contains(text, "[targets.xbs.workflows]") &&
			strings.Contains(text, "job:xbs-go-test") &&
			strings.Contains(text, "job:xbs-go-vet") &&
			strings.Contains(text, "job:xbs-final-seal") &&
			strings.Contains(text, "job:xbs-build-module")
		legacyHandoff := hasCommandTarget && (hasLegacyGoTest || hasWorkflowGoTest)
		nativeHandoff := hasNativeGoTarget && hasNativeGoTest && hasNativeGoVet && hasNativeGoSeal && hasNativeGoWorkflows
		if !legacyHandoff && !nativeHandoff {
			return fmt.Errorf("Devtool xbs target is not sealed as a Go execution target")
		}
	}
	ignore := filepath.Join(repo, ".gitignore")
	if exists(ignore) {
		data, err := os.ReadFile(ignore)
		if err != nil {
			return fmt.Errorf("repository handoff scan failed for .gitignore: %w", err)
		}
		expectedIgnore := "apps/xbs/.tmp/"
		if repo == root {
			expectedIgnore = ".tmp/"
		}
		if !strings.Contains(string(data), expectedIgnore) {
			return fmt.Errorf("%s is not ignored", expectedIgnore)
		}
	}
	generator := filepath.Join(repo, "tools", "generate_cli_artifacts.py")
	if exists(generator) {
		data, err := os.ReadFile(generator)
		if err != nil {
			return fmt.Errorf("repository handoff scan failed for tools/generate_cli_artifacts.py: %w", err)
		}
		if strings.Contains(string(data), "xbs_cli.cli") {
			return fmt.Errorf("legacy Python XBS generator reference remains: tools/generate_cli_artifacts.py")
		}
	}
	readme := filepath.Join(repo, "README.md")
	if exists(readme) {
		data, err := os.ReadFile(readme)
		if err != nil {
			return fmt.Errorf("repository handoff scan failed for README.md: %w", err)
		}
		if strings.Contains(string(data), "apps/xbs/install.py") {
			return fmt.Errorf("legacy Python XBS README install command remains")
		}
	}
	return nil
}

func VerifyGoNativePurity(root string) error {
	for _, rel := range forbiddenProductionPaths {
		if exists(filepath.Join(root, rel)) {
			return fmt.Errorf("legacy production path must be removed for XGO12: %s", rel)
		}
	}
	for _, rel := range forbiddenProductionDirs {
		if hasFiles(filepath.Join(root, rel)) {
			return fmt.Errorf("legacy production directory must be empty or removed for XGO12: %s", rel)
		}
	}
	for _, rel := range goNativeProductionFiles {
		path := filepath.Join(root, rel)
		data, err := os.ReadFile(path)
		if err != nil {
			return fmt.Errorf("purity scan failed for %s: %w", rel, err)
		}
		text := string(data)
		for _, token := range forbiddenProductionTokens {
			if strings.Contains(text, token) {
				return fmt.Errorf("legacy production token %q remains in %s", token, rel)
			}
		}
	}
	return nil
}

var forbiddenProductionPaths = []string{
	"pyproject.toml",
	"install.py",
	"src/xbs_cli/__init__.py",
	"src/xbs_cli/cli.py",
	"src/xbs_cli/compatibility.py",
	"src/xbs_cli/core.py",
	"src/xbs_cli/event_bridge.py",
	"bin/yq",
	"bin/xbs-paths.sh",
	"yq-update.sh",
	"module/bin/yq",
	"module/bin/xbs-paths.sh",
	"module/yq-update.sh",
	"system/bin/bash",
	"system/bin/bc",
	"module/system/bin/bash",
	"module/system/bin/bc",
	"system/bin/legacy/XBSctl.sh",
	"system/bin/legacy/XtremeBSd.sh",
	"module/system/bin/legacy/XBSctl.sh",
	"module/system/bin/legacy/XtremeBSd.sh",
}

var forbiddenProductionDirs = []string{
	"src/xbs_cli",
	"system/bin/legacy",
	"module/system/bin/legacy",
}

var goNativeProductionFiles = []string{
	"cmd/xbs/main.go",
	"cmd/XBSctl/main.go",
	"cmd/XtremeBSd/main.go",
	"cmd/xbsrepo/main.go",
	"internal/cli/cli.go",
	"internal/compat/compat.go",
	"internal/config/config.go",
	"internal/control/control.go",
	"internal/daemon/daemon.go",
	"internal/eventbridge/eventbridge.go",
	"module/customize.sh",
	"module/service.sh",
	"module/system/bin/xbs",
	"module/system/bin/XBSctl",
	"module/system/bin/XtremeBSd",
	"system/bin/xbs",
	"system/bin/XBSctl",
	"system/bin/XtremeBSd",
}

var forbiddenProductionTokens = []string{
	"xbs_cli",
	"mike-cli-kit",
	"rich>=",
	"python -m",
	"python3 ",
	"/system/bin/bash",
	"system/bin/bash",
	"system/bin/bc",
	"bin/yq",
	"yq-update",
	"notImplemented(",
	"has not implemented production command",
}

func runGoTests(root string) error {
	cmd := exec.Command("go", "test", "./...")
	cmd.Dir = root
	var out bytes.Buffer
	cmd.Stdout = &out
	cmd.Stderr = &out
	if err := cmd.Run(); err != nil {
		return fmt.Errorf("go test ./... failed: %w: %s", err, strings.TrimSpace(out.String()))
	}
	return nil
}

func copyTree(src, dst string) error {
	return filepath.WalkDir(src, func(path string, d os.DirEntry, err error) error {
		if err != nil {
			return err
		}
		rel, err := filepath.Rel(src, path)
		if err != nil {
			return err
		}
		if rel == "." {
			return os.MkdirAll(dst, 0o755)
		}
		to := filepath.Join(dst, rel)
		if d.Type()&os.ModeSymlink != 0 {
			return fmt.Errorf("refusing to copy symlink: %s", path)
		}
		info, err := d.Info()
		if err != nil {
			return err
		}
		if d.IsDir() {
			return os.MkdirAll(to, info.Mode().Perm())
		}
		mode := info.Mode().Perm()
		return copyFile(path, to, mode)
	})
}

func copyFile(src, dst string, mode os.FileMode) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		return err
	}
	out, err := os.OpenFile(dst, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, mode)
	if err != nil {
		return err
	}
	_, copyErr := io.Copy(out, in)
	syncErr := out.Sync()
	closeErr := out.Close()
	if copyErr != nil {
		return copyErr
	}
	if syncErr != nil {
		return syncErr
	}
	if closeErr != nil {
		return closeErr
	}
	return os.Chmod(dst, mode)
}

func mustSha256File(path string) string {
	digest, err := sha256File(path)
	if err != nil {
		return ""
	}
	return digest
}

func sha256File(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return "", err
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

func exists(path string) bool { _, err := os.Lstat(path); return err == nil }

func hasFiles(path string) bool {
	found := false
	_ = filepath.WalkDir(path, func(current string, d os.DirEntry, err error) error {
		if err != nil || found {
			return nil
		}
		if !d.IsDir() {
			found = true
		}
		return nil
	})
	return found
}

func assertNoError(err error) {
	if err != nil {
		panic(err)
	}
}

var _ = errors.Is
