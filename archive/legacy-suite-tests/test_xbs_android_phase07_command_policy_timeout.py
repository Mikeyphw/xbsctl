from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "apps" / "xbs" / "android-app" / "app" / "src" / "main" / "java" / "io" / "github" / "mikeceead" / "xbscompanion"
TEST = ROOT / "apps" / "xbs" / "android-app" / "app" / "src" / "test" / "java" / "io" / "github" / "mikeceead" / "xbscompanion"


def read(path: Path) -> str:
    return path.read_text()


def test_command_policy_types_exist():
    assert (SRC / "ExecutionSource.java").exists()
    assert (SRC / "MutationClass.java").exists()
    assert (SRC / "TimeoutPolicy.java").exists()
    spec = read(SRC / "XbsCommandSpec.java")
    assert "Only XBS actions are allowed" in spec
    assert "MutationClass.from(action)" in spec
    assert "TimeoutPolicy.forMutation" in spec


def test_runner_is_bounded_and_timeout_aware():
    runner = read(SRC / "RootCommandRunner.java")
    service = read(SRC / "RootCommandService.java")
    assert "Executors.newFixedThreadPool(2)" in service
    assert "Executors.newCachedThreadPool" not in runner + service
    assert "RootCommandService.start" in runner
    assert "process.waitFor(spec.timeout.seconds, TimeUnit.SECONDS)" in runner
    assert "exitCode = 124" in runner
    assert "Root command timed out after" in runner


def test_cancellation_is_distinct_from_timeout():
    runner = read(SRC / "RootCommandRunner.java")
    assert "CANCELLATIONS" in runner
    assert "exitCode = 130" in runner
    assert "Root command cancelled" in runner
    assert "xbscompanion-runs" in runner
    assert "killRootChild(runId)" in runner
    assert "setsid sh -c" in runner


def test_stdout_and_stderr_are_collected_separately():
    runner = read(SRC / "RootCommandRunner.java")
    assert "redirectErrorStream(false)" in runner
    assert "getInputStream()" in runner
    assert "getErrorStream()" in runner
    assert "RunStore.finish(context, runId, exitCode, stdout, stderr, error)" in runner


def test_run_records_persist_policy_metadata():
    record = read(SRC / "RunRecord.java")
    store = read(SRC / "RunStore.java")
    assert "source" in record
    assert "mutation_class" in record
    assert "timeout_seconds" in record
    assert "r.source=spec.source.label" in store
    assert "r.mutationClass=spec.mutationClass.label" in store
    assert "r.timeoutSeconds=spec.timeout.seconds" in store


def test_notification_actions_are_tagged_as_notification_source():
    receiver = read(SRC / "NotificationActionReceiver.java")
    assert "ExecutionSource.NOTIFICATION" in receiver


def test_java_policy_unit_tests_are_present():
    test = read(TEST / "XbsCommandPolicyTest.java")
    assert "readOnlyActionsUseShortReadTimeout" in test
    assert "destructiveActionsGetLongerDeadline" in test
    assert "policyRejectsNonXbsActions" in test
