package eventbridge

import (
	"bytes"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"
)

const (
	FormatVersion    = 1
	Algorithm        = "hmac-sha256"
	MaxSourceBytes   = 256_000
	MaxEnvelopeBytes = 512_000
)

type BridgeError struct {
	Code    string
	Message string
	Cause   error
}

func (e *BridgeError) Error() string { return e.Message }
func (e *BridgeError) Unwrap() error { return e.Cause }

func errCode(code, message string, cause error) *BridgeError {
	return &BridgeError{Code: code, Message: message, Cause: cause}
}

type Paths struct {
	Root     string
	Runtime  string
	Key      string
	Envelope string
	Sequence string
	Event    string
	Status   string
}

func DefaultPaths() Paths {
	root := getenv("XBS_EVENT_BRIDGE_ROOT", "/data/adb/XtremeBS")
	runtime := getenv("XBS_RUNTIME_DIR", "/data/local/tmp/XtremeBS")
	return Paths{
		Root:     root,
		Runtime:  runtime,
		Key:      getenv("XBS_EVENT_BRIDGE_KEY", filepath.Join(root, "event_bridge_hmac_v1.key")),
		Envelope: getenv("XBS_EVENT_BRIDGE_FILE", filepath.Join(runtime, "event_bridge_v1.json")),
		Sequence: getenv("XBS_EVENT_BRIDGE_SEQUENCE", filepath.Join(runtime, "event_bridge_v1.seq")),
		Event:    getenv("XBS_EVENT_CHANGES_FILE", filepath.Join(runtime, "event_changes.json")),
		Status:   getenv("XBS_EXTERNAL_STATUS", "/sdcard/.config/xbs/logs/XtremeBS.status"),
	}
}

func getenv(key, def string) string {
	if value := strings.TrimSpace(os.Getenv(key)); value != "" {
		return value
	}
	return def
}

func CanonicalJSON(value any) []byte {
	encoded, _ := json.Marshal(value)
	return encoded
}

func lstat(path string, missingOK bool) (os.FileInfo, error) {
	info, err := os.Lstat(path)
	if err != nil {
		if missingOK && errors.Is(err, os.ErrNotExist) {
			return nil, nil
		}
		if errors.Is(err, os.ErrNotExist) {
			return nil, errCode("missing", "Missing bridge input: "+path, err)
		}
		return nil, errCode("unreadable", "Bridge path is unreadable: "+path, err)
	}
	if info.Mode()&os.ModeSymlink != 0 {
		return nil, errCode("symlink_rejected", "Symbolic-link bridge path is not allowed: "+path, nil)
	}
	return info, nil
}

func rejectSymlinkComponents(path string) error {
	clean := filepath.Clean(path)
	if clean == "." || clean == string(filepath.Separator) {
		return nil
	}
	current := "."
	parts := strings.Split(clean, string(filepath.Separator))
	if filepath.IsAbs(clean) {
		current = string(filepath.Separator)
		parts = parts[1:]
	}
	for _, part := range parts {
		if part == "" || part == "." {
			continue
		}
		current = filepath.Join(current, part)
		info, err := lstat(current, true)
		if err != nil {
			return err
		}
		if info == nil {
			continue
		}
		if info.Mode()&os.ModeSymlink != 0 {
			return errCode("symlink_rejected", "Symbolic-link path component is not allowed: "+current, nil)
		}
	}
	return nil
}

func boundedRead(path string, maximum int64, private bool) ([]byte, error) {
	if err := rejectSymlinkComponents(filepath.Dir(path)); err != nil {
		return nil, err
	}
	info, err := lstat(path, false)
	if err != nil {
		return nil, err
	}
	if !info.Mode().IsRegular() {
		return nil, errCode("invalid_path", "Bridge input is not a regular file: "+path, nil)
	}
	if private {
		if err := validatePrivateFile(path, info); err != nil {
			return nil, err
		}
	}
	if info.Size() <= 0 || info.Size() > maximum {
		return nil, errCode("oversized", fmt.Sprintf("Bridge input size is outside 1..%d: %s", maximum, path), nil)
	}
	file, err := os.Open(path)
	if err != nil {
		return nil, errCode("unreadable", "Bridge input is unreadable: "+path, err)
	}
	defer file.Close()
	opened, err := file.Stat()
	if err != nil {
		return nil, errCode("unreadable", "Bridge input is unreadable: "+path, err)
	}
	if !sameFile(info, opened) {
		return nil, errCode("path_changed", "Bridge input changed while opening: "+path, nil)
	}
	data, err := io.ReadAll(io.LimitReader(file, maximum+1))
	if err != nil {
		return nil, errCode("unreadable", "Bridge input is unreadable: "+path, err)
	}
	if int64(len(data)) > maximum {
		return nil, errCode("oversized", fmt.Sprintf("Bridge input exceeds %d: %s", maximum, path), nil)
	}
	if len(data) == 0 {
		return nil, errCode("oversized", "Bridge input is empty: "+path, nil)
	}
	return data, nil
}

func sameFile(a, b os.FileInfo) bool {
	as, aok := a.Sys().(*syscall.Stat_t)
	bs, bok := b.Sys().(*syscall.Stat_t)
	if !aok || !bok {
		return os.SameFile(a, b)
	}
	return as.Dev == bs.Dev && as.Ino == bs.Ino
}

func ownerUID(info os.FileInfo) uint32 {
	if stat, ok := info.Sys().(*syscall.Stat_t); ok {
		return stat.Uid
	}
	return ^uint32(0)
}

func validatePrivateParent(path string) error {
	info, err := lstat(path, false)
	if err != nil {
		return err
	}
	if !info.IsDir() {
		return errCode("key_permissions", "Private bridge parent is not a directory: "+path, nil)
	}
	if ownerUID(info) != uint32(os.Geteuid()) {
		return errCode("key_permissions", "Private bridge parent has the wrong owner: "+path, nil)
	}
	if info.Mode().Perm()&0o077 != 0 {
		return errCode("key_permissions", "Private bridge parent is accessible by group or other users: "+path, nil)
	}
	return nil
}

func validatePrivateFile(path string, info os.FileInfo) error {
	if !info.Mode().IsRegular() {
		return errCode("key_permissions", "Private bridge path is not a regular file: "+path, nil)
	}
	if ownerUID(info) != uint32(os.Geteuid()) {
		return errCode("key_permissions", "Private bridge file has the wrong owner: "+path, nil)
	}
	if info.Mode().Perm() != 0o600 {
		return errCode("key_permissions", "Private bridge file must already have mode 0600: "+path, nil)
	}
	return nil
}

func validateTrustedSourceFile(path string, info os.FileInfo) error {
	if !info.Mode().IsRegular() {
		return errCode("source_permissions", "Bridge source is not a regular file: "+path, nil)
	}
	if ownerUID(info) != uint32(os.Geteuid()) {
		return errCode("source_permissions", "Bridge source has the wrong owner: "+path, nil)
	}
	if info.Mode().Perm()&0o022 != 0 {
		return errCode("source_permissions", "Bridge source is writable by group or other users: "+path, nil)
	}
	return nil
}

func preparePrivateParent(path string) error {
	if err := rejectSymlinkComponents(path); err != nil {
		return err
	}
	if err := os.MkdirAll(path, 0o700); err != nil {
		return err
	}
	_ = os.Chmod(path, 0o700)
	return validatePrivateParent(path)
}

func atomicWrite(path string, data []byte, mode os.FileMode) error {
	if err := rejectSymlinkComponents(filepath.Dir(path)); err != nil {
		return err
	}
	existing, err := lstat(path, true)
	if err != nil {
		return err
	}
	if existing != nil && !existing.Mode().IsRegular() {
		return errCode("symlink_rejected", "Bridge output path is not a regular file: "+path, nil)
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	if err := rejectSymlinkComponents(filepath.Dir(path)); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(filepath.Dir(path), filepath.Base(path)+".*.tmp")
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
	if _, err := tmp.Write(data); err != nil {
		_ = tmp.Close()
		return err
	}
	if err := tmp.Chmod(mode); err != nil {
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
	if err := os.Rename(tmpName, path); err != nil {
		return err
	}
	if dir, err := os.Open(filepath.Dir(path)); err == nil {
		_ = dir.Sync()
		_ = dir.Close()
	}
	ok = true
	return nil
}

func withPublicationLock(path string, fn func() error) error {
	if err := preparePrivateParent(filepath.Dir(path)); err != nil {
		return err
	}
	existing, err := lstat(path, true)
	if err != nil {
		return err
	}
	if existing != nil {
		if err := validatePrivateFile(path, existing); err != nil {
			return err
		}
	}
	fd, err := syscall.Open(path, syscall.O_RDWR|syscall.O_CREAT|syscall.O_CLOEXEC|syscall.O_NOFOLLOW, 0o600)
	if err != nil {
		return errCode("lock_unavailable", "Unable to open event bridge publication lock: "+path, err)
	}
	defer syscall.Close(fd)
	_ = syscall.Fchmod(fd, 0o600)
	var stat syscall.Stat_t
	if err := syscall.Fstat(fd, &stat); err != nil {
		return err
	}
	if stat.Uid != uint32(os.Geteuid()) || stat.Mode&syscall.S_IFMT != syscall.S_IFREG {
		return errCode("key_permissions", "Event bridge lock is not privately owned: "+path, nil)
	}
	if err := syscall.Flock(fd, syscall.LOCK_EX); err != nil {
		return err
	}
	defer syscall.Flock(fd, syscall.LOCK_UN)
	return fn()
}

func jsonObject(path string, fallback map[string]any) (map[string]any, error) {
	info, err := lstat(path, true)
	if err != nil {
		return nil, err
	}
	if info == nil || info.Size() == 0 {
		return cloneMap(fallback), nil
	}
	if !info.Mode().IsRegular() {
		return nil, errCode("invalid_path", "Bridge source is not a regular file: "+path, nil)
	}
	if err := validateTrustedSourceFile(path, info); err != nil {
		return nil, err
	}
	data, err := boundedRead(path, MaxSourceBytes, false)
	if err != nil {
		return nil, err
	}
	var value map[string]any
	if err := json.Unmarshal(data, &value); err != nil {
		return nil, errCode("invalid_source_json", "Invalid JSON source: "+path, err)
	}
	if value == nil {
		return nil, errCode("invalid_source_json", "Bridge source is not a JSON object: "+path, nil)
	}
	return value, nil
}

func cloneMap(in map[string]any) map[string]any {
	out := map[string]any{}
	for k, v := range in {
		out[k] = v
	}
	return out
}

func ReadKey(path string) ([]byte, error) {
	if err := validatePrivateParent(filepath.Dir(path)); err != nil {
		return nil, err
	}
	data, err := boundedRead(path, 128, true)
	if err != nil {
		return nil, err
	}
	if len(data) != 32 {
		return nil, errCode("invalid_key", "Event bridge key must contain exactly 32 bytes", nil)
	}
	return data, nil
}

func EnsureKey(path string) ([]byte, error) {
	if err := preparePrivateParent(filepath.Dir(path)); err != nil {
		return nil, err
	}
	existing, err := lstat(path, true)
	if err != nil {
		return nil, err
	}
	if existing != nil {
		return ReadKey(path)
	}
	data := make([]byte, 32)
	if _, err := rand.Read(data); err != nil {
		return nil, err
	}
	if err := atomicWrite(path, data, 0o600); err != nil {
		return nil, err
	}
	return ReadKey(path)
}

func nextSequence(path string) (int, error) {
	current := 0
	existing, err := lstat(path, true)
	if err != nil {
		return 0, err
	}
	if existing != nil {
		if err := validatePrivateFile(path, existing); err != nil {
			return 0, err
		}
		data, err := boundedRead(path, 64, true)
		if err != nil {
			return 0, err
		}
		parsed, err := strconv.Atoi(strings.TrimSpace(string(data)))
		if err != nil {
			return 0, errCode("invalid_sequence", "Stored event bridge sequence is invalid", err)
		}
		if parsed < 0 {
			return 0, errCode("invalid_sequence", "Stored event bridge sequence is negative", nil)
		}
		current = parsed
	}
	sequence := current + 1
	if err := atomicWrite(path, []byte(fmt.Sprintf("%d\n", sequence)), 0o600); err != nil {
		return 0, err
	}
	return sequence, nil
}

func KeyID(key []byte) string {
	digest := sha256.Sum256(key)
	return hex.EncodeToString(digest[:])[:16]
}

func Publish(paths Paths, now *int64) (map[string]any, error) {
	lockPath := paths.Sequence + ".lock"
	var result map[string]any
	err := withPublicationLock(lockPath, func() error {
		key, err := EnsureKey(paths.Key)
		if err != nil {
			return err
		}
		event, err := jsonObject(paths.Event, map[string]any{"safe_mode": false, "last_change": nil, "profiles": map[string]any{}, "active": []any{}, "inactive": []any{}})
		if err != nil {
			return err
		}
		status, err := jsonObject(paths.Status, map[string]any{"mode": "unknown", "active_profiles": []any{}})
		if err != nil {
			return err
		}
		issued := time.Now().Unix()
		if now != nil {
			issued = *now
		}
		sequence, err := nextSequence(paths.Sequence)
		if err != nil {
			return err
		}
		signed := map[string]any{
			"algorithm": Algorithm,
			"format":    FormatVersion,
			"issued_at": issued,
			"key_id":    KeyID(key),
			"payload":   map[string]any{"event": event, "status": status},
			"sequence":  sequence,
		}
		mac := hmac.New(sha256.New, key)
		mac.Write(CanonicalJSON(signed))
		envelope := cloneMap(signed)
		envelope["mac"] = hex.EncodeToString(mac.Sum(nil))
		encoded := append(CanonicalJSON(envelope), '\n')
		if len(encoded) > MaxEnvelopeBytes {
			return errCode("oversized", "Authenticated event envelope exceeds the size limit", nil)
		}
		if err := atomicWrite(paths.Envelope, encoded, 0o600); err != nil {
			return err
		}
		info, err := lstat(paths.Envelope, false)
		if err != nil {
			return err
		}
		if err := validatePrivateFile(paths.Envelope, info); err != nil {
			return err
		}
		result = map[string]any{
			"authenticated":   true,
			"algorithm":       Algorithm,
			"format":          FormatVersion,
			"issued_at":       issued,
			"key_id":          signed["key_id"],
			"sequence":        sequence,
			"envelope_digest": sha256Hex(CanonicalJSON(envelope)),
		}
		return nil
	})
	return result, err
}

func Verify(paths Paths) (map[string]any, error) {
	key, err := ReadKey(paths.Key)
	if err != nil {
		return nil, err
	}
	data, err := boundedRead(paths.Envelope, MaxEnvelopeBytes, true)
	if err != nil {
		return nil, err
	}
	var envelope map[string]any
	if err := json.Unmarshal(data, &envelope); err != nil {
		return nil, errCode("invalid_json", "Authenticated event envelope is not valid JSON", err)
	}
	if envelope == nil {
		return nil, errCode("invalid_envelope", "Authenticated event envelope is not an object", nil)
	}
	format, ok := numberInt(envelope["format"])
	if !ok || format != FormatVersion {
		return nil, errCode("unsupported_format", "Unsupported authenticated event bridge format", nil)
	}
	if envelope["algorithm"] != Algorithm {
		return nil, errCode("unsupported_algorithm", "Unsupported authenticated event bridge algorithm", nil)
	}
	sequence, ok := numberInt(envelope["sequence"])
	if !ok || sequence < 1 {
		return nil, errCode("invalid_sequence", "Authenticated event sequence is invalid", nil)
	}
	issuedAt, ok := numberInt64(envelope["issued_at"])
	if !ok || issuedAt < 1 {
		return nil, errCode("invalid_timestamp", "Authenticated event timestamp is invalid", nil)
	}
	keyID, _ := envelope["key_id"].(string)
	if keyID != KeyID(key) {
		return nil, errCode("key_mismatch", "Authenticated event key identifier does not match", nil)
	}
	payload, ok := envelope["payload"].(map[string]any)
	if !ok {
		return nil, errCode("invalid_payload", "Authenticated event payload must contain event and status objects", nil)
	}
	if _, ok := payload["event"].(map[string]any); !ok {
		return nil, errCode("invalid_payload", "Authenticated event payload must contain event and status objects", nil)
	}
	if _, ok := payload["status"].(map[string]any); !ok {
		return nil, errCode("invalid_payload", "Authenticated event payload must contain event and status objects", nil)
	}
	macText, ok := envelope["mac"].(string)
	if !ok || len(macText) != 64 || !isHex(macText) {
		return nil, errCode("invalid_mac", "Authenticated event MAC is invalid", nil)
	}
	signed := map[string]any{}
	for k, v := range envelope {
		if k != "mac" {
			signed[k] = v
		}
	}
	mac := hmac.New(sha256.New, key)
	mac.Write(CanonicalJSON(signed))
	expected := hex.EncodeToString(mac.Sum(nil))
	if !hmac.Equal([]byte(expected), []byte(strings.ToLower(macText))) {
		return nil, errCode("bad_mac", "Authenticated event envelope failed HMAC verification", nil)
	}
	return map[string]any{
		"authenticated":   true,
		"algorithm":       Algorithm,
		"format":          FormatVersion,
		"issued_at":       issuedAt,
		"key_id":          keyID,
		"sequence":        sequence,
		"payload":         payload,
		"envelope_digest": sha256Hex(CanonicalJSON(envelope)),
		"verified_at":     time.Now().Unix(),
	}, nil
}

func VerificationPayload(paths Paths) map[string]any {
	bridge, err := Verify(paths)
	if err == nil {
		return map[string]any{"bridge": bridge}
	}
	code := "error"
	if varr := (*BridgeError)(nil); errors.As(err, &varr) {
		code = varr.Code
	}
	return map[string]any{"bridge": map[string]any{"authenticated": false, "reason_code": code, "detail": err.Error()}}
}

func numberInt(v any) (int, bool) {
	switch x := v.(type) {
	case int:
		return x, true
	case int64:
		return int(x), true
	case float64:
		if x == float64(int(x)) {
			return int(x), true
		}
	}
	return 0, false
}

func numberInt64(v any) (int64, bool) {
	switch x := v.(type) {
	case int:
		return int64(x), true
	case int64:
		return x, true
	case float64:
		if x == float64(int64(x)) {
			return int64(x), true
		}
	}
	return 0, false
}

func isHex(s string) bool {
	for _, ch := range s {
		if !(ch >= '0' && ch <= '9' || ch >= 'a' && ch <= 'f' || ch >= 'A' && ch <= 'F') {
			return false
		}
	}
	return true
}

func sha256Hex(data []byte) string {
	digest := sha256.Sum256(data)
	return hex.EncodeToString(digest[:])
}

func CompareDigest(a, b []byte) bool { return bytes.Equal(a, b) }
