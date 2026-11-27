package daemon

import (
	"errors"
	"fmt"
	"io"
	"os"
	"strings"
	"syscall"
)

// Legacy one-line control files are supported for compatibility only. Refuse
// symlinks, devices, FIFOs, and oversized files; never follow an injected
// privileged control path. New clients should use the durable queue.
func consumeLegacyControl(path string) (string, error) {
	fd, err := syscall.Open(path, syscall.O_RDWR|syscall.O_NOFOLLOW|syscall.O_NONBLOCK|syscall.O_CLOEXEC, 0)
	if errors.Is(err, os.ErrNotExist) {
		return "", nil
	}
	if err != nil {
		return "", fmt.Errorf("open legacy control safely: %w", err)
	}
	f := os.NewFile(uintptr(fd), path)
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		return "", err
	}
	if !st.Mode().IsRegular() || st.Size() > 4096 {
		return "", errors.New("unsafe or oversized legacy control file")
	}
	b, err := io.ReadAll(io.LimitReader(f, 4097))
	if err != nil {
		return "", err
	}
	if len(b) > 4096 {
		return "", errors.New("oversized legacy control command")
	}
	cmd := strings.TrimSpace(string(b))
	if cmd == "" {
		return "", nil
	}
	if err := f.Truncate(0); err != nil {
		return "", err
	}
	if _, err := f.Seek(0, io.SeekStart); err != nil {
		return "", err
	}
	if _, err := f.Write([]byte("\n")); err != nil {
		return "", err
	}
	if err := f.Sync(); err != nil {
		return "", err
	}
	return cmd, nil
}
