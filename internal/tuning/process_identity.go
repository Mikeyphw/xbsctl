package tuning

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// procIdentity is sourced from one /proc/PID/stat read. Field 19 is nice;
// field 22 is starttime in clock ticks since boot. Using the last closing
// parenthesis is essential because the process comm field can contain spaces.
type procIdentity struct {
	nice       string
	startTicks string
}

func parseProcIdentity(stat string) (procIdentity, error) {
	close := strings.LastIndex(stat, ")")
	if close < 0 || close+2 >= len(stat) || stat[close+1] != ' ' {
		return procIdentity{}, errors.New("malformed /proc stat command field")
	}
	fields := strings.Fields(stat[close+2:]) // 0-based field[0] = kernel field 3
	if len(fields) < 20 {
		return procIdentity{}, errors.New("/proc stat missing starttime field")
	}
	nice, err := strconv.Atoi(fields[16])
	if err != nil || nice < -20 || nice > 19 {
		return procIdentity{}, fmt.Errorf("invalid /proc niceness %q", fields[16])
	}
	ticks, err := strconv.ParseUint(fields[19], 10, 64)
	if err != nil || ticks == 0 {
		return procIdentity{}, fmt.Errorf("invalid /proc starttime %q", fields[19])
	}
	return procIdentity{nice: strconv.Itoa(nice), startTicks: strconv.FormatUint(ticks, 10)}, nil
}

func validPID(pid string) bool {
	value, err := strconv.ParseUint(pid, 10, 32)
	return err == nil && value > 0 && strconv.FormatUint(value, 10) == pid
}

func (e *Engine) readProcessIdentity(pid string) (procIdentity, error) {
	if !validPID(pid) {
		return procIdentity{}, fmt.Errorf("invalid PID %q", pid)
	}
	raw, err := e.R.Read(filepath.Join("/proc", pid, "stat"))
	if err != nil {
		return procIdentity{}, err
	}
	return parseProcIdentity(raw)
}

func processNiceKey(pid, ticks string) string { return "nice:" + pid + "@" + ticks }

func parseNiceKey(key string) (pid, ticks string, err error) {
	if !strings.HasPrefix(key, "nice:") {
		return "", "", fmt.Errorf("not a niceness journal record: %q", key)
	}
	parts := strings.Split(strings.TrimPrefix(key, "nice:"), "@")
	if len(parts) != 2 || !validPID(parts[0]) {
		return "", "", fmt.Errorf("legacy or invalid PID-only niceness record %q requires manual reconciliation", key)
	}
	n, e := strconv.ParseUint(parts[1], 10, 64)
	if e != nil || n == 0 || strconv.FormatUint(n, 10) != parts[1] {
		return "", "", fmt.Errorf("invalid process starttime in niceness record %q", key)
	}
	return parts[0], parts[1], nil
}

func (e *Engine) renice(pid string, value int) error {
	identity, err := e.readProcessIdentity(pid)
	if err != nil {
		return fmt.Errorf("probe niceness of PID %s: %w", pid, err)
	}
	key := processNiceKey(pid, identity.startTicks)
	// If the PID was replaced while this engine held a snapshot, stop before
	// any new privileged mutation. The stale snapshot needs reconciliation.
	for recorded := range e.original {
		if strings.HasPrefix(recorded, "nice:"+pid) &&
			(recorded == "nice:"+pid || strings.HasPrefix(recorded, "nice:"+pid+"@")) && recorded != key {
			return fmt.Errorf("PID %s was reused while a niceness recovery record was pending", pid)
		}
	}
	if err := e.remember(key, identity.nice); err != nil {
		return err
	}
	// Recheck immediately before mutation to narrow the reuse window.
	check, err := e.readProcessIdentity(pid)
	if err != nil || check.startTicks != identity.startTicks {
		return fmt.Errorf("PID %s changed after its journal was written; refusing renice: %v", pid, err)
	}
	_, err = e.R.Run("renice", strconv.Itoa(value), "-p", pid)
	return err
}

// restoreNiceJournal never applies an old priority to a new process that
// inherited its PID. Exited/replaced processes no longer need restoring, but
// releasing their journal entry must itself be durable.
func (e *Engine) restoreNiceJournal(key, old string) (string, error) {
	pid, ticks, err := parseNiceKey(key)
	if err != nil {
		return "", err
	}
	identity, err := e.readProcessIdentity(pid)
	if errors.Is(err, os.ErrNotExist) {
		if err := e.release(key); err != nil {
			return "", err
		}
		return "process_exited", nil
	}
	if err != nil {
		return "", fmt.Errorf("cannot verify PID %s before restoring priority: %w", pid, err)
	}
	if identity.startTicks != ticks {
		if err := e.release(key); err != nil {
			return "", err
		}
		return "pid_reused", nil
	}
	if _, err := strconv.Atoi(old); err != nil {
		return "", fmt.Errorf("invalid original nice value for %s: %w", key, err)
	}
	if _, err := e.R.Run("renice", old, "-p", pid); err != nil {
		return "", err
	}
	if err := e.release(key); err != nil {
		return "", err
	}
	return "restored", nil
}
