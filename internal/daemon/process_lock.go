package daemon

import (
	"fmt"
	"os"
	"path/filepath"
	"syscall"

	"github.com/Mikeyphw/xbsctl/internal/config"
)

// Hold a process-scoped, nonblocking advisory lock across recovery and the
// entire control loop. Two root daemons must never process the same .work.
func acquireDaemonLock() (*os.File, error) {
	runtime := config.ResolvePaths().Runtime
	if err := os.MkdirAll(runtime, 0700); err != nil {
		return nil, err
	}
	path := filepath.Join(runtime, "xbs-daemon.lock")
	fd, err := syscall.Open(path, syscall.O_RDWR|syscall.O_CREAT|syscall.O_NOFOLLOW|syscall.O_CLOEXEC, 0600)
	if err != nil {
		return nil, fmt.Errorf("open daemon lock: %w", err)
	}
	f := os.NewFile(uintptr(fd), path)
	st, err := f.Stat()
	if err != nil || !st.Mode().IsRegular() {
		f.Close()
		return nil, fmt.Errorf("unsafe daemon lock: %v", err)
	}
	if err := syscall.Flock(int(f.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
		f.Close()
		return nil, fmt.Errorf("another daemon owns the runtime: %w", err)
	}
	return f, nil
}
