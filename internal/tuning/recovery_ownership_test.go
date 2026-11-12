package tuning

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

type suspensionAuditRunner struct {
	*fakeRunner
	packageState   map[string]string
	failUnsuspend  bool
	failWiFi       bool
	failCpuRestore bool
}

func (r *suspensionAuditRunner) Run(name string, args ...string) (string, error) {
	call := strings.TrimSpace(name + " " + strings.Join(args, " "))
	if strings.HasPrefix(call, "dumpsys package ") {
		r.calls = append(r.calls, call)
		if output, ok := r.packageState[strings.TrimPrefix(call, "dumpsys package ")]; ok {
			return output, nil
		}
		return "", errors.New("package state unavailable")
	}
	if (r.failUnsuspend && strings.HasPrefix(call, "pm unsuspend --user 0 ")) ||
		(r.failWiFi && call == "svc wifi disable") {
		r.calls = append(r.calls, call)
		return "", errors.New("injected operation failure")
	}
	return r.fakeRunner.Run(name, args...)
}

func (r *suspensionAuditRunner) Write(path, value string) error {
	if r.failCpuRestore && value == "schedutil" {
		return errors.New("injected restoration failure")
	}
	return r.fakeRunner.Write(path, value)
}

func suspensionEvent(t *testing.T) Event {
	t.Helper()
	allow := filepath.Join(t.TempDir(), "allowed.txt")
	if err := os.WriteFile(allow, []byte("app.keep\n"), 0600); err != nil {
		t.Fatal(err)
	}
	ev := safeEvent()
	ev.HandleApps = "suspend"
	ev.Allowlist = allow
	return ev
}

func sourceRunner(state string) *suspensionAuditRunner {
	return &suspensionAuditRunner{
		fakeRunner:   &fakeRunner{files: map[string]string{}},
		packageState: map[string]string{"app.one": state},
	}
}

func hasExactCall(calls []string, target string) bool {
	for _, call := range calls {
		if call == target {
			return true
		}
	}
	return false
}

func TestPreSuspendedPackageNotOwnedOrUnsuspended(t *testing.T) {
	r := sourceRunner("User 0: installed=true suspended=true")
	ev := suspensionEvent(t)
	engine := New(r)
	result, err := engine.Apply(ev, false)
	if err != nil || !result.Applied {
		t.Fatalf("apply: result=%+v err=%v", result, err)
	}
	if len(engine.Snapshot()) != 0 {
		t.Fatalf("touched an already suspended package: %v", engine.Snapshot())
	}
	if _, err := engine.Restore(ev); err != nil {
		t.Fatal(err)
	}
	if hasExactCall(r.calls, "pm suspend --user 0 app.one") || hasExactCall(r.calls, "pm unsuspend --user 0 app.one") {
		t.Fatalf("modified externally suspended package: %v", r.calls)
	}
}

func TestOwnedSuspensionRestoresExactPackageAfterListChanges(t *testing.T) {
	r := sourceRunner("User 0: installed=true suspended=false")
	ev := suspensionEvent(t)
	engine := New(r)
	result, err := engine.Apply(ev, false)
	if err != nil || !result.Applied {
		t.Fatalf("apply: %+v %v", result, err)
	}
	if engine.Snapshot()["suspend:user0:app.one"] != "false" {
		t.Fatalf("missing exact original state: %v", engine.Snapshot())
	}
	if err := os.WriteFile(ev.Allowlist, []byte("app.one\n"), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := engine.Restore(ev); err != nil {
		t.Fatal(err)
	}
	if !hasExactCall(r.calls, "pm unsuspend --user 0 app.one") || hasExactCall(r.calls, "pm unsuspend --user 0 app.keep") {
		t.Fatalf("restored based on current list instead of recorded ownership: %v", r.calls)
	}
	if len(engine.Snapshot()) != 0 {
		t.Fatalf("snapshot not cleared after successful restoration: %v", engine.Snapshot())
	}
}

func TestInvalidOrAmbiguousSuspensionProbeRefusesMutation(t *testing.T) {
	for _, response := range []string{
		"User 10: installed=true suspended=false",
		"User 0: installed=true",
		"User 0: suspended=maybe",
		"User 0: suspended=false\nUser 0: suspended=true",
	} {
		r := sourceRunner(response)
		ev := suspensionEvent(t)
		result, err := New(r).Apply(ev, false)
		if err == nil || result.Applied {
			t.Fatalf("untrusted suspension state accepted: %q result=%+v", response, result)
		}
		if hasExactCall(r.calls, "pm suspend --user 0 app.one") {
			t.Fatalf("mutated after untrusted probe %q: %v", response, r.calls)
		}
	}
}

func TestRollbackFailureKeepsOwnedSuspensionForRetry(t *testing.T) {
	r := sourceRunner("User 0: suspended=false")
	r.failUnsuspend = true
	r.failWiFi = true
	ev := suspensionEvent(t)
	ev.KillWiFi = true
	engine := New(r)
	result, err := engine.Apply(ev, false)
	if err == nil || result.Applied || !strings.Contains(err.Error(), "rollback incomplete") {
		t.Fatalf("rollback failure hidden: %+v %v", result, err)
	}
	if engine.Snapshot()["suspend:user0:app.one"] != "false" {
		t.Fatalf("lost unresolved package journal: %v", engine.Snapshot())
	}
	r.failUnsuspend = false
	if _, err := engine.Restore(ev); err != nil {
		t.Fatalf("restoration retry: %v", err)
	}
	if len(engine.Snapshot()) != 0 {
		t.Fatalf("unresolved journal after retry: %v", engine.Snapshot())
	}
}

func TestCPUUndoFailureKeepsOriginalValue(t *testing.T) {
	const path = "/sys/devices/system/cpu/cpu1/cpufreq/scaling_governor"
	r := sourceRunner("User 0: suspended=false")
	r.files[path] = "schedutil"
	r.failWiFi = true
	r.failCpuRestore = true
	ev := safeEvent()
	ev.HandleCores = "cpu1"
	ev.KillWiFi = true
	engine := New(r)
	result, err := engine.Apply(ev, false)
	if err == nil || result.Applied || !strings.Contains(err.Error(), "rollback incomplete") {
		t.Fatalf("failed CPU undo hidden: %+v %v", result, err)
	}
	if engine.Snapshot()["file:"+path] != "schedutil" {
		t.Fatalf("lost original governor: %v", engine.Snapshot())
	}
	r.failCpuRestore = false
	if _, err := engine.Restore(ev); err != nil {
		t.Fatal(err)
	}
	if r.files[path] != "schedutil" || len(engine.Snapshot()) != 0 {
		t.Fatalf("restoration not completed: value=%s journal=%v", r.files[path], engine.Snapshot())
	}
}

func TestEssentialsCannotBeTargetedByExplicitDenylist(t *testing.T) {
	r := sourceRunner("User 0: suspended=false")
	path := filepath.Join(t.TempDir(), "denylist")
	if err := os.WriteFile(path, []byte("com.android.systemui\n"), 0600); err != nil {
		t.Fatal(err)
	}
	ev := safeEvent()
	ev.HandleApps = "kill"
	ev.Denylist = path
	result, err := New(r).Apply(ev, false)
	if err == nil || result.Applied || !strings.Contains(err.Error(), "protected essential") {
		t.Fatalf("essential app not protected: %+v %v", result, err)
	}
	for _, call := range r.calls {
		if strings.HasPrefix(call, "am force-stop ") {
			t.Fatalf("package mutated despite protected target: %v", r.calls)
		}
	}
}

func TestConfiguredEssentialsAndMissingPolicyAreFailClosed(t *testing.T) {
	r := sourceRunner("User 0: suspended=false")
	ev := safeEvent()
	ev.HandleApps = "kill"
	ev.EssentialsPath = filepath.Join(t.TempDir(), "essentials")
	if _, err := New(r).Apply(ev, false); err == nil {
		t.Fatal("missing configured essentials was silently bypassed")
	}
	if err := os.WriteFile(ev.EssentialsPath, []byte("app.one\n"), 0600); err != nil {
		t.Fatal(err)
	}
	result, err := New(r).Apply(ev, false)
	if err == nil || result.Applied || !strings.Contains(err.Error(), "protected essential") {
		t.Fatalf("configured essential not protected: %+v %v", result, err)
	}
}

func TestSuspensionSnapshotSurvivesEngineRecreation(t *testing.T) {
	r := sourceRunner("User 0: suspended=false")
	ev := suspensionEvent(t)
	first := New(r)
	if _, err := first.Apply(ev, false); err != nil {
		t.Fatal(err)
	}
	second := New(r)
	second.LoadSnapshot(first.Snapshot())
	if _, err := second.Restore(ev); err != nil {
		t.Fatal(err)
	}
	if !hasExactCall(r.calls, "pm unsuspend --user 0 app.one") || len(second.Snapshot()) != 0 {
		t.Fatalf("cross-engine restore incorrect: calls=%v journal=%v", r.calls, second.Snapshot())
	}
}
