from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "apps/xbs/android-app/app/src/main/java/io/github/mikeceead/xbscompanion"
TEST_APP = ROOT / "apps/xbs/android-app/app/src/test/java/io/github/mikeceead/xbscompanion"


def source(name: str) -> str:
    return (APP / name).read_text(encoding="utf-8")


def test_phase17_notification_channel_readiness_is_idempotently_created_before_probe_and_settings():
    controller = source("NotificationController.java")
    assert "static void ensureChannels(Context context)" in controller
    assert "ensureChannels(this.context);" in controller
    assert "ensureChannels(app);" in controller
    assert "ensureChannels(context);" in controller
    assert "manager.createNotificationChannel(progress);" in controller
    assert "CHANNEL_COMMAND_PROGRESS" in controller
    assert "CHANNEL_COMMAND_RESULTS" in controller
    assert "CHANNEL_STATUS" in controller
    assert "CHANNEL_ALERTS" in controller


def test_phase18_release_readiness_uses_current_release_requirements_and_bounded_transaction_parser():
    policy = source("ReleaseReadinessPolicy.java")
    test = (TEST_APP / "ReleaseReadinessPolicyTest.java").read_text(encoding="utf-8")
    assert "REQUIRED_SUPPORT_BUNDLE_FORMAT = 18" in policy
    assert "REQUIRED_VERSION_CODE = 15L" in policy
    assert "MAX_TRANSACTION_SUPPORT_CHARS" in policy
    assert "MAX_TRANSACTION_SUPPORT_LINES" in policy
    assert "MAX_TRANSACTION_LINE_CHARS" in policy
    assert "supportText.length() > MAX_TRANSACTION_SUPPORT_CHARS" in policy
    assert "lines.length > MAX_TRANSACTION_SUPPORT_LINES" in policy
    assert "line.length() > MAX_TRANSACTION_LINE_CHARS" in policy
    assert "invalid_oversized" in policy
    assert "parseTransactionStatus(repeat" in test


def test_phase18_release_readiness_support_text_is_bounded_before_export():
    report = source("ReleaseReadinessReport.java")
    store = source("XbsConfigTransactionStore.java")
    assert "MAX_ITEMS = 64" in report
    assert "MAX_FIELD_CHARS = 700" in report
    assert "oneLine(item.detail, MAX_FIELD_CHARS)" in report
    assert "copy.subList(0, Math.min(copy.size(), MAX_ITEMS))" in report
    assert "boundedSupportText" in store
    assert "MAX_SUPPORT_TEXT_CHARS = ReleaseReadinessPolicy.MAX_TRANSACTION_SUPPORT_CHARS" in store
    assert "OVERSIZED_SUPPORT_TEXT" in store
    assert "status=invalid_oversized" in store
    assert "? text : OVERSIZED_SUPPORT_TEXT" in store
    assert "text.substring(0, MAX_SUPPORT_TEXT_CHARS)" not in store
