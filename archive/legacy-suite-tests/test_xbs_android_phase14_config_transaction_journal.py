from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "apps/xbs/android-app/app/src/main/java/io/github/mikeceead/xbscompanion"


def source(name):
    return (APP / name).read_text()


def test_config_saves_use_one_serialized_journaled_transaction():
    api = source("XbsConfigApi.java")
    transaction = source("XbsConfigTransaction.java")
    assert "XbsConfigTransaction.create(config, safeFiles, safeDeletes, reload)" in api
    assert "XbsConfigTransaction.execute(plan)" in api
    assert "acquire_lock" in transaction
    assert "Another configuration transaction is active" in transaction
    assert "write_status preparing" in transaction
    assert "write_status committed" in transaction
    assert "Automatic rollback restored the complete old file set" in transaction


def test_transaction_plan_and_snapshot_journal_record_sha256_digests():
    transaction = source("XbsConfigTransaction.java")
    assert "OLD_HASH=$(hash_file" in transaction
    assert "NEW_HASH=$(hash_file" in transaction
    assert "manifest.tsv" in transaction
    assert "snapshots.tsv" in transaction
    assert "__XBS_PLAN__" in transaction
    assert "__XBS_SNAPSHOTS__" in transaction
    assert "parts.length != 3" in transaction
    assert "snapshots_complete" in transaction
    assert "$SNAPSHOTS.complete" in transaction
    assert "isDigest(parts[2])" in transaction
    assert "--json validate" in transaction


def test_transaction_rejects_external_nested_symlink_and_oversized_targets():
    transaction = source("XbsConfigTransaction.java")
    assert "isManagedListPath" in transaction
    assert "ManagedPathPolicy.isDirectChild" in transaction
    path_policy = source("ManagedPathPolicy.java")
    assert "value.indexOf('/', prefix.length()) >= 0" in path_policy
    assert "Refusing symbolic-link target" in transaction
    assert "Managed XBS directory must not be a symbolic link" in transaction
    assert "MAX_CONFIG_BYTES" in transaction
    assert "MAX_LIST_BYTES" in transaction
    assert "MAX_ENTRIES" in transaction


def test_event_file_deletion_is_inside_the_same_transaction():
    api = source("XbsConfigApi.java")
    assert "Set<String> deletes=new LinkedHashSet<>()" in api
    assert "save(context, loaded.config,new LinkedHashMap<>(),deletes,true)" in api
    assert "if(saved.ok&&deleteManagedFiles)" not in api


def test_rollback_is_latest_only_and_refuses_post_commit_changes():
    transaction = source("XbsConfigTransaction.java")
    assert "guard_latest" in transaction
    assert "Transaction is no longer the latest configuration save" in transaction
    assert "Managed files changed after commit; refusing stale rollback" in transaction
    assert "verify_committed" in transaction
    assert "reapply_committed" in transaction
    assert "rollback_failed" in transaction


def test_interrupted_and_failed_transactions_offer_retryable_recovery():
    transaction = source("XbsConfigTransaction.java")
    assert "rolling_back|recovering|rollback_failed|recovery_failed" in transaction
    assert "write_status recovery_failed" in transaction
    assert "retry is safe" in transaction
    assert "ALLOW_PENDING=true" in transaction
    assert "snapshotsComplete" in transaction
    activity = source("XbsConfigActivity.java")
    assert "Recover interrupted transaction" in activity
    assert "recoverLatest(this,selected)" in activity


def test_configuration_ui_exposes_digest_lock_and_guarded_rollback():
    activity = source("XbsConfigActivity.java")
    assert "Transaction journal" in activity
    assert "serialized by a root-side lock" in activity
    assert "old/new SHA-256 digests" in activity
    assert "Rollback latest configuration save" in activity
    assert "newer save exists or managed files changed" in activity


def test_essentials_editor_uses_canonical_managed_path():
    essentials = source("XbsEssentialsActivity.java")
    assert "XbsConfigTransaction.isManagedListPath(target)" in essentials
    assert "Use managed essentials path" in essentials
    assert "Transactional writes are limited" in essentials


def test_support_bundle_reports_cached_transaction_without_root_prompt():
    support = source("SupportBundleBuilder.java")
    assert "FORMAT_VERSION = 18" in support
    assert "[Config transactions]" in support
    assert "XbsConfigTransactionStore.supportText(context)" in support
    assert "XbsConfigTransaction.latest()" not in support
    store = source("XbsConfigTransactionStore.java")
    assert "without another root prompt" in store


def test_phase13_foreground_service_lint_warning_is_removed():
    probe = source("RootDiagnosticsProbe.java")
    assert 'FOREGROUND_SERVICE_PERMISSION = "android.permission.FOREGROUND_SERVICE"' in probe
    assert 'FOREGROUND_SERVICE_SPECIAL_USE_PERMISSION = "android.permission.FOREGROUND_SERVICE_SPECIAL_USE"' in probe
    assert "Manifest.permission.FOREGROUND_SERVICE" not in probe
    assert "root.error" not in probe
