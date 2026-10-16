from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "apps/xbs/android-app/app/src/main/java/io/github/mikeceead/xbscompanion"


def source(name: str) -> str:
    return (APP / name).read_text()


def test_root_storage_repair_refuses_symlinked_dirs_before_marker_writes():
    probe = source("RootDiagnosticsProbe.java")
    assert "storageRepairSafetyShell" in probe
    assert "safe_repair_dir" in probe
    assert "Refusing symlinked XBS" in probe
    assert "Unsafe XBS" in probe
    assert "Refusing symlinked marker directory" in probe
    assert "sync >/dev/null 2>&1 || true" in probe
    assert "chmod 777" not in probe
    assert "chmod -R" not in probe
    assert "rm -rf" not in probe


def test_config_transaction_lock_metadata_is_revalidated_before_reclaim_and_release():
    transaction = source("XbsConfigTransaction.java")
    assert "Configuration transaction lock metadata is unsafe" in transaction
    assert "[ -L \\\"$LOCK/pid\\\" ]" in transaction
    assert "[ -L \\\"$LOCK/id\\\" ]" in transaction
    assert "Configuration transaction lock changed while reclaiming" in transaction
    assert "if [ -d \\\"$LOCK\\\" ] && [ ! -L \\\"$LOCK\\\" ] && [ ! -L \\\"$LOCK/id\\\" ]" in transaction


def test_config_transaction_status_and_latest_pointer_are_durably_published():
    transaction = source("XbsConfigTransaction.java")
    assert "CLEAN_MESSAGE=$(printf '%s'" in transaction
    assert "mv -f \\\"$STATUS.tmp\\\" \\\"$STATUS\\\"; sync >/dev/null 2>&1 || true" in transaction
    assert "LATEST_FILE + \".tmp\"" in transaction
    assert "SNAPSHOTS.complete" in transaction
    assert transaction.count("sync >/dev/null 2>&1 || true") >= 5


def test_config_transaction_latest_reader_bounds_metadata_before_parsing():
    transaction = source("XbsConfigTransaction.java")
    assert "MAX_JOURNAL_BYTES" in transaction
    assert "MAX_STATUS_LINE_BYTES" in transaction
    assert "safe_cat" in transaction
    assert "exceeds bounded journal size" in transaction
    assert "Transaction manifest" in transaction
    assert "Transaction snapshot journal" in transaction
    assert "Transaction journal has too many lines" in transaction
    assert "Transaction journal line exceeds bounded size" in transaction


def test_config_transaction_record_parser_rejects_unknown_status_and_unsafe_id():
    transaction = source("XbsConfigTransaction.java")
    assert "isKnownStatus" in transaction
    assert "Unknown transaction status" in transaction
    assert "Transaction status id is unsafe" in transaction
    assert "ManagedPathPolicy.safeInternalId(id, 80)" in transaction
    assert 'cleanMessage(fields.getOrDefault("message", ""))' in transaction

def test_v2_lock_release_requires_owned_metadata_and_uses_directory_removal():
    transaction = source("XbsConfigTransaction.java")
    assert 'if [ \\"$OWNER\\" = \\"$ID\\" ]' in transaction
    assert 'if [ -z \\"$OWNER\\" ] || [ \\"$OWNER\\" = \\"$ID\\" ]' not in transaction
    assert 'rm -f \\"$LOCK/pid\\" \\"$LOCK/id\\"; rmdir \\"$LOCK\\"' in transaction
    assert 'rm -rf \\"$LOCK\\"' not in transaction
    assert 'Configuration transaction lock owner is unsafe' in transaction
    assert 'Configuration transaction lock pid is unsafe' in transaction


def test_v2_latest_pointer_and_delete_restore_paths_are_bounded_and_durable():
    transaction = source("XbsConfigTransaction.java")
    assert "MAX_LATEST_POINTER_BYTES" in transaction
    assert "Latest transaction pointer is not a regular file" in transaction
    assert "Latest transaction pointer exceeds bounded journal size" in transaction
    assert '.append(XbsConfigApi.q(target)).append("; sync >/dev/null 2>&1 || true; else if' in transaction
    assert '.append(XbsConfigApi.q(entry.path)).append("; sync >/dev/null 2>&1 || true; ")' in transaction


def test_v2_root_probe_writability_marker_refuses_symlinked_probe_dirs():
    probe = source("RootDiagnosticsProbe.java")
    assert 'probe_dir(){ P=\\"$1\\"; if [ -L \\"$P\\" ] || [ ! -d \\"$P\\" ]; then printf false; return; fi;' in probe

