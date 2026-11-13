package config

import (
    "crypto/sha256"
    "encoding/json"
    "errors"
    "fmt"
    "os"
    "strings"
)

// LoadWithSourceDigest returns a validated config and the SHA-256 of the *same*
// on-disk bytes parsed into that config. It does not log or expose config data.
// Missing legacy configuration returns defaults without a purported generation.
func LoadWithSourceDigest() (map[string]any, string, error) {
    if err := EnsureDirs(); err != nil { return nil, "", err }
    cfg := Default()
    path := ResolvePaths().Config
    info, err := os.Lstat(path)
    if errors.Is(err, os.ErrNotExist) { return cfg, "", nil }
    if err != nil { return nil, "", err }
    if !info.Mode().IsRegular() { return nil, "", fmt.Errorf("config is not a regular file") }
    // Keep the same upper bound as the Android configuration transaction writer.
    if info.Size() > 2*1024*1024 { return nil, "", fmt.Errorf("config exceeds bounded size") }
    data, err := os.ReadFile(path)
    if err != nil { return nil, "", err }
    if len(data) > 2*1024*1024 { return nil, "", fmt.Errorf("config exceeds bounded size") }
    if len(strings.TrimSpace(string(data))) == 0 { return nil, "", fmt.Errorf("config is empty: %s", path) }
    var raw map[string]any
    if err := json.Unmarshal(data, &raw); err != nil { return nil, "", fmt.Errorf("config is invalid JSON: %w", err) }
    deepUpdate(cfg, raw)
    if errs := Validate(cfg); len(errs) > 0 {
        return nil, "", fmt.Errorf("config validation failed: %s", strings.Join(errs, "; "))
    }
    sum := sha256.Sum256(data)
    return cfg, fmt.Sprintf("%x", sum), nil
}
