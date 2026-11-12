package daemon

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/signal"
	"path/filepath"
	"reflect"
	"sort"
	"strings"
	"syscall"
	"time"

	"github.com/Mikeyphw/xbsctl/internal/autoevents"
	"github.com/Mikeyphw/xbsctl/internal/config"
	"github.com/Mikeyphw/xbsctl/internal/control"
	"github.com/Mikeyphw/xbsctl/internal/eventbridge"
	"github.com/Mikeyphw/xbsctl/internal/tuning"
)

type options struct {
	jsonMode bool
	once     bool
	dryRun   bool
	interval time.Duration
}
type state struct {
	Mode             string
	Active           map[string]bool
	LastAppliedEvent string
	LastAppliedTime  string
	LastReloadTime   string
	LastError        string
	LastErrorTime    string
	LogLevel         any
	EffectResults    map[string]any
	QuitRequested    bool
	DryRun           bool
	AutoOwned        map[string]bool
	AutoPending      *autoPending
	AutoPlanner      *autoevents.Planner
	AutoBlocked      bool
	AutoReport       map[string]any
}

func Main(args []string, stdout io.Writer, stderr io.Writer) int {
	opts, err := parse(args)
	if err != nil {
		fmt.Fprintln(stderr, "XtremeBSd:", err)
		return 2
	}
	cfg, err := config.Load()
	if err != nil {
		return finish(stdout, opts, false, "config load failed: "+err.Error(), 3)
	}
	if errs := config.Validate(cfg); len(errs) > 0 {
		return finish(stdout, opts, false, "initial config invalid: "+strings.Join(errs, "; "), 3)
	}
	if configured, ok := get(cfg, "header.dry_run").(bool); ok && configured {
		opts.dryRun = true
	}
	s := &state{Mode: "normal", Active: map[string]bool{}, AutoOwned: map[string]bool{}, LogLevel: get(cfg, "header.log_level"), EffectResults: map[string]any{}, DryRun: opts.dryRun}
	// A second daemon must never race this instance on the .work queue.
	lock, lockErr := acquireDaemonLock()
	if lockErr != nil {
		return finish(stdout, opts, false, "daemon singleton lock: "+lockErr.Error(), 6)
	}
	defer lock.Close()
	// Command-phase recovery MUST precede journal reconciliation and active
	// profile replay. Otherwise an interrupted root command could execute twice.
	unknown, scanErr := control.PendingUnknown(ctlPath(cfg))
	if scanErr != nil || len(unknown) != 0 {
		s.Mode = "recovery_required"
		s.LastError = fmt.Sprintf("unknown command outcomes %v: %v; manual reconciliation required", unknown, scanErr)
		s.LastErrorTime = now()
		_ = writeStatus(cfg, s)
		return finish(stdout, opts, false, s.LastError, 6)
	}
	engine := tuning.New(nil)
	bindEngineJournal(engine, cfg, s)
	if err := recoverRuntimeState(engine, cfg, s, opts.dryRun); err != nil {
		// Never begin the control loop with a failed recovery; preserve the
		// durable journal and expose explicit recovery_required state.
		s.Mode = "recovery_required"
		s.LastError = "restart reconciliation: " + err.Error()
		s.LastErrorTime = now()
		_ = writeStatus(cfg, s)
		return finish(stdout, opts, false, s.LastError, 6)
	}
	if err := writeStatus(cfg, s); err != nil {
		return finish(stdout, opts, false, err.Error(), 6)
	}
	if opts.once {
		change, err := processCtl(engine, cfg, s, opts.dryRun)
		if err != nil {
			return finish(stdout, opts, false, err.Error(), 6)
		}
		if change != nil {
			if err := writeStatus(cfg, s); err != nil {
				return finish(stdout, opts, false, err.Error(), 6)
			}
			if err := writeEventChanges(cfg, s, change); err != nil {
				return finish(stdout, opts, false, err.Error(), 6)
			}
		}
		return finish(stdout, opts, true, "XtremeBSd one-shot completed", 0)
	}
	sigc := make(chan os.Signal, 1)
	signal.Notify(sigc, syscall.SIGTERM, syscall.SIGINT, syscall.SIGHUP)
	defer signal.Stop(sigc)
	for {
		change, err := processCtl(engine, cfg, s, opts.dryRun)
		if err != nil {
			s.LastError = err.Error()
			s.LastErrorTime = now()
		}
		if change != nil || err != nil {
			if writeErr := writeStatus(cfg, s); writeErr != nil {
				return finish(stdout, opts, false, "status publication failed: "+writeErr.Error(), 6)
			}
		}
		if s.Mode == "recovery_required" {
			return finish(stdout, opts, false, "runtime restoration incomplete; refusing further commands", 6)
		}
		if change != nil {
			if pubErr := writeEventChanges(cfg, s, change); pubErr != nil {
				s.Mode = "recovery_required"
				s.LastError = "event publication failure: " + pubErr.Error()
				s.LastErrorTime = now()
				_ = writeStatus(cfg, s)
				return finish(stdout, opts, false, s.LastError, 6)
			}
		}
		if s.QuitRequested {
			return finish(stdout, opts, true, "XtremeBSd boot event requested quit", 0)
		}
		if changed, autoErr := automaticCycle(engine, cfg, s); autoErr != nil {
			s.LastError = "automatic event planner: " + autoErr.Error()
			s.LastErrorTime = now()
			if err := writeStatus(cfg, s); err != nil {
				return finish(stdout, opts, false, "automatic status publication: "+err.Error(), 6)
			}
		} else if changed {
			if err := writeStatus(cfg, s); err != nil {
				return finish(stdout, opts, false, "automatic status publication: "+err.Error(), 6)
			}
		}
		if s.Mode == "normal" {
			for _, name := range activeList(s) {
				if ev, x := tuning.Resolve(cfg, name); x == nil {
					if x = engine.Maintain(ev); x != nil {
						s.LastError = x.Error()
						s.LastErrorTime = now()
						_ = writeStatus(cfg, s)
					}
				}
			}
		}
		select {
		case <-time.After(opts.interval):
		case <-sigc:
			if err := restoreActive(engine, cfg, s); err != nil {
				s.LastError = err.Error()
				s.LastErrorTime = now()
				_ = writeStatus(cfg, s)
				return finish(stdout, opts, false, "shutdown restoration failed: "+err.Error(), 6)
			}
			s.Active = map[string]bool{}
			s.Mode = "normal"
			if err := persistRuntimeState(engine, cfg, s); err != nil {
				s.Mode = "recovery_required"
				s.LastError = "shutdown persistence failed: " + err.Error()
				s.LastErrorTime = now()
				_ = writeStatus(cfg, s)
				return finish(stdout, opts, false, s.LastError, 6)
			}
			if err := writeStatus(cfg, s); err != nil {
				return finish(stdout, opts, false, "shutdown status publication failed: "+err.Error(), 6)
			}
			return finish(stdout, opts, true, "XtremeBSd stopped with effects restored", 0)
		}
	}
}

func parse(args []string) (options, error) {
	opts := options{interval: 3 * time.Second}
	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--json":
			opts.jsonMode = true
		case "--once":
			opts.once = true
		case "--dry-run":
			opts.dryRun = true
		case "--interval":
			if i+1 >= len(args) {
				return opts, errors.New("--interval requires seconds")
			}
			i++
			var sec int
			if _, err := fmt.Sscanf(args[i], "%d", &sec); err != nil || sec <= 0 {
				return opts, errors.New("invalid --interval")
			}
			opts.interval = time.Duration(sec) * time.Second
		case "-h", "--help":
			opts.once = true
		default:
			return opts, fmt.Errorf("unknown argument: %s", args[i])
		}
	}
	return opts, nil
}

func processCtl(engine *tuning.Engine, cfg map[string]any, s *state, dryRun bool) (change map[string]any, retErr error) {
	if s.Mode == "recovery_required" {
		return nil, errors.New("unresolved privileged restoration; control is disabled")
	}
	ctl := ctlPath(cfg)
	req, reqPath, qerr := control.NextRequest(ctl)
	if qerr != nil {
		if errors.Is(qerr, control.ErrUnknownOutcome) {
			s.Mode = "recovery_required"
			s.LastError = qerr.Error()
			s.LastErrorTime = now()
		}
		return nil, qerr
	}
	cmdline := ""
	if reqPath != "" {
		cmdline = strings.TrimSpace(req.Command + " " + req.Event)
		defer func() {
			if ackErr := control.CompleteRequest(ctl, req, retErr); ackErr != nil {
				// Preserve the .work file if the receipt could not be written.
				// The next daemon refuses to replay that interrupted claim.
				retErr = errors.Join(retErr, fmt.Errorf("durable command acknowledgement: %w", ackErr))
				return
			}
			if removeErr := os.Remove(reqPath); removeErr != nil && !errors.Is(removeErr, os.ErrNotExist) {
				retErr = errors.Join(retErr, fmt.Errorf("remove acknowledged claim: %w", removeErr))
			}
		}()
	} else {
		// Legacy control-file compatibility: no symlinks, FIFOs, or oversized data.
		// The durable queue is the preferred path for new clients.
		cmdline, qerr = consumeLegacyControl(ctl)
		if qerr != nil {
			return nil, qerr
		}
		if cmdline == "" {
			return nil, nil
		}
	}

	parts := strings.Fields(cmdline)
	cmd := parts[0]
	event := "manual"
	if len(parts) > 1 {
		event = parts[1]
	}
	if !eventExists(cfg, event) {
		return nil, fmt.Errorf("unknown event: %s", event)
	}
	if reqPath != "" {
		if err := validateAutoRequest(cfg, s, req); err != nil {
			return nil, err
		}
	}
	// Strict WAL protocol: no privileged mutation before the applying phase
	// is durably published for this exact queued request.
	if reqPath != "" {
		if err := control.MarkApplying(ctl, req); err != nil {
			return nil, fmt.Errorf("durable command applying phase: %w", err)
		}
	}
	change = map[string]any{"type": "profile", "name": event, "timestamp": time.Now().Unix()}
	switch cmd {
	case "start":
		// Starting while paused/safe would change the device despite the mode.
		if s.Mode != "normal" {
			return nil, fmt.Errorf("cannot start profile %q while mode=%s", event, s.Mode)
		}
		// Duplicate starts are idempotent; never repeat privileged commands.
		if s.Active[event] {
			change["state"] = "active"
			change["effects"] = map[string]any{"state": "already_active", "mutations": "none"}
			break
		}
		if err := checkStartOwnership(cfg, s.Active, event); err != nil {
			return nil, err
		}
		ev, err := tuning.Resolve(cfg, event)
		if err != nil {
			return nil, err
		}
		result, err := engine.Apply(ev, dryRun)
		s.EffectResults[event] = tuning.MarshalResult(result)
		if err != nil {
			if strings.Contains(err.Error(), "rollback incomplete") {
				s.Mode = "recovery_required"
				if persistErr := persistRuntimeState(engine, cfg, s); persistErr != nil {
					return nil, errors.Join(err, fmt.Errorf("persist recovery-required state: %w", persistErr))
				}
			}
			return nil, fmt.Errorf("apply %s: %w", event, err)
		}
		if !result.Applied {
			return nil, fmt.Errorf("apply %s did not commit", event)
		}
		s.Active[event] = true
		s.LastAppliedEvent = event
		s.LastAppliedTime = now()
		if event == "boot" && ev.Quit {
			s.QuitRequested = true
		}
		change["state"] = "active"
		change["effects"] = tuning.MarshalResult(result)
	case "stop":
		// Do not call the global Engine.Restore for a profile that is not
		// active; that would undo another profile's owned effects.
		if !s.Active[event] {
			change["state"] = "inactive"
			change["effects"] = map[string]any{"state": "already_inactive", "mutations": "none"}
			break
		}
		ev, err := tuning.Resolve(cfg, event)
		if err != nil {
			return nil, err
		}
		// Restore ONLY this profile's resource domains; other active
		// profiles retain their own original-value records and effects.
		result := tuning.Result{Event: event, Applied: true}
		if hasPrivilegedEffects(ev) {
			result, err = engine.RestoreResources(ev, resourceLeases(ev))
			s.EffectResults[event] = tuning.MarshalResult(result)
			if err != nil {
				s.Mode = "recovery_required"
				restoreErr := fmt.Errorf("restore %s: %w", event, err)
				if persistErr := persistRuntimeState(engine, cfg, s); persistErr != nil {
					return nil, errors.Join(restoreErr, fmt.Errorf("persist recovery-required: %w", persistErr))
				}
				return nil, restoreErr
			}
		}
		delete(s.Active, event)
		// Peer effect domains remain active and are not replayed or toggled.
		s.EffectResults[event] = tuning.MarshalResult(result)
		s.LastAppliedEvent = event
		s.LastAppliedTime = now()
		change["state"] = "inactive"
		change["effects"] = tuning.MarshalResult(result)
	case "reload":
		fresh, err := config.Load()
		if err != nil {
			return nil, fmt.Errorf("reload config: %w", err)
		}
		if errs := config.Validate(fresh); len(errs) > 0 {
			return nil, fmt.Errorf("reload validation: %s", strings.Join(errs, "; "))
		}
		// A configuration update may turn policy-only profiles into mutators.
		// Refuse the conflicting new plan before restoring anything live.
		projected := map[string]bool{}
		for name, active := range s.Active {
			if active && eventExists(fresh, name) {
				projected[name] = true
			}
		}
		if _, err := exclusiveActiveOwner(fresh, projected); err != nil {
			return nil, fmt.Errorf("reload ownership: %w", err)
		}
		oldActive := activeList(s)
		oldCfg := cloneConfig(cfg)
		if err := restoreActive(engine, cfg, s); err != nil {
			return nil, fmt.Errorf("reload restore: %w", err)
		}
		for k := range cfg {
			delete(cfg, k)
		}
		for k, v := range fresh {
			cfg[k] = v
		}
		for _, name := range oldActive {
			if eventExists(cfg, name) {
				s.Active[name] = true
			} else {
				delete(s.Active, name)
			}
		}
		if s.Mode == "normal" {
			if err := reapplyActive(engine, cfg, s, dryRun); err != nil {
				_ = restoreActive(engine, cfg, s)
				for k := range cfg {
					delete(cfg, k)
				}
				for k, v := range oldCfg {
					cfg[k] = v
				}
				s.Active = map[string]bool{}
				for _, name := range oldActive {
					s.Active[name] = true
				}
				_ = reapplyActive(engine, cfg, s, dryRun)
				return nil, fmt.Errorf("reload reconcile: %w", err)
			}
		}
		s.LogLevel = get(cfg, "header.log_level")
		s.LastReloadTime = now()
		s.AutoPlanner = nil
		s.AutoBlocked = false
		change = map[string]any{"type": "reload", "name": "reload", "state": "applied", "timestamp": time.Now().Unix()}
	case "pause":
		if err := restoreActive(engine, cfg, s); err != nil {
			return nil, err
		}
		s.Mode = "paused"
		change = map[string]any{"type": "mode", "name": "pause", "state": "active", "timestamp": time.Now().Unix()}
	case "safe":
		if err := restoreActive(engine, cfg, s); err != nil {
			return nil, err
		}
		s.Mode = "safe"
		s.Active = map[string]bool{}
		s.AutoOwned = map[string]bool{}
		change = map[string]any{"type": "safe_mode", "name": "safe_mode", "state": "active", "timestamp": time.Now().Unix()}
	case "resume":
		wasPaused := s.Mode == "paused"
		s.Mode = "normal"
		if wasPaused {
			if err := reapplyActive(engine, cfg, s, dryRun); err != nil {
				s.Mode = "recovery_required"
				if persistErr := persistRuntimeState(engine, cfg, s); persistErr != nil {
					return nil, errors.Join(err, fmt.Errorf("persist failed resume recovery: %w", persistErr))
				}
				return nil, fmt.Errorf("resume reconciliation: %w", err)
			}
		}
		change = map[string]any{"type": "safe_mode", "name": "safe_mode", "state": "inactive", "timestamp": time.Now().Unix()}
	default:
		return nil, fmt.Errorf("unknown control command: %s", cmd)
	}
	if reqPath != "" && (cmd == "start" || cmd == "stop") {
		if s.AutoOwned == nil {
			s.AutoOwned = map[string]bool{}
		}
		if req.Origin == "automatic" && cmd == "start" && s.Active[event] {
			s.AutoOwned[event] = true
		} else {
			delete(s.AutoOwned, event)
		}
	}
	clearLastError(s)
	if err := persistRuntimeState(engine, cfg, s); err != nil {
		return nil, fmt.Errorf("persist runtime state: %w", err)
	}
	return change, nil
}

func restoreActive(engine *tuning.Engine, cfg map[string]any, s *state) error {
	// Bulk transitions are deliberate global restoration boundaries. Validate
	// every lease BEFORE writing anything, then restore the shared journal once.
	owners, err := activeResourceLeases(cfg, s.Active)
	if err != nil {
		return fmt.Errorf("restore ownership: %w", err)
	}
	if len(owners) == 0 && len(engine.Snapshot()) > 0 {
		return errors.New("unowned privileged recovery snapshot: refusing to discard original state")
	}
	if len(owners) > 0 {
		result, err := engine.Restore(tuning.Event{Name: "bulk-restoration"})
		if err != nil {
			s.Mode = "recovery_required"
			restoreErr := fmt.Errorf("bulk restore: %w", err)
			if persistErr := persistRuntimeState(engine, cfg, s); persistErr != nil {
				return errors.Join(restoreErr, fmt.Errorf("persist recovery-required: %w", persistErr))
			}
			return restoreErr
		}
		for name := range owners {
			s.EffectResults[name] = tuning.MarshalResult(result)
		}
	}
	for _, name := range activeList(s) {
		if _, mutating := owners[name]; !mutating {
			s.EffectResults[name] = tuning.MarshalResult(tuning.Result{Event: name, Applied: true})
		}
	}
	return nil
}
func reapplyActive(engine *tuning.Engine, cfg map[string]any, s *state, dry bool) error {
	// Reject corrupt or formerly valid multi-owner runtime states before
	// performing ANY privileged reapplication (including after a restart).
	if _, err := exclusiveActiveOwner(cfg, s.Active); err != nil {
		return fmt.Errorf("reapply ownership: %w", err)
	}
	for _, name := range activeList(s) {
		ev, err := tuning.Resolve(cfg, name)
		if err != nil {
			return err
		}
		result, err := engine.Apply(ev, dry)
		s.EffectResults[name] = tuning.MarshalResult(result)
		if err != nil || !result.Applied {
			if err == nil {
				err = errors.New("effect plan did not commit")
			}
			return fmt.Errorf("reapply %s: %w", name, err)
		}
	}
	return nil
}
func cloneConfig(cfg map[string]any) map[string]any {
	b, _ := json.Marshal(cfg)
	out := map[string]any{}
	_ = json.Unmarshal(b, &out)
	return out
}

func dryRunState(s *state) bool { return s.DryRun }

func clearLastError(s *state) {
	s.LastError = ""
	s.LastErrorTime = ""
}

type runtimeState struct {
	Version        int                 `json:"version"`
	Mode           string              `json:"mode"`
	Active         []string            `json:"active"`
	Original       map[string]string   `json:"original"`
	ResourceLeases map[string][]string `json:"resource_leases,omitempty"`
	Automatic      []string            `json:"automatic_owned,omitempty"`
}

func runtimeStatePath() string {
	return filepath.Join(config.ResolvePaths().Runtime, "daemon_state_v1.json")
}

// bindEngineJournal installs a per-effect write-ahead barrier. Its callback
// commits the ORIGINAL state before the first irreversible system call.
func bindEngineJournal(engine *tuning.Engine, cfg map[string]any, s *state) {
	engine.SetJournal(func(next map[string]string) error {
		return persistRuntimeSnapshot(cfg, s, next)
	})
}

func persistRuntimeState(engine *tuning.Engine, cfg map[string]any, s *state) error {
	return persistRuntimeSnapshot(cfg, s, engine.Snapshot())
}

func persistRuntimeSnapshot(cfg map[string]any, s *state, snapshot map[string]string) error {
	rs := runtimeState{Version: 1, Mode: s.Mode, Active: activeList(s), Original: snapshot, ResourceLeases: declaredLeases(cfg, s.Active), Automatic: autoOwnedList(s)}
	if len(rs.Active) == 0 && len(rs.Original) == 0 && s.Mode == "normal" {
		if err := os.Remove(runtimeStatePath()); errors.Is(err, os.ErrNotExist) {
			return nil
		} else if err != nil {
			return err
		}
		return syncRuntimeDirectory()
	}
	if err := atomicJSON(runtimeStatePath(), rs, 0o600); err != nil {
		return err
	}
	return syncRuntimeDirectory()
}

// Only the internal runtime WAL has a strict directory-sync barrier. Keep
// status/log writes on shared-storage compatible with their existing policy.
func syncRuntimeDirectory() error {
	dir, err := os.Open(filepath.Dir(runtimeStatePath()))
	if err != nil {
		return err
	}
	syncErr := dir.Sync()
	closeErr := dir.Close()
	if syncErr != nil {
		return fmt.Errorf("sync WAL directory: %w", syncErr)
	}
	return closeErr
}

func recoverRuntimeState(engine *tuning.Engine, cfg map[string]any, s *state, dry bool) error {
	b, err := os.ReadFile(runtimeStatePath())
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	var rs runtimeState
	if err := json.Unmarshal(b, &rs); err != nil {
		return err
	}
	if rs.Version != 1 {
		return fmt.Errorf("unsupported runtime state version %d", rs.Version)
	}
	// When present, compare durable lease intentions to the current config.
	// Refuse recovery after an out-of-band active-profile policy change.
	if len(rs.ResourceLeases) > 0 {
		current := declaredLeases(cfg, func() map[string]bool {
			a := map[string]bool{}
			for _, name := range rs.Active {
				a[name] = true
			}
			return a
		}())
		if !reflect.DeepEqual(rs.ResourceLeases, current) {
			return fmt.Errorf("persisted resource lease drift; recovery requires explicit reconciliation")
		}
	}
	engine.LoadSnapshot(rs.Original)
	s.Mode = rs.Mode
	if s.Mode == "" {
		s.Mode = "normal"
	}
	for _, name := range rs.Active {
		if !eventExists(cfg, name) {
			return fmt.Errorf("persisted event %q is no longer configured", name)
		}
		s.Active[name] = true
	}
	if s.AutoOwned == nil {
		s.AutoOwned = map[string]bool{}
	}
	for _, name := range rs.Automatic {
		if name != "charging" && name != "screen_off" && name != "low_power" {
			return fmt.Errorf("invalid automatic recovery owner %q", name)
		}
		if !s.Active[name] || s.AutoOwned[name] {
			return fmt.Errorf("orphaned or duplicate automatic recovery owner %q", name)
		}
		s.AutoOwned[name] = true
	}
	if dry {
		if len(rs.Original) > 0 {
			return errors.New("dry-run cannot reconcile pending privileged changes")
		}
		return nil
	}
	if len(rs.Original) > 0 {
		// A crash may occur after any root write but before the profile becomes
		// active. Restore the complete owned preimage before replaying desired
		// profiles; incomplete restoration must fail closed and retain the WAL.
		if _, err := engine.Restore(tuning.Event{Name: "startup-recovery"}); err != nil {
			return fmt.Errorf("restore pending write-ahead journal: %w", err)
		}
		if err := persistRuntimeState(engine, cfg, s); err != nil {
			return fmt.Errorf("persist recovered journal: %w", err)
		}
	}
	if s.Mode == "recovery_required" {
		// Uncertain interrupted work must never reactivate by itself after
		// a successful repair. Enter safe mode and require deliberate resume.
		s.Mode = "safe"
		s.Active = map[string]bool{}
		s.AutoOwned = map[string]bool{}
		if err := persistRuntimeState(engine, cfg, s); err != nil {
			return fmt.Errorf("persist post-recovery safe mode: %w", err)
		}
	}
	if s.Mode == "normal" && len(rs.Active) > 0 {
		if err := reapplyActive(engine, cfg, s, false); err != nil {
			return fmt.Errorf("reapply recovered profiles: %w", err)
		}
		if err := persistRuntimeState(engine, cfg, s); err != nil {
			return err
		}
	}
	return nil
}

func bridgePaths() eventbridge.Paths {
	p := eventbridge.DefaultPaths()
	cp := config.ResolvePaths()
	if strings.TrimSpace(os.Getenv("XBS_RUNTIME_DIR")) == "" {
		p.Runtime = cp.Runtime
	}
	if strings.TrimSpace(os.Getenv("XBS_EVENT_CHANGES_FILE")) == "" {
		p.Event = filepath.Join(p.Runtime, "event_changes.json")
	}
	if strings.TrimSpace(os.Getenv("XBS_EVENT_BRIDGE_FILE")) == "" {
		p.Envelope = filepath.Join(p.Runtime, "event_bridge_v1.json")
	}
	if strings.TrimSpace(os.Getenv("XBS_EVENT_BRIDGE_SEQUENCE")) == "" {
		p.Sequence = filepath.Join(p.Runtime, "event_bridge_v1.seq")
	}
	if strings.TrimSpace(os.Getenv("XBS_EXTERNAL_STATUS")) == "" {
		p.Status = cp.Status
	}
	return p
}

func writeStatus(cfg map[string]any, s *state) error {
	p := config.ResolvePaths()
	active := activeList(s)
	profiles := []string{}
	if s.Mode == "safe" {
		profiles = []string{"safe"}
	} else {
		profiles = append(profiles, active...)
	}
	payload := map[string]any{"version": 2, "active_events": active, "active_profiles": profiles, "last_error": s.LastError, "last_error_time": s.LastErrorTime, "last_applied_event": s.LastAppliedEvent, "last_applied_time": s.LastAppliedTime, "last_reload_time": s.LastReloadTime, "log_level": s.LogLevel, "mode": s.Mode, "detail": map[string]any{"runtime": "go-native", "daemon": "XtremeBSd", "dry_run": dryRunState(s), "pid": os.Getpid(), "effects": s.EffectResults, "automatic": s.AutoReport, "automatic_owned": autoOwnedList(s)}}
	return atomicJSON(p.Status, payload, 0o644)
}

func writeEventChanges(cfg map[string]any, s *state, change map[string]any) error {
	paths := bridgePaths()
	events := eventKeys(cfg)
	profiles := map[string]string{}
	active := activeList(s)
	activeSet := map[string]bool{}
	for _, e := range active {
		activeSet[e] = true
	}
	inactive := []string{}
	for _, e := range events {
		if activeSet[e] {
			profiles[e] = "active"
		} else {
			profiles[e] = "inactive"
			inactive = append(inactive, e)
		}
	}
	payload := map[string]any{"version": 1, "safe_mode": s.Mode == "safe", "last_change": change, "profiles": profiles, "active": active, "inactive": inactive}
	if err := atomicJSON(paths.Event, payload, 0o600); err != nil {
		return err
	}
	if _, pubErr := eventbridge.Publish(paths, nil); pubErr != nil {
		// The command may already have succeeded; do not mislabel its root
		// outcome as a failure or replay it. Expose bridge errors in status.
		s.LastError = "event bridge publication: " + pubErr.Error()
		s.LastErrorTime = now()
		if statusErr := writeStatus(cfg, s); statusErr != nil {
			return errors.Join(pubErr, fmt.Errorf("persist event bridge failure: %w", statusErr))
		}
	}
	return nil
}

func activeList(s *state) []string {
	out := []string{}
	for e, ok := range s.Active {
		if ok {
			out = append(out, e)
		}
	}
	sort.Strings(out)
	return out
}
func eventKeys(cfg map[string]any) []string {
	out := []string{}
	if ev, ok := cfg["events"].(map[string]any); ok {
		for k := range ev {
			if k != "template" {
				out = append(out, k)
			}
		}
	}
	if !contains(out, "manual") {
		out = append(out, "manual")
	}
	sort.Strings(out)
	return out
}
func eventExists(cfg map[string]any, name string) bool {
	if name == "manual" {
		return true
	}
	return contains(eventKeys(cfg), name)
}
func contains(items []string, needle string) bool {
	for _, item := range items {
		if item == needle {
			return true
		}
	}
	return false
}
func ctlPath(cfg map[string]any) string {
	if v, ok := get(cfg, "header.ctl_file").(string); ok && strings.TrimSpace(v) != "" {
		return v
	}
	return filepath.Join(config.ResolvePaths().Runtime, "xbs.ctl")
}
func get(cfg map[string]any, key string) any { v, _ := config.GetByKey(cfg, key); return v }
func now() string                            { return time.Now().Format("2006-01-02T15:04:05") }

func atomicJSON(path string, payload any, mode os.FileMode) error {
	data, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	data = append(data, '\n')
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(filepath.Dir(path), "."+filepath.Base(path)+"-*.tmp")
	if err != nil {
		return err
	}
	name := tmp.Name()
	ok := false
	defer func() {
		if !ok {
			_ = os.Remove(name)
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
	if err := os.Rename(name, path); err != nil {
		return err
	}
	if dir, err := os.Open(filepath.Dir(path)); err == nil {
		_ = dir.Sync()
		_ = dir.Close()
	}
	ok = true
	return nil
}

func finish(stdout io.Writer, opts options, ok bool, message string, code int) int {
	if opts.jsonMode {
		b, _ := json.Marshal(map[string]any{"ok": ok, "message": message, "exit_code": code})
		fmt.Fprintln(stdout, string(b))
	} else {
		fmt.Fprintln(stdout, message)
	}
	return code
}
