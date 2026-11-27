package control

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"sync/atomic"
	"syscall"
	"time"
	"unsafe"
)

// The queue is a private, same-filesystem spool. A failed durability or
// ownership check is a refusal; callers must never guess whether a root
// command executed.
const maxRequestBytes = 4096

type Request struct {
	ID       string `json:"id"`
	Command  string `json:"command"`
	Event    string `json:"event,omitempty"`
	Created  int64  `json:"created"`
	Deadline int64  `json:"deadline_unix_nano,omitempty"`
	Origin   string `json:"origin,omitempty"`
}

var requestCounter uint64

type Ack struct {
	ID        string `json:"id"`
	State     string `json:"state"`
	Error     string `json:"error,omitempty"`
	Completed int64  `json:"completed"`
}

// spoolPathComponents refuses symbolic links in every existing ancestor.
// The final directory alone is insufficient: a symlinked parent would let
// a privileged daemon read or publish in a different filesystem tree.
func spoolPathComponents(path string) error {
	for cur := filepath.Clean(path); ; cur = filepath.Dir(cur) {
		info, err := os.Lstat(cur)
		if err != nil && !errors.Is(err, os.ErrNotExist) {
			return err
		}
		if err == nil && info.Mode()&os.ModeSymlink != 0 {
			// Android's system-managed /data/data -> /data/user/0 alias
			// appears in Termux's documented private path. Permit ONLY
			// this root-owned, exact, platform-provided alias.
			if cur == "/data/data" && runtime.GOOS == "android" {
				owner, ok := info.Sys().(*syscall.Stat_t)
				target, resolveErr := filepath.EvalSymlinks(cur)
				if ok && owner.Uid == 0 && resolveErr == nil && filepath.Clean(target) == "/data/user/0" {
					continue
				}
			}
			return fmt.Errorf("symlinked queue path component: %s", cur)
		}
		parent := filepath.Dir(cur)
		if parent == cur {
			return nil
		}
	}
}

// inspectSpoolDir is strictly read-only. Never chmod during `runtime
// recovery` or while inspecting phase/ack evidence.
func inspectSpoolDir(path string) error {
	if err := spoolPathComponents(path); err != nil {
		return err
	}
	st, err := os.Lstat(path)
	if err != nil {
		return err
	}
	if st.Mode()&os.ModeSymlink != 0 || !st.IsDir() || st.Mode().Perm() != 0700 {
		return fmt.Errorf("unsafe spool directory mode/type: %s", path)
	}
	owner, ok := st.Sys().(*syscall.Stat_t)
	if !ok || owner.Uid != uint32(os.Geteuid()) {
		return fmt.Errorf("queue spool owner mismatch: %s", path)
	}
	return nil
}

func privateSpoolDir(path string, create bool) error {
	if err := spoolPathComponents(path); err != nil {
		return err
	}
	if create {
		// Legacy spools may exist with 0755. Tighten them before accessing data.
		if err := os.MkdirAll(path, 0700); err != nil {
			return err
		}
	}
	st, err := os.Lstat(path)
	if err != nil {
		return err
	}
	if st.Mode()&os.ModeSymlink != 0 || !st.IsDir() {
		return fmt.Errorf("unsafe queue directory %s", path)
	}
	// Never chmod an untrusted owner-controlled queue as privileged root.
	// The controller and daemon must use the same trusted runtime owner.
	owner, ok := st.Sys().(*syscall.Stat_t)
	if !ok || owner.Uid != uint32(os.Geteuid()) {
		return fmt.Errorf("queue spool owner mismatch: %s", path)
	}
	if err := spoolPathComponents(path); err != nil {
		return err
	}
	if st.Mode().Perm() != 0700 {
		if err := os.Chmod(path, 0700); err != nil {
			return fmt.Errorf("secure queue directory %s: %w", path, err)
		}
	}
	st, err = os.Lstat(path)
	if err != nil {
		return err
	}
	if st.Mode()&os.ModeSymlink != 0 || !st.IsDir() || st.Mode().Perm() != 0700 {
		return fmt.Errorf("queue directory not private: %s", path)
	}
	return nil
}
func syncSpoolDir(path string) error {
	f, err := os.Open(path)
	if err != nil {
		return err
	}
	defer f.Close()
	return f.Sync()
}

// no-follow is essential: the daemon runs with privileges while the client
// may submit requests. Check again after open to reject non-regular files.
func readSpoolFile(path string, limit int64) ([]byte, error) {
	fd, err := syscall.Open(path, syscall.O_RDONLY|syscall.O_NOFOLLOW|syscall.O_CLOEXEC, 0)
	if err != nil {
		return nil, err
	}
	f := os.NewFile(uintptr(fd), path)
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		return nil, err
	}
	if !st.Mode().IsRegular() || st.Size() > limit {
		return nil, fmt.Errorf("unsafe spool item size/type: %s", path)
	}
	data, err := io.ReadAll(io.LimitReader(f, limit+1))
	if err != nil {
		return nil, err
	}
	if int64(len(data)) > limit {
		return nil, fmt.Errorf("oversized spool item: %s", path)
	}
	return data, nil
}
func writeNewFile(path string, data []byte) error {
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if err != nil {
		return err
	}
	failed := true
	defer func() {
		if failed {
			_ = os.Remove(path)
		}
	}()
	if _, err = f.Write(data); err != nil {
		_ = f.Close()
		return err
	}
	if err = f.Sync(); err != nil {
		_ = f.Close()
		return err
	}
	if err = f.Close(); err != nil {
		return err
	}
	failed = false
	return nil
}

// renameNoReplace uses Linux/Android renameat2(RENAME_NOREPLACE) to publish
// an already-fsynced temporary file atomically WITHOUT overwriting an
// existing queue item or receipt. Android's app SELinux policy may prohibit
// hard links even inside Termux's own private directory, so os.Link is not a
// portable publication primitive here.
//
// Fail closed when this kernel/filesystem lacks RENAME_NOREPLACE. A normal
// os.Rename fallback would silently weaken the no-overwrite invariant.
func renameNoReplace(src, dst string) error {
	if filepath.Dir(src) != filepath.Dir(dst) {
		return errors.New("atomic queue publication requires one directory")
	}
	if runtime.GOOS != "linux" && runtime.GOOS != "android" {
		return fmt.Errorf("atomic no-replace publication unsupported on %s", runtime.GOOS)
	}
	// Linux syscall numbers: asm-generic on arm64/riscv64; x86 ABI on amd64/386;
	// ARM EABI on arm. Android uses these same Linux syscall tables.
	var nr uintptr
	switch runtime.GOARCH {
	case "amd64":
		nr = 316
	case "arm64", "riscv64":
		nr = 276
	case "386":
		nr = 353
	case "arm":
		nr = 382
	default:
		return fmt.Errorf("atomic no-replace publication unsupported on %s/%s", runtime.GOOS, runtime.GOARCH)
	}
	from, err := syscall.BytePtrFromString(src)
	if err != nil {
		return err
	}
	to, err := syscall.BytePtrFromString(dst)
	if err != nil {
		return err
	}
	const renameNoReplaceFlag uintptr = 1
	atFDCWD := -100
	_, _, errno := syscall.Syscall6(nr, uintptr(atFDCWD), uintptr(unsafe.Pointer(from)),
		uintptr(atFDCWD), uintptr(unsafe.Pointer(to)), renameNoReplaceFlag, 0)
	runtime.KeepAlive(from)
	runtime.KeepAlive(to)
	if errno != 0 {
		return &os.LinkError{Op: "renameat2-noreplace", Old: src, New: dst, Err: errno}
	}
	return nil
}

// The file is fully written and fsynced before it becomes visible at dst;
// the no-replace rename is atomic on the same filesystem. The directory sync
// makes its published name durable. On sync failure, the caller reports an
// uncertain outcome and must not replay a possibly applied privileged action.
func publishNewFile(tmp, dst string) error {
	if err := renameNoReplace(tmp, dst); err != nil {
		return err
	}
	if err := syncSpoolDir(filepath.Dir(dst)); err != nil {
		return fmt.Errorf("sync published spool item: %w", err)
	}
	return nil
}
func QueueRequest(ctl, command, event string) (Request, error) {
	return QueueRequestWithDeadline(ctl, command, event, 0)
}

// QueueRequestWithDeadline imposes only a pre-execution deadline. In-flight
// Android operations must never be interrupted by a client-side timeout.
func QueueRequestWithDeadline(ctl, command, event string, ttl time.Duration) (Request, error) {
	return queueRequestWithOrigin(ctl, command, event, ttl, "")
}

// QueueAutomaticRequest publishes through the same audited WAL and private
// Android-compatible spool. Manual clients continue using QueueRequest.
func QueueAutomaticRequest(ctl, command, event string) (Request, error) {
	if command != "start" && command != "stop" {
		return Request{}, errors.New("automatic command must be start or stop")
	}
	if event != "charging" && event != "screen_off" && event != "low_power" {
		return Request{}, errors.New("automatic event name not allowlisted")
	}
	return queueRequestWithOrigin(ctl, command, event, 0, "automatic")
}
func queueRequestWithOrigin(ctl, command, event string, ttl time.Duration, origin string) (Request, error) {
	if ttl < 0 || ttl > 24*time.Hour {
		return Request{}, errors.New("invalid command TTL")
	}
	if !validCommand(command) {
		return Request{}, fmt.Errorf("invalid queue command %q", command)
	}
	if strings.ContainsAny(event, "\x00\r\n\t ") || len(event) > 128 {
		return Request{}, errors.New("invalid queue event")
	}
	dir := ctl + ".d"
	if err := privateSpoolDir(dir, true); err != nil {
		return Request{}, err
	}
	id := fmt.Sprintf("%020d-%d-%06d", time.Now().UnixNano(), os.Getpid(), atomic.AddUint64(&requestCounter, 1))
	r := Request{ID: id, Command: command, Event: event, Created: time.Now().Unix(), Origin: origin}
	if ttl > 0 {
		r.Deadline = time.Now().Add(ttl).UnixNano()
	}
	b, err := json.Marshal(r)
	if err != nil {
		return Request{}, err
	}
	tmp := filepath.Join(dir, "."+id+".tmp")
	dst := filepath.Join(dir, id+".json")
	if err := writeNewFile(tmp, append(b, '\n')); err != nil {
		return Request{}, err
	}
	if err := publishNewFile(tmp, dst); err != nil {
		_ = os.Remove(tmp)
		return Request{}, fmt.Errorf("queue publication uncertain for %s: %w", id, err)
	}
	return r, nil
}
func validCommand(cmd string) bool {
	switch cmd {
	case "start", "stop", "reload", "pause", "safe", "resume":
		return true
	}
	return false
}
func quarantine(dir, p, suffix string) (string, error) {
	dst := p + suffix
	if _, err := os.Lstat(dst); err == nil {
		return p, fmt.Errorf("quarantine target already exists: %s", dst)
	} else if !errors.Is(err, os.ErrNotExist) {
		return p, err
	}
	if err := os.Rename(p, dst); err != nil {
		return p, err
	}
	if err := syncSpoolDir(dir); err != nil {
		return dst, err
	}
	return dst, nil
}
func NextRequest(ctl string) (Request, string, error) {
	dir := ctl + ".d"
	if err := privateSpoolDir(dir, false); errors.Is(err, os.ErrNotExist) {
		return Request{}, "", nil
	} else if err != nil {
		return Request{}, "", err
	}
	for {
		es, err := os.ReadDir(dir)
		if err != nil {
			return Request{}, "", err
		}
		names := []string{}
		for _, e := range es {
			if e.IsDir() {
				continue
			}
			name := e.Name()
			if strings.HasSuffix(name, ".json") || strings.HasSuffix(name, ".work") {
				names = append(names, name)
			}
		}
		sort.Slice(names, func(i, j int) bool { return requestBase(names[i]) < requestBase(names[j]) })
		if len(names) == 0 {
			return Request{}, "", nil
		}
		name := names[0]
		p := filepath.Join(dir, name)
		// Verify the item before claiming; an attacker-supplied symlink must never
		// be followed even briefly or used to overwrite a destination.
		st, err := os.Lstat(p)
		if errors.Is(err, os.ErrNotExist) {
			continue
		}
		if err != nil {
			return Request{}, p, err
		}
		if !st.Mode().IsRegular() || st.Size() > maxRequestBytes || !validRequestID(requestBase(name)) {
			bad, qerr := quarantine(dir, p, ".bad")
			if qerr != nil {
				return Request{}, p, qerr
			}
			return Request{}, bad, fmt.Errorf("unsafe queue item quarantined: %s", name)
		}
		claimedHere := false
		if strings.HasSuffix(name, ".json") {
			claimed := filepath.Join(dir, strings.TrimSuffix(name, ".json")+".work")
			if _, err := os.Lstat(claimed); err == nil {
				return Request{}, p, fmt.Errorf("already claimed request %s", name)
			} else if !errors.Is(err, os.ErrNotExist) {
				return Request{}, p, err
			}
			if err := os.Rename(p, claimed); errors.Is(err, os.ErrNotExist) {
				continue
			} else if err != nil {
				return Request{}, p, err
			}
			p = claimed
			claimedHere = true
			if err := syncSpoolDir(dir); err != nil {
				return Request{}, p, fmt.Errorf("durable claim: %w", err)
			}
		}
		b, err := readSpoolFile(p, maxRequestBytes)
		if err != nil {
			bad, qerr := quarantine(dir, p, ".bad")
			if qerr != nil {
				return Request{}, p, errors.Join(err, qerr)
			}
			return Request{}, bad, fmt.Errorf("unsafe control request: %w", err)
		}
		var r Request
		if err = json.Unmarshal(b, &r); err != nil || !validRequestID(r.ID) || r.ID != requestBase(name) || !validCommand(r.Command) || len(r.Event) > 128 || strings.ContainsAny(r.Event, "\x00\r\n\t ") || r.Deadline < 0 || (r.Origin != "" && r.Origin != "automatic") {
			bad, qerr := quarantine(dir, p, ".bad")
			if qerr != nil {
				return Request{}, p, qerr
			}
			return Request{}, bad, fmt.Errorf("invalid control request %s", name)
		}
		if claimedHere {
			// A crash between claim and this write has NOT reached privileged
			// execution, but is conservatively treated as unknown on recovery.
			if err := writePhase(ctl, r, "claimed"); err != nil {
				return Request{}, p, fmt.Errorf("durable claim phase: %w", err)
			}
		}
		if ack, present, err := strictAck(ctl, r.ID); err != nil {
			return Request{}, p, err
		} else if present {
			if ack.State == "unknown" {
				return Request{}, p, fmt.Errorf("%w: command %s", ErrUnknownOutcome, r.ID)
			}
			if err := os.Remove(p); err != nil {
				return Request{}, p, err
			}
			if err := syncSpoolDir(dir); err != nil {
				return Request{}, p, err
			}
			continue
		}
		if claimedHere && r.Deadline > 0 && time.Now().UnixNano() > r.Deadline {
			cause := fmt.Errorf("command %s deadline expired before execution; not replayed", r.ID)
			if err := CompleteRequest(ctl, r, cause); err != nil {
				return Request{}, p, err
			}
			expired, err := quarantine(dir, p, ".interrupted")
			if err != nil {
				return Request{}, p, err
			}
			return Request{}, expired, cause
		}
		if !claimedHere {
			claimed, err := readPhase(ctl, r, "claimed")
			if err != nil {
				return Request{}, p, err
			}
			applying, err := readPhase(ctl, r, "applying")
			if err != nil {
				return Request{}, p, err
			}
			if applying || !claimed {
				// Root effects MAY have happened: UNKNOWN is not FAILED, and the
				// original .work evidence must survive daemon restarts.
				if err := CompleteUnknownRequest(ctl, r); err != nil {
					return Request{}, p, fmt.Errorf("persist unknown outcome: %w", err)
				}
				return Request{}, p, fmt.Errorf("%w: command %s", ErrUnknownOutcome, r.ID)
			}
			cause := fmt.Errorf("interrupted command %s before privileged execution; not replayed", r.ID)
			if err := CompleteRequest(ctl, r, cause); err != nil {
				return Request{}, p, fmt.Errorf("persist interrupted receipt: %w", err)
			}
			interrupted, err := quarantine(dir, p, ".interrupted")
			if err != nil {
				return Request{}, p, err
			}
			return Request{}, interrupted, cause
		}
		return r, p, nil
	}
}
func requestBase(name string) string {
	return strings.TrimSuffix(strings.TrimSuffix(name, ".json"), ".work")
}
func validRequestID(id string) bool {
	if len(id) < 3 || len(id) > 96 {
		return false
	}
	for _, c := range id {
		if c != '-' && (c < '0' || c > '9') {
			return false
		}
	}
	return true
}
func strictAck(ctl, id string) (Ack, bool, error) {
	if !validRequestID(id) {
		return Ack{}, false, errors.New("invalid acknowledgement id")
	}
	dir := ctl + ".ack"
	if err := inspectSpoolDir(dir); errors.Is(err, os.ErrNotExist) {
		return Ack{}, false, nil
	} else if err != nil {
		return Ack{}, false, err
	}
	b, err := readSpoolFile(filepath.Join(dir, id+".json"), maxRequestBytes)
	if errors.Is(err, os.ErrNotExist) {
		return Ack{}, false, nil
	}
	if err != nil {
		return Ack{}, false, fmt.Errorf("read acknowledgement: %w", err)
	}
	var a Ack
	if err := json.Unmarshal(b, &a); err != nil || a.ID != id || (a.State != "applied" && a.State != "failed" && a.State != "unknown") {
		return Ack{}, false, fmt.Errorf("invalid acknowledgement record for %s", id)
	}
	return a, true, nil
}
func ReadAck(ctl, id string) (Ack, bool) {
	a, ok, err := strictAck(ctl, id)
	return a, ok && err == nil
}
func CompleteRequest(ctl string, r Request, err error) error {
	if !validRequestID(r.ID) {
		return errors.New("invalid acknowledgement id")
	}
	dir := ctl + ".ack"
	if e := privateSpoolDir(dir, true); e != nil {
		return e
	}
	a := Ack{ID: r.ID, State: "applied", Completed: time.Now().Unix()}
	if err != nil {
		a.State = "failed"
		a.Error = err.Error()
	}
	if existing, present, e := strictAck(ctl, r.ID); e != nil {
		return e
	} else if present {
		if existing.State == a.State && existing.Error == a.Error {
			return nil
		}
		return fmt.Errorf("acknowledgement conflict for %s", r.ID)
	}
	b, e := json.Marshal(a)
	if e != nil {
		return e
	}
	tmp := filepath.Join(dir, "."+r.ID+".tmp")
	dst := filepath.Join(dir, r.ID+".json")
	if e := writeNewFile(tmp, append(b, '\n')); e != nil {
		return e
	}
	if e := publishNewFile(tmp, dst); e != nil {
		_ = os.Remove(tmp)
		// A concurrent writer or uncertain fsync must not replace its receipt.
		if existing, present, checkErr := strictAck(ctl, r.ID); checkErr == nil && present && existing.State == a.State && existing.Error == a.Error {
			return nil
		}
		return fmt.Errorf("acknowledgement publication uncertain: %w", e)
	}
	return nil
}
