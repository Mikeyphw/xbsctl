package module

import (
	"strings"
	"testing"
)

func TestR09EModuleCandidateEvidenceRefusals(t *testing.T) {
	base := R04InstallCandidate{Manager: "kernelsu", Repository: "Mikeyphw/xbsctl", Commit: strings.Repeat("a", 40), RunID: 1, RunAttempt: 1, Artifact: "xbs-android-module-1-1", SHA256: strings.Repeat("b", 64), ZipPath: "relative.zip"}
	for _, tc := range []struct {
		name   string
		mutate func(*R04InstallCandidate)
	}{
		{"foreign-repository", func(x *R04InstallCandidate) { x.Repository = "other/repo" }},
		{"short-commit", func(x *R04InstallCandidate) { x.Commit = "abc" }},
		{"uppercase-commit", func(x *R04InstallCandidate) { x.Commit = strings.Repeat("A", 40) }},
		{"zero-run", func(x *R04InstallCandidate) { x.RunID = 0 }},
		{"zero-attempt", func(x *R04InstallCandidate) { x.RunAttempt = 0 }},
		{"path-artifact", func(x *R04InstallCandidate) { x.Artifact = "../escape" }},
		{"missing-digest", func(x *R04InstallCandidate) { x.SHA256 = "" }},
		{"relative-zip", func(x *R04InstallCandidate) {}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			x := base
			tc.mutate(&x)
			cmd, err := QualifiedInstallCommand(x)
			if err == nil || cmd != "" {
				t.Fatalf("unqualified installation offered: %q %v", cmd, err)
			}
		})
	}
	if err := VerifyR04ZipHash("does-not-exist.zip", "not-a-sha"); err == nil {
		t.Fatal("invalid expected digest accepted")
	}
}
