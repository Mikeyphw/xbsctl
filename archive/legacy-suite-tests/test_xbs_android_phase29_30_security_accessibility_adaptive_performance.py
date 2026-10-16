from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
APP=ROOT/'apps/xbs/android-app/app'
JAVA=APP/'src/main/java/io/github/mikeceead/xbscompanion'
def t(p): return p.read_text()
def test_manifest_security_and_predictive_back():
 m=t(APP/'src/main/AndroidManifest.xml')
 assert 'android:enableOnBackInvokedCallback="true"' in m
 assert 'android:usesCleartextTraffic="false"' in m
 assert 'android:networkSecurityConfig="@xml/xbs_network_security_config"' in m
 assert 'android:name=".SecuritySealActivity" android:exported="false"' in m
 assert '<base-config cleartextTrafficPermitted="false"' in t(APP/'src/main/res/xml/xbs_network_security_config.xml')
def test_managed_paths_and_shares_are_centralized():
 assert 'ManagedPathPolicy.isDirectChild' in t(JAVA/'XbsConfigTransaction.java')
 for name in ['MainActivity.java','MoreSettingsActivity.java','ActivityWorkbenchActivity.java']:
  s=t(JAVA/name); assert 'IntentSecurityPolicy.textShare' in s; assert 'new Intent(Intent.ACTION_SEND)' not in s
def test_stale_actions_are_persistently_consumed():
 store=t(JAVA/'RuntimeNotificationStore.java'); receiver=t(JAVA/'NotificationActionReceiver.java'); controller=t(JAVA/'NotificationController.java')
 for token in ['action_nonce','consumed_actions','consumeAction','commit()']: assert token in store
 assert 'EXTRA_NONCE' in receiver and 'stale_or_replayed_action' in receiver
 assert 'RuntimeNotificationStore.actionNonce' in controller and 'EXTRA_NONCE' in controller
def test_accessibility_adaptive_and_performance_contracts():
 ui=t(JAVA/'UiKit.java'); base=t(JAVA/'XbsActivity.java'); main=t(JAVA/'MainActivity.java')
 assert 'setAccessibilityHeading' in ui and 'dp(48)' in ui
 assert 'Type.displayCutout()' in base and 'Type.ime()' in base
 assert 'AdaptiveLayoutPolicy.useNavigationRail' in main and 'UiUpdateDebouncer' in main and 'PerformanceStore.recordRebuild' in main
 assert '.apply()' in t(JAVA/'PerformanceStore.java') and '.commit()' not in t(JAVA/'PerformanceStore.java')
 assert 'UiQualityStore.cached' in t(JAVA/'ReleaseReadinessEvaluator.java')
 assert 'AdaptiveLayoutPolicy.sealReady()' not in t(JAVA/'ReleaseReadinessEvaluator.java')
 assert (APP/'src/androidTest/java/io/github/mikeceead/xbscompanion/AccessibilityAdaptiveInstrumentedTest.java').is_file()
def test_release_and_support_seals():
 support=t(JAVA/'SupportBundleBuilder.java'); release=t(JAVA/'ReleaseReadinessPolicy.java'); gradle=t(APP/'build.gradle')
 assert 'FORMAT_VERSION = 18' in support and '[Manifest path and stale-action security seal]' in support and '[Accessibility adaptive layout and performance]' in support
 assert 'REQUIRED_SUPPORT_BUNDLE_FORMAT = 18' in release
 for name in ['ManagedPathPolicy.java','SecuritySealEvaluator.java','AdaptiveLayoutPolicy.java','PerformanceStore.java']:
  assert name in gradle


def test_r2_validator_and_durable_cancel_contracts():
    validator = (ROOT / '.devtool-artifact' / 'validate.py') if (ROOT / '.devtool-artifact' / 'validate.py').exists() else None
    receiver = t(JAVA/'NotificationActionReceiver.java')
    controller = t(JAVA/'NotificationController.java')
    store = t(JAVA/'RuntimeNotificationStore.java')
    assert 'consumeCancelAction' in receiver
    assert 'publishCancelAction' in controller
    assert 'xbs-notification://cancel/' in controller
    assert 'cancel_consumed' in store and 'ActionGuardStatus' in store


def test_r2_expanded_and_whole_screen_accessibility_contracts():
    main = t(JAVA/'MainActivity.java')
    activity = t(JAVA/'XbsActivity.java')
    policy = t(JAVA/'AccessibilityUiPolicy.java')
    quality = t(JAVA/'UiQualityReport.java')
    assert 'FrameLayout bodyHost' in main and 'Gravity.CENTER_HORIZONTAL' in main
    assert 'AccessibilityUiPolicy.apply' in activity
    assert 'view instanceof Spinner' in policy and 'view instanceof CompoundButton' in policy
    assert 'contentConstrained' in quality and 'adaptiveReady()' in quality


def test_r2_security_and_release_evidence_contracts():
    security = t(JAVA/'SecuritySealPolicy.java') + t(JAVA/'SecuritySealEvaluator.java')
    tasker = t(JAVA/'TaskerAuthorization.java')
    release = t(JAVA/'ReleaseReadinessEvaluator.java')
    assert 'missing.removeAll(actual)' in security and 'actual.addAll' in security
    assert 'pinStatus' in tasker and 'certificate.length() != 64' in tasker
    assert 'UiQualityStore.cached' in release
    assert 'AdaptiveLayoutPolicy.sealReady()' not in release
