package config

import (
    "crypto/sha256"
    "encoding/json"
    "fmt"
    "os"
    "path/filepath"
    "strings"
    "testing"
)

func TestR05BSourceDigestBindsExactValidatedBytes(t *testing.T) {
    dir := t.TempDir()
    t.Setenv("XBS_EXTERNAL_ROOT", dir)
    t.Setenv("XBS_EXTERNAL_CONFIG", filepath.Join(dir, "XtremeBS.json"))
    t.Setenv("XBS_RUNTIME_DIR", filepath.Join(dir, "runtime"))
    _, missing, err := LoadWithSourceDigest()
    if err != nil || missing != "" { t.Fatalf("missing legacy config must not invent generation %q %v", missing, err) }
    valid, err := json.Marshal(Default())
    if err != nil { t.Fatal(err) }
    file := ResolvePaths().Config
    if err := os.WriteFile(file, valid, 0600); err != nil { t.Fatal(err) }
    first, got, err := LoadWithSourceDigest()
    expected := fmt.Sprintf("%x", sha256.Sum256(valid))
    if err != nil || got != expected || first == nil { t.Fatalf("wrong loaded generation %q vs %q: %v", got, expected, err) }
    // Byte-distinct but semantically equivalent JSON is a distinct generation.
    changed := append([]byte(" \n"), valid...)
    if err := os.WriteFile(file, changed, 0600); err != nil { t.Fatal(err) }
    _, changedHash, err := LoadWithSourceDigest()
    if err != nil || changedHash == got || changedHash != fmt.Sprintf("%x", sha256.Sum256(changed)) { t.Fatalf("stale digest: %q %v",changedHash,err) }
}

func TestR05BSourceDigestRejectsMalformedSymlinkAndOversize(t *testing.T) {
    dir := t.TempDir()
    t.Setenv("XBS_EXTERNAL_ROOT", dir)
    t.Setenv("XBS_EXTERNAL_CONFIG", filepath.Join(dir,"XtremeBS.json"))
    t.Setenv("XBS_RUNTIME_DIR", filepath.Join(dir,"runtime"))
    target := ResolvePaths().Config
    if err := os.WriteFile(target, []byte("{"), 0600); err != nil {t.Fatal(err)}
    if _,sha,err := LoadWithSourceDigest(); err==nil || sha!="" {t.Fatalf("malformed config accepted: %q %v",sha,err)}
    if err := os.WriteFile(target, []byte(strings.Repeat(" ",2*1024*1024+1)), 0600);err!=nil{t.Fatal(err)}
    if _,sha,err := LoadWithSourceDigest();err==nil||sha!=""{t.Fatalf("oversized config accepted: %q %v",sha,err)}
    if err := os.Remove(target);err!=nil{t.Fatal(err)}
    secret := filepath.Join(dir,"secret.json")
    if err := os.WriteFile(secret, []byte("{}"), 0600);err!=nil{t.Fatal(err)}
    if err := os.Symlink(secret,target);err!=nil{t.Skipf("symlink unsupported: %v",err)}
    if _,sha,err := LoadWithSourceDigest();err==nil||sha!=""{t.Fatalf("symlink followed: %q %v",sha,err)}
}
