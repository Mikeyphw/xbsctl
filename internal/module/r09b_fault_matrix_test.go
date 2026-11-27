package module

import (
	"archive/zip"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func r09bArchive(t *testing.T, members []string, symlink bool) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "candidate.zip")
	f, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	z := zip.NewWriter(f)
	for _, name := range members {
		hdr := &zip.FileHeader{Name: name, Method: zip.Store}
		if symlink {
			hdr.SetMode(os.ModeSymlink | 0777)
		} else {
			hdr.SetMode(0644)
		}
		w, e := z.CreateHeader(hdr)
		if e != nil {
			t.Fatal(e)
		}
		if _, e = w.Write([]byte("R09B")); e != nil {
			t.Fatal(e)
		}
	}
	if err = z.Close(); err != nil {
		t.Fatal(err)
	}
	if err = f.Close(); err != nil {
		t.Fatal(err)
	}
	return path
}
func TestR09BMalformedZipMatrix(t *testing.T) {
	cases := []struct {
		name    string
		entries []string
		symlink bool
		reason  string
	}{
		{"parent_traversal", []string{"../escape"}, false, "unsafe zip member path"},
		{"absolute_path", []string{"/absolute"}, false, "unsafe zip member path"},
		{"duplicate_member", []string{"duplicate", "duplicate"}, false, "duplicate/case-conflicting"},
		{"case_collision", []string{"Duplicate", "duplicate"}, false, "duplicate/case-conflicting"},
		{"backslash", []string{"dir\\unsafe"}, false, "unsafe zip member path"},
		{"double_slash", []string{"dir//unsafe"}, false, "unsafe zip member path"},
		{"symlink_member", []string{"symlink"}, true, "unsafe module zip member type"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			archive := r09bArchive(t, tc.entries, tc.symlink)
			if err := VerifyModuleZip(archive); err == nil || !strings.Contains(err.Error(), tc.reason) {
				t.Fatalf("unsafe zip not rejected for security reason %q: %v", tc.reason, err)
			}
		})
	}
}
func TestR09BInvalidZipContainer(t *testing.T) {
	path := filepath.Join(t.TempDir(), "broken.zip")
	if err := os.WriteFile(path, []byte("not a zip archive"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := VerifyModuleZip(path); err == nil {
		t.Fatal("invalid bytes accepted")
	}
	empty := r09bArchive(t, nil, false)
	if err := VerifyModuleZip(empty); err == nil || !strings.Contains(err.Error(), "invalid module zip member count") {
		t.Fatalf("empty zip accepted or wrong refusal: %v", err)
	}
}
