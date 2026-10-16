from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
JAVA=ROOT/'apps/xbs/android-app/app/src/main/java/io/github/mikeceead/xbscompanion'
def src(n): return (JAVA/n).read_text()

def test_runtime_notification_uses_authenticated_transport_and_stale_action_tokens():
    controller=src('NotificationController.java'); receiver=src('NotificationActionReceiver.java'); policy=src('RuntimeNotificationPolicy.java')
    assert 'RuntimeNotificationStore.publish' in controller
    assert 'bridge.securityFailure' in controller
    assert 'openDiagnosticsAction("bridge-security")' in controller
    assert 'EXTRA_TOKEN' in receiver and 'EXTRA_ISSUED_AT' in receiver
    assert 'ACTION_MAX_AGE_MS' in policy and 'stale_action' in policy and 'bridge_security_failure' in policy

def test_foreground_service_launch_failures_are_durable_and_non_crashing():
    launcher=src('ForegroundServiceLauncher.java'); runner=src('RootCommandRunner.java'); service=src('RootCommandService.java')
    assert 'ForegroundServiceStartNotAllowedException' in launcher
    assert 'SecurityException' in launcher and 'RuntimeNotificationStore.recordLaunch' in launcher
    assert 'if (!launch.started)' in runner
    assert 'onTimeout(int startId, int fgsType)' in service

def test_notification_taps_have_explicit_components_immutable_pending_intents_and_back_stacks():
    controller=src('NotificationController.java'); receiver=src('NotificationActionReceiver.java')
    assert 'TaskStackBuilder' in controller and 'activityStack(' in controller
    assert 'PendingIntent.FLAG_IMMUTABLE' in controller
    assert 'xbs-notification://runtime/' in controller and 'runtime-action' in controller
    assert 'intent.getComponent() == null' in receiver
    assert 'context.getPackageName().equals(intent.getComponent().getPackageName())' in receiver

def test_runtime_notification_evidence_is_exposed_without_refreshing_root():
    support=src('SupportBundleBuilder.java'); store=src('RuntimeNotificationStore.java'); settings=src('MoreSettingsActivity.java')
    assert 'FORMAT_VERSION = 18' in support and '[Runtime notification 2.0]' in support
    assert 'RuntimeNotificationStore.cached' in support
    assert 'getSharedPreferences' in store and 'commit()' in store
    publish=store[store.index('static String publish'):store.index('static void recordService')]
    assert 'putBoolean("service_active", true)' not in publish
    assert 'Runtime Notification 2.0' in settings

def test_phase27_policy_is_covered_by_real_junit_source():
    test=(ROOT/'apps/xbs/android-app/app/src/test/java/io/github/mikeceead/xbscompanion/RuntimeNotificationPolicyTest.java').read_text()
    for marker in ['stateTokenChangesAcrossRuntimeAndBridgeState','staleExpiredAndSecurityBlockedActionsAreRejected','authenticatedActionsAndMonitorStopAreAllowedButMissingTransportBlocksMutation','missingTransportNeverExposesMutationActions','monitorStopStillRequiresTheCurrentUnexpiredToken']:
        assert marker in test


def test_foreground_promotion_and_timeout_cleanup_are_fail_safe():
    launcher=src('ForegroundServiceLauncher.java'); service=src('RootCommandService.java'); monitor=src('EventMonitorService.java')
    assert 'static ForegroundServiceLaunchResult promote' in launcher
    assert 'ForegroundServiceLauncher.promote' in service and 'abortOutstanding' in service
    assert 'state.compareAndSet(0, 2)' in service and 'failBeforeExecution' in service
    assert 'ForegroundServiceLauncher.promote' in monitor and 'if (!promotion.started)' in monitor


def test_release_gate_fingerprints_runtime_notification_integrations():
    gradle=(ROOT/'apps/xbs/android-app/app/build.gradle').read_text()
    for name in ['RuntimeNotificationPolicy.java','NotificationController.java','NotificationActionReceiver.java','RootCommandService.java','RootCommandRunner.java','EventMonitorService.java']:
        assert name in gradle
