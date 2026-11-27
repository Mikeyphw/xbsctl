package module

import (
	"archive/zip"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"time"
)

// R04 is an OFFLINE module staging/verification protocol. Nothing in this
// file invokes su, magisk, ksud or modifies installed /data/adb modules.
const R04ReceiptSchema = "xbs-r04-module-transaction/v1"
const maxR04Entry = 64 << 20
const maxR04Archive = 192 << 20

var r04SafeArtifact = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$`)
var r04SHA = regexp.MustCompile(`^[0-9a-f]{64}$`)

type R04Receipt struct {
	Schema     string `json:"schema"`
	State      string `json:"state"`
	Operation  string `json:"operation"`
	Stage      string `json:"stage"`
	Previous   string `json:"previous,omitempty"`
	Digest     string `json:"sha256,omitempty"`
	StartedUTC string `json:"started_utc"`
	Info       string `json:"info,omitempty"`
}

type R04InstallCandidate struct {
	Manager    string `json:"manager"`
	Repository string `json:"repository"`
	Commit     string `json:"commit"`
	RunID      int64  `json:"run_id"`
	RunAttempt int    `json:"run_attempt"`
	Artifact   string `json:"artifact"`
	SHA256     string `json:"sha256"`
	ZipPath    string `json:"zip_path"`
}

// QualifiedInstallCommand never executes the shell command. An exact source
// and artifact identity are necessary but NOT sufficient for hosted CI trust.
func QualifiedInstallCommand(x R04InstallCandidate) (string, error) {
	if x.Repository != "Mikeyphw/xbsctl" || len(x.Commit) != 40 || !regexp.MustCompile(`^[a-f0-9]{40}$`).MatchString(x.Commit) || x.RunID <= 0 || x.RunAttempt <= 0 || !r04SafeArtifact.MatchString(x.Artifact) || !r04SHA.MatchString(x.SHA256) {
		return "", errors.New("incomplete or invalid source-bound artifact evidence")
	}
	if !filepath.IsAbs(x.ZipPath) {
		return "", errors.New("module zip requires an absolute path")
	}
	if err := VerifyModuleZip(x.ZipPath); err != nil {
		return "", fmt.Errorf("module zip qualification refused: %w", err)
	}
	got, err := r04HashFile(x.ZipPath)
	if err != nil {
		return "", err
	}
	if got != x.SHA256 {
		return "", errors.New("module zip digest does not match source-bound evidence")
	}
	tool := ""
	switch x.Manager {
	case "kernelsu", "ksu":
		tool = "ksud module install "
	case "magisk":
		tool = "magisk --install-module "
	default:
		return "", errors.New("R04 requires explicit manager: kernelsu or magisk")
	}
	return "su -c " + shellQuote(tool+shellQuote(x.ZipPath)), nil
}

// The returned command is a candidate for operator review. It must not be
// labeled CI-qualified without independently checking the actual hosted run.
func r04HashFile(p string) (string, error) {
	f, e := os.Open(p)
	if e != nil {
		return "", e
	}
	defer f.Close()
	h := sha256.New()
	if _, e = io.Copy(h, f); e != nil {
		return "", e
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}
func r04SyncDir(p string) error {
	f, e := os.Open(p)
	if e != nil {
		return e
	}
	defer f.Close()
	return f.Sync()
}
func r04Regular(p string) (os.FileInfo, error) {
	i, e := os.Lstat(p)
	if e != nil {
		return nil, e
	}
	if !i.Mode().IsRegular() || i.Mode()&os.ModeSymlink != 0 {
		return nil, fmt.Errorf("unsafe nonregular file: %s", p)
	}
	return i, nil
}
func r04NoSymlinkRoot(root string) error {
	clean := filepath.Clean(root)
	if !filepath.IsAbs(clean) || clean == string(os.PathSeparator) {
		return errors.New("unsafe module root")
	}
	cur := string(os.PathSeparator)
	for _, component := range strings.Split(strings.TrimPrefix(clean, string(os.PathSeparator)), string(os.PathSeparator)) {
		if component == "" {
			continue
		}
		cur = filepath.Join(cur, component)
		i, e := os.Lstat(cur)
		if errors.Is(e, os.ErrNotExist) {
			continue
		}
		if e != nil {
			return e
		}
		if i.Mode()&os.ModeSymlink != 0 {
			return fmt.Errorf("symlink in module root: %s", cur)
		}
	}
	return nil
}

// VerifyR04Stage validates *all* files before any staged-tree replacement.
func VerifyR04Stage(root string) error {
	if err := r04NoSymlinkRoot(root); err != nil {
		return err
	}
	if err := ValidateModule(root); err != nil {
		return err
	}
	if err := filepath.WalkDir(root, func(p string, d os.DirEntry, e error) error {
		if e != nil {
			return e
		}
		if d.Type()&os.ModeSymlink != 0 {
			return fmt.Errorf("symlink in staged module: %s", p)
		}
		if !d.IsDir() && !d.Type().IsRegular() {
			return fmt.Errorf("unexpected module file type: %s", p)
		}
		return nil
	}); err != nil {
		return err
	}
	data, e := os.ReadFile(filepath.Join(root, "runtime/arm64-v8a/runtime-manifest.json"))
	if e != nil {
		return e
	}
	var m struct {
		SchemaVersion int `json:"schema_version"`
		Runtime       map[string]struct {
			Kind   string `json:"kind"`
			GOOS   string `json:"goos"`
			GOARCH string `json:"goarch"`
			Path   string `json:"path"`
			SHA    string `json:"sha256"`
		} `json:"runtime"`
		Module struct {
			ID string `json:"id"`
		} `json:"module"`
	}
	if e = json.Unmarshal(data, &m); e != nil {
		return e
	}
	if m.SchemaVersion != 2 || m.Module.ID != ModuleID || len(m.Runtime) != 3 {
		return errors.New("runtime manifest schema/id/binary count mismatch")
	}
	var shaLines []string
	for _, name := range []string{"xbs", "XBSctl", "XtremeBSd"} {
		v, ok := m.Runtime[name]
		want := "runtime/arm64-v8a/" + name
		if !ok || v.Kind != "go-native" || v.GOOS != "android" || v.GOARCH != "arm64" || v.Path != want || !r04SHA.MatchString(v.SHA) {
			return fmt.Errorf("runtime manifest mismatch for %s", name)
		}
		full := filepath.Join(root, filepath.FromSlash(want))
		if err := ValidateRuntime(full); err != nil {
			return err
		}
		digest, err := r04HashFile(full)
		if err != nil {
			return err
		}
		if digest != v.SHA {
			return fmt.Errorf("runtime binary hash mismatch for %s", name)
		}
		shaLines = append(shaLines, digest+"  "+want)
	}
	// The standalone checksum file is deliberately consumable by the root
	// install environment without jq, Python or other legacy dependencies.
	checks, e := os.ReadFile(filepath.Join(root, "runtime/arm64-v8a/runtime.sha256"))
	if e != nil {
		return fmt.Errorf("runtime checksum seal missing: %w", e)
	}
	if string(checks) != strings.Join(shaLines, "\n")+"\n" {
		return errors.New("runtime checksum seal differs from manifest")
	}
	if err := r04ValidateProps(root); err != nil {
		return err
	}
	for _, rel := range []string{"customize.sh", "service.sh", "action.sh", "META-INF/com/google/android/update-binary"} {
		i, e := r04Regular(filepath.Join(root, rel))
		if e != nil {
			return e
		}
		if i.Mode().Perm()&0111 == 0 {
			return fmt.Errorf("installer script is not executable: %s", rel)
		}
	}
	return nil
}

func r04ValidateProps(root string) error {
	raw, e := os.ReadFile(filepath.Join(root, "module.prop"))
	if e != nil {
		return e
	}
	vals := map[string]string{}
	for _, line := range strings.Split(string(raw), "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		k, v, ok := strings.Cut(line, "=")
		if !ok || k == "" {
			return errors.New("malformed module.prop")
		}
		if _, dup := vals[k]; dup {
			return errors.New("duplicate module.prop key")
		}
		vals[k] = v
	}
	if vals["id"] != ModuleID || vals["version"] == "" || vals["versionCode"] == "" {
		return errors.New("invalid module.prop identity/version")
	}
	if vals["updateJson"] != "" {
		return errors.New("unqualified upstream update feed must be disabled")
	}
	b, e := os.ReadFile(filepath.Join(root, "update.json"))
	if e != nil {
		return e
	}
	var v struct {
		Version        string `json:"version"`
		Code           int    `json:"versionCode"`
		UpdatesEnabled bool   `json:"updates_enabled"`
		ZipURL         string `json:"zipUrl"`
		ChangeLog      string `json:"changelog"`
	}
	if e = json.Unmarshal(b, &v); e != nil {
		return e
	}
	if v.UpdatesEnabled || v.ZipURL != "" || v.ChangeLog != "" || v.Version != vals["version"] || fmt.Sprint(v.Code) != vals["versionCode"] {
		return errors.New("unqualified update.json metadata differs from module.prop")
	}
	return nil
}

// VerifyR04LegacyStage permits backup/rollback of an earlier Go-native stage
// that predates the standalone runtime.sha256 seal. It independently checks
// all manifest hashes without modifying that existing stage or accepting its
// unqualified update feed as a source of installation authority.
func VerifyR04LegacyStage(root string) error {
	if err := r04NoSymlinkRoot(root); err != nil {
		return err
	}
	if err := ValidateModule(root); err != nil {
		return err
	}
	b, err := os.ReadFile(filepath.Join(root, "runtime/arm64-v8a/runtime-manifest.json"))
	if err != nil {
		return err
	}
	var m struct {
		SchemaVersion int `json:"schema_version"`
		Module        struct {
			ID string `json:"id"`
		} `json:"module"`
		Runtime map[string]struct {
			SHA    string `json:"sha256"`
			Path   string `json:"path"`
			GOOS   string `json:"goos"`
			GOARCH string `json:"goarch"`
		} `json:"runtime"`
	}
	if err = json.Unmarshal(b, &m); err != nil {
		return err
	}
	if m.SchemaVersion != 2 || m.Module.ID != ModuleID || len(m.Runtime) != 3 {
		return errors.New("legacy stage manifest schema/identity mismatch")
	}
	for _, n := range []string{"xbs", "XBSctl", "XtremeBSd"} {
		r, ok := m.Runtime[n]
		p := "runtime/arm64-v8a/" + n
		if !ok || r.Path != p || r.GOOS != "android" || r.GOARCH != "arm64" || !r04SHA.MatchString(r.SHA) {
			return fmt.Errorf("legacy stage manifest invalid: %s", n)
		}
		f := filepath.Join(root, p)
		if err := ValidateRuntime(f); err != nil {
			return err
		}
		sum, err := r04HashFile(f)
		if err != nil {
			return err
		}
		if sum != r.SHA {
			return fmt.Errorf("legacy stage binary hash mismatch: %s", n)
		}
	}
	return nil
}

// WriteR04Checksums runs only on a private newly staged tree. The manifest
// already contains all three hashes; this emits an independently checkable
// install-time checksum seal.
func WriteR04Checksums(root string) error {
	paths := []string{"xbs", "XBSctl", "XtremeBSd"}
	var lines []string
	for _, n := range paths {
		rel := "runtime/arm64-v8a/" + n
		sum, e := r04HashFile(filepath.Join(root, rel))
		if e != nil {
			return e
		}
		lines = append(lines, sum+"  "+rel)
	}
	return os.WriteFile(filepath.Join(root, "runtime/arm64-v8a/runtime.sha256"), []byte(strings.Join(lines, "\n")+"\n"), 0644)
}

// VerifyModuleZip refuses traversal, case-insensitive collisions, symlinks,
// FIFO/device entries, extra-large members and invalid manifest hashes.
func VerifyModuleZip(zipPath string) error {
	if _, e := r04Regular(zipPath); e != nil {
		return e
	}
	zr, e := zip.OpenReader(zipPath)
	if e != nil {
		return e
	}
	defer zr.Close()
	if len(zr.File) == 0 || len(zr.File) > 512 {
		return errors.New("invalid module zip member count")
	}
	dir, e := os.MkdirTemp("", "xbs-r04-zip-*")
	if e != nil {
		return e
	}
	defer os.RemoveAll(dir)
	seen := map[string]bool{}
	total := uint64(0)
	for _, f := range zr.File {
		n := f.Name
		clean := path.Clean(n)
		if n == "" || strings.HasPrefix(n, "/") || strings.Contains(n, "\\") || n != clean || clean == "." || clean == ".." || strings.HasPrefix(clean, "../") || strings.Contains(n, "//") || strings.ContainsRune(n, 0) || strings.Contains(n, ":") {
			return fmt.Errorf("unsafe zip member path: %q", n)
		}
		low := strings.ToLower(n)
		if seen[low] {
			return fmt.Errorf("duplicate/case-conflicting module zip member: %q", n)
		}
		seen[low] = true
		mode := f.Mode()
		if !mode.IsRegular() || mode&os.ModeSymlink != 0 {
			return fmt.Errorf("unsafe module zip member type: %q", n)
		}
		if f.UncompressedSize64 > maxR04Entry || f.CompressedSize64 > maxR04Entry {
			return fmt.Errorf("oversized module zip member: %q", n)
		}
		total += f.UncompressedSize64
		if total > maxR04Archive {
			return errors.New("module zip expands beyond maximum")
		}
		out := filepath.Join(dir, filepath.FromSlash(n))
		if e = os.MkdirAll(filepath.Dir(out), 0755); e != nil {
			return e
		}
		r, e := f.Open()
		if e != nil {
			return e
		}
		w, e := os.OpenFile(out, os.O_WRONLY|os.O_CREATE|os.O_EXCL, mode.Perm())
		if e != nil {
			r.Close()
			return e
		}
		written, copyErr := io.Copy(w, io.LimitReader(r, int64(maxR04Entry)+1))
		closeErr := w.Close()
		r.Close()
		if copyErr != nil {
			return copyErr
		}
		if closeErr != nil {
			return closeErr
		}
		if written != int64(f.UncompressedSize64) {
			return fmt.Errorf("zip member size mismatch: %s", n)
		}
		if e = os.Chmod(out, mode.Perm()); e != nil {
			return e
		}
	}
	return VerifyR04Stage(dir)
}

func r04WriteReceipt(p string, x R04Receipt) error {
	x.Schema = R04ReceiptSchema
	if x.StartedUTC == "" {
		x.StartedUTC = time.Now().UTC().Format(time.RFC3339Nano)
	}
	b, e := json.MarshalIndent(x, "", "  ")
	if e != nil {
		return e
	}
	b = append(b, '\n')
	if e = r04NoSymlinkRoot(filepath.Dir(p)); e != nil {
		return e
	}
	tmp, e := os.CreateTemp(filepath.Dir(p), ".r04-receipt-*.tmp")
	if e != nil {
		return e
	}
	name := tmp.Name()
	defer os.Remove(name)
	if _, e = tmp.Write(b); e != nil {
		tmp.Close()
		return e
	}
	if e = tmp.Chmod(0600); e != nil {
		tmp.Close()
		return e
	}
	if e = tmp.Sync(); e != nil {
		tmp.Close()
		return e
	}
	if e = tmp.Close(); e != nil {
		return e
	}
	if e = os.Rename(name, p); e != nil {
		return e
	}
	return r04SyncDir(filepath.Dir(p))
}
func r04StageExists(p string) (bool, error) {
	i, e := os.Lstat(p)
	if errors.Is(e, os.ErrNotExist) {
		return false, nil
	}
	if e != nil {
		return false, e
	}
	if !i.IsDir() || i.Mode()&os.ModeSymlink != 0 {
		return false, fmt.Errorf("unsafe stage path: %s", p)
	}
	return true, nil
}

// CommitR04Stage is the only permitted staged replacement. It leaves a
// rollback copy until an explicit operator decision. A preexisting backup or
// pending receipt is an unresolved transaction, not an invitation to delete.
func CommitR04Stage(prepared, dest string) error {
	if prepared == dest || filepath.Dir(prepared) != filepath.Dir(dest) {
		return errors.New("stage replacement requires distinct paths in the same parent")
	}
	if e := r04NoSymlinkRoot(filepath.Dir(dest)); e != nil {
		return e
	}
	if e := VerifyR04Stage(prepared); e != nil {
		return fmt.Errorf("prepared stage rejected: %w", e)
	}
	backup := dest + ".r04.previous"
	receipt := dest + ".r04.receipt.json"
	// Never stack transactions on top of a failed staged replacement.
	if _, e := os.Lstat(dest + ".r04.failed"); e == nil {
		return errors.New("retired failed stage requires explicit reconciliation")
	} else if !errors.Is(e, os.ErrNotExist) {
		return e
	}
	if _, e := os.Lstat(backup); e == nil {
		return errors.New("previous backup needs explicit reconciliation")
	} else if !errors.Is(e, os.ErrNotExist) {
		return e
	}
	if _, e := os.Lstat(receipt); e == nil {
		b, readErr := os.ReadFile(receipt)
		if readErr != nil {
			return readErr
		}
		var x R04Receipt
		if _, err := r04Regular(receipt); err != nil {
			return err
		}
		if json.Unmarshal(b, &x) != nil || x.Schema != R04ReceiptSchema || x.Stage != dest ||
			(x.State != "staged" && x.State != "accepted" && x.State != "rolled_back") {
			return errors.New("unrecognized or unfinished module transaction requires reconciliation")
		}
	} else if !errors.Is(e, os.ErrNotExist) {
		return e
	}
	had, e := r04StageExists(dest)
	if e != nil {
		return e
	}
	if had {
		if e = VerifyR04Stage(dest); e != nil {
			if legacyErr := VerifyR04LegacyStage(dest); legacyErr != nil {
				return fmt.Errorf("existing stage is invalid; refusal to replace: R04=%v legacy=%w", e, legacyErr)
			}
		}
	}
	intent := R04Receipt{State: "intent", Operation: "stage-upgrade", Stage: dest, Previous: backup}
	if !had {
		intent.Operation = "stage-fresh"
	}
	if e = r04WriteReceipt(receipt, intent); e != nil {
		return e
	}
	if had {
		if e = os.Rename(dest, backup); e != nil {
			return fmt.Errorf("retain previous stage: %w", e)
		}
		if e = r04SyncDir(filepath.Dir(dest)); e != nil {
			return e
		}
	}
	if e = os.Rename(prepared, dest); e != nil {
		if had {
			if r := os.Rename(backup, dest); r != nil {
				return errors.Join(e, fmt.Errorf("rollback previous stage failed: %w", r))
			}
			_ = r04SyncDir(filepath.Dir(dest))
		}
		_ = r04WriteReceipt(receipt, R04Receipt{State: "rolled_back", Operation: intent.Operation, Stage: dest, Previous: backup, Info: e.Error()})
		return fmt.Errorf("install new stage: %w", e)
	}
	if e = r04SyncDir(filepath.Dir(dest)); e != nil {
		return e
	}
	if e = VerifyR04Stage(dest); e != nil {
		return fmt.Errorf("postcommit stage validation failed (backup retained): %w", e)
	}
	digest, e := r04HashFile(filepath.Join(dest, "runtime/arm64-v8a/runtime-manifest.json"))
	if e != nil {
		return e
	}
	return r04WriteReceipt(receipt, R04Receipt{State: "staged", Operation: intent.Operation, Stage: dest, Previous: backup, Digest: digest})
}

// r04PendingUpgradeReceipt refuses unrelated/forged-looking receipt shapes.
// Receipts are local lifecycle records, NOT cryptographic authenticity proofs.
func r04PendingUpgradeReceipt(dest string) (R04Receipt, error) {
	p := dest + ".r04.receipt.json"
	if _, err := r04Regular(p); err != nil {
		return R04Receipt{}, fmt.Errorf("missing/unsafe upgrade receipt: %w", err)
	}
	b, err := os.ReadFile(p)
	if err != nil {
		return R04Receipt{}, err
	}
	var r R04Receipt
	if err := json.Unmarshal(b, &r); err != nil {
		return R04Receipt{}, err
	}
	if r.Schema != R04ReceiptSchema || r.Stage != dest || r.Previous != dest+".r04.previous" ||
		r.Operation != "stage-upgrade" || (r.State != "intent" && r.State != "staged") {
		return R04Receipt{}, errors.New("rollback/finalize requires a matching pending upgrade receipt")
	}
	return r, nil
}

// RollbackR04Stage handles both a fully staged upgrade and an interrupted
// post-intent rename, including an absent/invalid current stage. It retains
// any current tree under .r04.failed instead of deleting it. An absent backup,
// a suspicious receipt, or a preexisting retired tree causes a refusal.
func RollbackR04Stage(dest string) error {
	if err := r04NoSymlinkRoot(filepath.Dir(dest)); err != nil {
		return err
	}
	backup := dest + ".r04.previous"
	retired := dest + ".r04.failed"
	receipt := dest + ".r04.receipt.json"
	if _, err := r04PendingUpgradeReceipt(dest); err != nil {
		return err
	}
	if err := VerifyR04Stage(backup); err != nil {
		if legacyErr := VerifyR04LegacyStage(backup); legacyErr != nil {
			return fmt.Errorf("verified rollback backup required: R04=%v legacy=%w", err, legacyErr)
		}
	}
	if _, err := os.Lstat(retired); err == nil {
		return errors.New("previous failed stage exists; refusing overwrite")
	} else if !errors.Is(err, os.ErrNotExist) {
		return err
	}
	present, err := r04StageExists(dest)
	if err != nil {
		return err
	}
	if err := r04WriteReceipt(receipt, R04Receipt{State: "intent", Operation: "rollback", Stage: dest, Previous: backup}); err != nil {
		return err
	}
	if present {
		if err := os.Rename(dest, retired); err != nil {
			return err
		}
		if err := r04SyncDir(filepath.Dir(dest)); err != nil {
			return err
		}
	}
	if err := os.Rename(backup, dest); err != nil {
		if present {
			if r := os.Rename(retired, dest); r != nil {
				return errors.Join(err, r)
			}
		}
		return err
	}
	if err := r04SyncDir(filepath.Dir(dest)); err != nil {
		return err
	}
	return r04WriteReceipt(receipt, R04Receipt{State: "rolled_back", Operation: "rollback", Stage: dest, Previous: retired, Info: "Offline staged tree restored; installed Android module unchanged"})
}

// PlanR04Uninstall only generates a receipt; it deliberately never invokes
// a root module manager or unlinks live device files.
func PlanR04Uninstall(dest string) (R04Receipt, error) {
	if err := VerifyR04Stage(dest); err != nil {
		return R04Receipt{}, err
	}
	return R04Receipt{Schema: R04ReceiptSchema, State: "review_required", Operation: "uninstall", Stage: dest, Info: "No device or installed module changed; explicit external root approval required"}, nil
}

func R04StageInventory(root string) ([]string, error) {
	var a []string
	e := filepath.WalkDir(root, func(p string, d os.DirEntry, e error) error {
		if e != nil {
			return e
		}
		if !d.IsDir() {
			r, _ := filepath.Rel(root, p)
			a = append(a, filepath.ToSlash(r))
		}
		return nil
	})
	sort.Strings(a)
	return a, e
}

// FinalizeR04Stage removes an older retained staged backup only after the
// current stage is reverified and an explicit caller confirms acceptance.
// It never manipulates /data/adb/modules or executes a root command.
func FinalizeR04Stage(dest string, ack string) error {
	if ack != "I_ACCEPT_VERIFIED_STAGE" {
		return errors.New("explicit stage acceptance acknowledgement required")
	}
	if err := VerifyR04Stage(dest); err != nil {
		return err
	}
	receipt, err := r04PendingUpgradeReceipt(dest)
	if err != nil {
		return err
	}
	if receipt.State != "staged" {
		return errors.New("finalize requires a completed staged upgrade, not an interrupted intent")
	}
	manifestSum, err := r04HashFile(filepath.Join(dest, "runtime/arm64-v8a/runtime-manifest.json"))
	if err != nil || receipt.Digest != manifestSum {
		return errors.New("finalize refuses an unbound or changed current stage")
	}
	backup := dest + ".r04.previous"
	if _, err := r04StageExists(backup); err != nil {
		return err
	}
	if err := VerifyR04Stage(backup); err != nil {
		if legacyErr := VerifyR04LegacyStage(backup); legacyErr != nil {
			return fmt.Errorf("refuse to remove unverified backup: %w", legacyErr)
		}
	}
	if err := os.RemoveAll(backup); err != nil {
		return err
	}
	if err := r04SyncDir(filepath.Dir(dest)); err != nil {
		return err
	}
	return r04WriteReceipt(dest+".r04.receipt.json", R04Receipt{State: "accepted", Operation: "finalize", Stage: dest, Info: "Previous staged backup explicitly removed; no installed module changed"})
}

// VerifyR04ZipHash is a pure local integrity gate, not a substitute for a
// real successful GitHub Actions run or for live Android module acceptance.
func VerifyR04ZipHash(zipPath, expected string) error {
	if !r04SHA.MatchString(expected) {
		return errors.New("invalid expected SHA256")
	}
	if err := VerifyModuleZip(zipPath); err != nil {
		return err
	}
	got, err := r04HashFile(zipPath)
	if err != nil {
		return err
	}
	if got != expected {
		return errors.New("module ZIP checksum mismatch")
	}
	return nil
}
