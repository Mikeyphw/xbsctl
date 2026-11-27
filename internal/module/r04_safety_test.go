package module

import (
	"archive/zip"
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func fakeStage(t *testing.T, name string, marker byte) string {
	t.Helper()
	d := filepath.Join(t.TempDir(), name)
	files := map[string][]byte{
		"module.prop": []byte("id=XtremeBS\nname=Xtreme Battery Saver\nversion=v2.1.6\nversionCode=216\n"),
		"update.json": []byte("{\"version\":\"v2.1.6\",\"versionCode\":216,\"updates_enabled\":false,\"zipUrl\":\"\",\"changelog\":\"\"}"),
		"service.sh":  []byte("#!/system/bin/sh\n"), "action.sh": []byte("#!/system/bin/sh\n"), "customize.sh": []byte("#!/system/bin/sh\n"), "META-INF/com/google/android/update-binary": []byte("#!/sbin/sh\n"),
		"system/bin/xbs": []byte("#!/system/bin/sh\n"), "system/bin/XBSctl": []byte("#!/system/bin/sh\n"), "system/bin/XtremeBSd": []byte("#!/system/bin/sh\n"),
	}
	m := map[string]any{"schema_version": 2, "module": map[string]any{"id": "XtremeBS"}}
	rt := map[string]any{}
	for _, n := range []string{"xbs", "XBSctl", "XtremeBSd"} {
		b := make([]byte, 64)
		copy(b, []byte("\x7fELF"))
		b[4] = 2
		b[5] = 1
		b[18] = 183
		b[24] = marker
		rel := "runtime/arm64-v8a/" + n
		files[rel] = b
		sum := sha256.Sum256(b)
		rt[n] = map[string]any{"kind": "go-native", "goos": "android", "goarch": "arm64", "path": rel, "sha256": hex.EncodeToString(sum[:])}
	}
	m["runtime"] = rt
	bb, _ := json.Marshal(m)
	files["runtime/arm64-v8a/runtime-manifest.json"] = bb
	for k, v := range files {
		p := filepath.Join(d, k)
		if err := os.MkdirAll(filepath.Dir(p), 0755); err != nil {
			t.Fatal(err)
		}
		mode := os.FileMode(0644)
		if strings.HasSuffix(k, ".sh") || strings.HasPrefix(k, "system/bin/") || strings.HasSuffix(k, "update-binary") || strings.HasPrefix(k, "runtime/arm64-v8a/") && !(strings.HasSuffix(k, "json") || strings.HasSuffix(k, "sha256")) {
			mode = 0755
		}
		if err := os.WriteFile(p, v, mode); err != nil {
			t.Fatal(err)
		}
	}
	if err := WriteR04Checksums(d); err != nil {
		t.Fatal(err)
	}
	if err := VerifyR04Stage(d); err != nil {
		t.Fatal(err)
	}
	return d
}
func makeArchive(t *testing.T, stage string) string {
	t.Helper()
	p := filepath.Join(t.TempDir(), "qualified.zip")
	w, e := os.Create(p)
	if e != nil {
		t.Fatal(e)
	}
	zw := zip.NewWriter(w)
	e = filepath.WalkDir(stage, func(p string, d os.DirEntry, e error) error {
		if e != nil {
			return e
		}
		if d.IsDir() {
			return nil
		}
		rel, _ := filepath.Rel(stage, p)
		h, e := zip.FileInfoHeader(mustStat(p))
		if e != nil {
			return e
		}
		h.Name = filepath.ToSlash(rel)
		h.Method = zip.Deflate
		out, e := zw.CreateHeader(h)
		if e != nil {
			return e
		}
		b, e := os.ReadFile(p)
		if e != nil {
			return e
		}
		_, e = out.Write(b)
		return e
	})
	if e != nil {
		t.Fatal(e)
	}
	if e := zw.Close(); e != nil {
		t.Fatal(e)
	}
	w.Close()
	return p
}
func mustStat(p string) os.FileInfo {
	i, e := os.Stat(p)
	if e != nil {
		panic(e)
	}
	return i
}
func TestR04StageAndArchiveValid(t *testing.T) {
	s := fakeStage(t, "valid", 1)
	p := makeArchive(t, s)
	if e := VerifyModuleZip(p); e != nil {
		t.Fatal(e)
	}
	if e := VerifyR04Stage(s); e != nil {
		t.Fatal(e)
	}
}
func TestR04RejectsTamperedBinary(t *testing.T) {
	s := fakeStage(t, "tampered", 1)
	p := filepath.Join(s, "runtime/arm64-v8a/xbs")
	f, e := os.OpenFile(p, os.O_APPEND|os.O_WRONLY, 0)
	if e != nil {
		t.Fatal(e)
	}
	f.Write([]byte{1})
	f.Close()
	if e := VerifyR04Stage(s); e == nil {
		t.Fatal("tampered binary accepted")
	}
}
func TestR04RejectsMissingChecksum(t *testing.T) {
	s := fakeStage(t, "missing", 1)
	os.Remove(filepath.Join(s, "runtime/arm64-v8a/runtime.sha256"))
	if e := VerifyR04Stage(s); e == nil {
		t.Fatal("missing seal accepted")
	}
}
func TestR04RejectsStaleUpdateFeed(t *testing.T) {
	s := fakeStage(t, "old", 1)
	f := filepath.Join(s, "module.prop")
	os.WriteFile(f, []byte("id=XtremeBS\nversion=v2.1.6\nversionCode=216\nupdateJson=https://bad/update.json\n"), 0644)
	if e := VerifyR04Stage(s); e == nil {
		t.Fatal("unqualified remote update feed accepted")
	}
}
func TestR04RejectsSymlinkedScript(t *testing.T) {
	s := fakeStage(t, "link", 1)
	p := filepath.Join(s, "service.sh")
	os.Remove(p)
	os.Symlink("/etc/passwd", p)
	if e := VerifyR04Stage(s); e == nil {
		t.Fatal("symlink accepted")
	}
}
func beside(t *testing.T, stage, dest string) string {
	t.Helper()
	p := filepath.Join(filepath.Dir(dest), filepath.Base(stage)+".prepared")
	if err := os.Rename(stage, p); err != nil {
		t.Fatal(err)
	}
	return p
}
func TestR04TransactionalUpgradeRollback(t *testing.T) {
	old := fakeStage(t, "old", 1)
	prepared := fakeStage(t, "new", 2)
	dest := filepath.Join(t.TempDir(), "module")
	old = beside(t, old, dest)
	prepared = beside(t, prepared, dest)
	if e := CommitR04Stage(old, dest); e != nil {
		t.Fatal(e)
	}
	if e := CommitR04Stage(prepared, dest); e != nil {
		t.Fatal(e)
	}
	if e := VerifyR04Stage(dest + ".r04.previous"); e != nil {
		t.Fatal(e)
	}
	b, _ := os.ReadFile(filepath.Join(dest, "runtime/arm64-v8a/xbs"))
	if b[24] != 2 {
		t.Fatal("not upgraded")
	}
	if e := RollbackR04Stage(dest); e != nil {
		t.Fatal(e)
	}
	b, _ = os.ReadFile(filepath.Join(dest, "runtime/arm64-v8a/xbs"))
	if b[24] != 1 {
		t.Fatal("not rolled back")
	}
}
func TestR04RejectsThirdUpgradeWhilePreviousHeld(t *testing.T) {
	a := fakeStage(t, "a", 1)
	b := fakeStage(t, "b", 2)
	c := fakeStage(t, "c", 3)
	d := filepath.Join(t.TempDir(), "dest")
	a = beside(t, a, d)
	b = beside(t, b, d)
	c = beside(t, c, d)
	if e := CommitR04Stage(a, d); e != nil {
		t.Fatal(e)
	}
	if e := CommitR04Stage(b, d); e != nil {
		t.Fatal(e)
	}
	if e := CommitR04Stage(c, d); e == nil {
		t.Fatal("overwrote backup")
	}
}
func TestR04RejectsIntentReceipt(t *testing.T) {
	a := fakeStage(t, "a", 1)
	d := filepath.Join(t.TempDir(), "dest")
	if e := r04WriteReceipt(d+".r04.receipt.json", R04Receipt{State: "intent", Operation: "stage"}); e != nil {
		t.Fatal(e)
	}
	if e := CommitR04Stage(a, d); e == nil {
		t.Fatal("accepted pending intent")
	}
}
func TestR04UninstallIsReadOnly(t *testing.T) {
	s := fakeStage(t, "existing", 1)
	x, e := PlanR04Uninstall(s)
	if e != nil {
		t.Fatal(e)
	}
	if x.State != "review_required" {
		t.Fatalf("bad receipt: %v", x)
	}
	if e := VerifyR04Stage(s); e != nil {
		t.Fatal(e)
	}
}
func TestR04CandidateCommandSourcePinned(t *testing.T) {
	s := fakeStage(t, "valid", 1)
	p := makeArchive(t, s)
	sum, _ := r04HashFile(p)
	x := R04InstallCandidate{Repository: "Mikeyphw/xbsctl", Commit: strings.Repeat("a", 40), RunID: 19, RunAttempt: 1, Artifact: "XtremeBS.zip", SHA256: sum, ZipPath: p, Manager: "kernelsu"}
	cmd, e := QualifiedInstallCommand(x)
	if e != nil {
		t.Fatal(e)
	}
	if !strings.HasPrefix(cmd, "su -c '") {
		t.Fatal(cmd)
	}
	x.SHA256 = strings.Repeat("0", 64)
	if _, e := QualifiedInstallCommand(x); e == nil {
		t.Fatal("accepted wrong digest")
	}
	x.SHA256 = sum
	x.RunID = 0
	if _, e := QualifiedInstallCommand(x); e == nil {
		t.Fatal("accepted missing hosted run")
	}
}
func TestR04ManagerExplicitAndShellQuoted(t *testing.T) {
	s := fakeStage(t, "safe", 1)
	zipPath := makeArchive(t, s)
	sum, _ := r04HashFile(zipPath)
	x := R04InstallCandidate{Repository: "Mikeyphw/xbsctl", Commit: strings.Repeat("b", 40), RunID: 52, RunAttempt: 1, Artifact: "module.zip", SHA256: sum, ZipPath: zipPath}
	if _, e := QualifiedInstallCommand(x); e == nil {
		t.Fatal("default installer accepted")
	}
	x.Manager = "magisk"
	cmd, e := QualifiedInstallCommand(x)
	if e != nil {
		t.Fatal(e)
	}
	if !strings.Contains(cmd, "magisk --install-module") || !strings.HasPrefix(cmd, "su -c ") {
		t.Fatal(cmd)
	}
	x.Manager = "kernelsu"
	cmd, e = QualifiedInstallCommand(x)
	if e != nil {
		t.Fatal(e)
	}
	if !strings.Contains(cmd, "ksud module install") {
		t.Fatal(cmd)
	}
	x.Manager = "unknown"
	if _, e = QualifiedInstallCommand(x); e == nil {
		t.Fatal("unknown installer accepted")
	}
}

func TestR04ArchiveRejectsTraversalAndDuplicate(t *testing.T) {
	for _, names := range [][]string{{"../steal"}, {"a", "a"}, {"AA", "aa"}, {"/etc/passwd"}} {
		t.Run(fmt.Sprint(names), func(t *testing.T) {
			p := filepath.Join(t.TempDir(), "bad.zip")
			buf := bytes.Buffer{}
			w := zip.NewWriter(&buf)
			for _, n := range names {
				h := &zip.FileHeader{Name: n, Method: zip.Store}
				h.SetMode(0644)
				f, _ := w.CreateHeader(h)
				f.Write([]byte("fake"))
			}
			w.Close()
			os.WriteFile(p, buf.Bytes(), 0644)
			if e := VerifyModuleZip(p); e == nil {
				t.Fatal("unsafe archive accepted")
			}
		})
	}
}

func TestR04LegacyUpgradeAndRollback(t *testing.T) {
	old := fakeStage(t, "legacy", 1)
	newStage := fakeStage(t, "modern", 2)
	// Earlier R03 module builds shipped the same three native runtimes and
	// manifest but no detached checksum seal and a stale update feed.
	if err := os.Remove(filepath.Join(old, "runtime/arm64-v8a/runtime.sha256")); err != nil {
		t.Fatal(err)
	}
	os.WriteFile(filepath.Join(old, "module.prop"), []byte("id=XtremeBS\nversion=v2.1.6\nversionCode=216\nupdateJson=https://legacy.invalid/update.json\n"), 0644)
	dest := filepath.Join(t.TempDir(), "module")
	old = beside(t, old, dest)
	newStage = beside(t, newStage, dest)
	// Simulate an earlier module stage already installed into the offline path.
	if err := os.Rename(old, dest); err != nil {
		t.Fatal(err)
	}
	if err := VerifyR04LegacyStage(dest); err != nil {
		t.Fatal(err)
	}
	if err := CommitR04Stage(newStage, dest); err != nil {
		t.Fatal(err)
	}
	if err := RollbackR04Stage(dest); err != nil {
		t.Fatal(err)
	}
	b, _ := os.ReadFile(filepath.Join(dest, "runtime/arm64-v8a/xbs"))
	if b[24] != 1 {
		t.Fatal("legacy stage was not restored exactly")
	}
}
func TestR04LegacyTamperCannotBeBackedUp(t *testing.T) {
	old := fakeStage(t, "legacy", 1)
	os.Remove(filepath.Join(old, "runtime/arm64-v8a/runtime.sha256"))
	f, _ := os.OpenFile(filepath.Join(old, "runtime/arm64-v8a/xbs"), os.O_WRONLY|os.O_APPEND, 0)
	f.Write([]byte{4})
	f.Close()
	if e := VerifyR04LegacyStage(old); e == nil {
		t.Fatal("tampered legacy Go binary accepted")
	}
}
func TestR04FinalizeRequiresExplicitConsent(t *testing.T) {
	a := fakeStage(t, "a", 1)
	b := fakeStage(t, "b", 2)
	d := filepath.Join(t.TempDir(), "dest")
	a = beside(t, a, d)
	b = beside(t, b, d)
	if e := CommitR04Stage(a, d); e != nil {
		t.Fatal(e)
	}
	if e := CommitR04Stage(b, d); e != nil {
		t.Fatal(e)
	}
	if e := FinalizeR04Stage(d, ""); e == nil {
		t.Fatal("finalized backup without authorization")
	}
	if e := FinalizeR04Stage(d, "I_ACCEPT_VERIFIED_STAGE"); e != nil {
		t.Fatal(e)
	}
	if _, e := os.Lstat(d + ".r04.previous"); !os.IsNotExist(e) {
		t.Fatal("backup not finalized")
	}
}

func TestR04RefusesOrphanedFailedStageBeforeUpgrade(t *testing.T) {
	a := fakeStage(t, "new", 2)
	dest := filepath.Join(t.TempDir(), "stage")
	a = beside(t, a, dest)
	if e := os.Mkdir(dest+".r04.failed", 0700); e != nil {
		t.Fatal(e)
	}
	if e := CommitR04Stage(a, dest); e == nil {
		t.Fatal("another upgrade accepted with unreconciled failed stage")
	}
	if e := VerifyR04Stage(a); e != nil {
		t.Fatal("prepared stage was unexpectedly changed")
	}
}
func TestR04ArchiveRejectsFIFOEntry(t *testing.T) {
	f := filepath.Join(t.TempDir(), "fifo.zip")
	buf := bytes.Buffer{}
	zw := zip.NewWriter(&buf)
	h := &zip.FileHeader{Name: "fifo", Method: zip.Store}
	h.SetMode(os.ModeNamedPipe | 0600)
	w, _ := zw.CreateHeader(h)
	w.Write([]byte("x"))
	zw.Close()
	os.WriteFile(f, buf.Bytes(), 0600)
	if e := VerifyModuleZip(f); e == nil {
		t.Fatal("FIFO module ZIP member accepted")
	}
}

// Simulates a crash after the previous staged tree has been renamed but
// before the new candidate is installed. Recovery must restore the original.
func TestR04RecoverInterruptedRename(t *testing.T) {
	old := fakeStage(t, "old", 7)
	dest := filepath.Join(t.TempDir(), "module")
	old = beside(t, old, dest)
	if e := os.Rename(old, dest); e != nil {
		t.Fatal(e)
	}
	if e := r04WriteReceipt(dest+".r04.receipt.json", R04Receipt{State: "intent", Operation: "stage-upgrade", Stage: dest, Previous: dest + ".r04.previous"}); e != nil {
		t.Fatal(e)
	}
	if e := os.Rename(dest, dest+".r04.previous"); e != nil {
		t.Fatal(e)
	}
	if e := RollbackR04Stage(dest); e != nil {
		t.Fatal(e)
	}
	if e := VerifyR04Stage(dest); e != nil {
		t.Fatal(e)
	}
	if _, e := os.Lstat(dest + ".r04.previous"); !os.IsNotExist(e) {
		t.Fatal("backup should be restored into destination")
	}
}
func TestR04RecoverCorruptedCurrentWithoutDeletingEvidence(t *testing.T) {
	old := fakeStage(t, "old", 7)
	newer := fakeStage(t, "new", 8)
	dest := filepath.Join(t.TempDir(), "module")
	old = beside(t, old, dest)
	newer = beside(t, newer, dest)
	if e := CommitR04Stage(old, dest); e != nil {
		t.Fatal(e)
	}
	if e := CommitR04Stage(newer, dest); e != nil {
		t.Fatal(e)
	}
	f := filepath.Join(dest, "runtime/arm64-v8a/xbs")
	if e := os.WriteFile(f, []byte("corrupted"), 0755); e != nil {
		t.Fatal(e)
	}
	if e := RollbackR04Stage(dest); e != nil {
		t.Fatal(e)
	}
	if e := VerifyR04Stage(dest); e != nil {
		t.Fatal(e)
	}
	b, e := os.ReadFile(filepath.Join(dest+".r04.failed", "runtime/arm64-v8a/xbs"))
	if e != nil || string(b) != "corrupted" {
		t.Fatal("corrupted current tree not retained")
	}
}
func TestR04RollbackRefusesForgedReceipt(t *testing.T) {
	old := fakeStage(t, "old", 1)
	dest := filepath.Join(t.TempDir(), "module")
	if e := os.Rename(old, dest+".r04.previous"); e != nil {
		t.Fatal(e)
	}
	other := fakeStage(t, "current", 2)
	if e := os.Rename(other, dest); e != nil {
		t.Fatal(e)
	}
	if e := r04WriteReceipt(dest+".r04.receipt.json", R04Receipt{State: "staged", Operation: "stage-upgrade", Stage: "/not/this/stage", Previous: dest + ".r04.previous"}); e != nil {
		t.Fatal(e)
	}
	if e := RollbackR04Stage(dest); e == nil {
		t.Fatal("invalid receipt accepted")
	}
	if e := VerifyR04Stage(dest); e != nil {
		t.Fatal(e)
	}
}
func TestR04FinalizeRefusesUnboundDigest(t *testing.T) {
	old := fakeStage(t, "old", 1)
	dest := filepath.Join(t.TempDir(), "module")
	old = beside(t, old, dest)
	newStage := fakeStage(t, "new", 2)
	newStage = beside(t, newStage, dest)
	if e := CommitR04Stage(old, dest); e != nil {
		t.Fatal(e)
	}
	if e := CommitR04Stage(newStage, dest); e != nil {
		t.Fatal(e)
	}
	if e := r04WriteReceipt(dest+".r04.receipt.json", R04Receipt{State: "staged", Operation: "stage-upgrade", Stage: dest, Previous: dest + ".r04.previous", Digest: strings.Repeat("0", 64)}); e != nil {
		t.Fatal(e)
	}
	if e := FinalizeR04Stage(dest, "I_ACCEPT_VERIFIED_STAGE"); e == nil {
		t.Fatal("unbound digest accepted")
	}
	if e := VerifyR04Stage(dest + ".r04.previous"); e != nil {
		t.Fatal(e)
	}
}
