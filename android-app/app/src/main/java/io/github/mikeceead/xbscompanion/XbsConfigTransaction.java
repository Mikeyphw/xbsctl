package io.github.mikeceead.xbscompanion;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Journaled, serialized and rollback-safe promotion of XBS configuration and managed list files.
 *
 * <p>The shell transaction deliberately keeps the complete staged and original sets after commit so
 * the latest transaction can be inspected or rolled back. Every mutating action acquires the same
 * root-side lock and revalidates the latest pointer immediately before touching a managed file.</p>
 */
final class XbsConfigTransaction {
    static final String TRANSACTION_DIR = XbsConfigApi.BACKUP_DIR + "/transactions";
    private static final String LATEST_FILE = TRANSACTION_DIR + "/latest";
    private static final String LOCK_DIR = TRANSACTION_DIR + "/.lock";
    private static final int MAX_ENTRIES = 64;
    private static final int MAX_CONFIG_BYTES = 2 * 1024 * 1024;
    private static final int MAX_LIST_BYTES = 512 * 1024;
    private static final int MAX_JOURNAL_BYTES = 256 * 1024;
    private static final int MAX_STATUS_LINE_BYTES = 2048;
    private static final int MAX_LATEST_POINTER_BYTES = 128;

    private XbsConfigTransaction() {}

    static Plan create(JSONObject config, Map<String, String> writes, Set<String> deletes, boolean reload) {
        if (config == null) throw new IllegalArgumentException("Config is missing");
        Map<String, String> safeWrites = writes == null ? Collections.emptyMap() : writes;
        Set<String> safeDeletes = deletes == null ? Collections.emptySet() : deletes;
        if (safeWrites.size() + safeDeletes.size() > MAX_ENTRIES - 1) {
            throw new IllegalArgumentException("Too many managed files in one transaction");
        }

        String json;
        try {
            json = config.toString(2);
        } catch (JSONException error) {
            throw new IllegalArgumentException("Configuration cannot be serialized", error);
        }
        requireSize("Configuration", json, MAX_CONFIG_BYTES);
        List<Entry> entries = new ArrayList<>();
        entries.add(Entry.write(XbsConfigApi.CONFIG, json));

        List<Map.Entry<String, String>> orderedWrites = new ArrayList<>(safeWrites.entrySet());
        orderedWrites.sort(Comparator.comparing(Map.Entry::getKey));
        Set<String> seen = new LinkedHashSet<>();
        seen.add(XbsConfigApi.CONFIG);
        for (Map.Entry<String, String> item : orderedWrites) {
            String path = requireManagedListPath(item.getKey());
            if (!seen.add(path)) throw new IllegalArgumentException("Duplicate transaction path: " + path);
            String value = item.getValue() == null ? "" : item.getValue();
            requireSize("Managed list " + path, value, MAX_LIST_BYTES);
            entries.add(Entry.write(path, value));
        }

        List<String> orderedDeletes = new ArrayList<>(safeDeletes);
        Collections.sort(orderedDeletes);
        for (String item : orderedDeletes) {
            String path = requireManagedListPath(item);
            if (!seen.add(path)) throw new IllegalArgumentException("Path cannot be written and deleted together: " + path);
            entries.add(Entry.delete(path));
        }

        long createdAt = System.currentTimeMillis();
        String seed = json + '\n' + entries.toString() + '\n' + createdAt;
        String id = createdAt + "-" + sha256(seed).substring(0, 12);
        return new Plan(id, createdAt, reload, Collections.unmodifiableList(entries));
    }

    static String buildExecuteShellForTest(Plan plan) { return buildExecuteShell(plan); }
    static String buildReadLatestShellForTest() { return buildReadLatestShell(); }
    static String buildRollbackShellForTest(Record record) { return buildRollbackShell(record); }
    static String buildRecoveryShellForTest(Record record) { return buildRecoveryShell(record); }
    static Record parseRecordForTest(String output) { return parseRecord(output); }

    static XbsRootApi.CommandOutput execute(Plan plan) {
        if (plan == null) return new XbsRootApi.CommandOutput(2, "", "Configuration transaction plan is missing");
        XbsRootApi.CommandOutput output = XbsRootApi.runRootLarge(buildExecuteShell(plan), 75);
        return withTimeoutState(output);
    }

    static Record latest() {
        XbsRootApi.CommandOutput output = XbsRootApi.runRoot(buildReadLatestShell());
        if (output.exitCode != 0) {
            String message = output.error.isEmpty() ? output.stdout : output.error;
            return Record.unavailable(message);
        }
        return parseRecord(output.stdout);
    }

    static XbsRootApi.CommandOutput rollbackLatest(Record record) {
        if (record == null || !record.canRollback()) {
            return new XbsRootApi.CommandOutput(2, "", "Latest configuration transaction is not rollback-ready");
        }
        try { return withTimeoutState(XbsRootApi.runRootLarge(buildRollbackShell(record), 75)); }
        catch (IllegalArgumentException invalid) { return new XbsRootApi.CommandOutput(2, "", invalid.getMessage()); }
    }

    static XbsRootApi.CommandOutput recoverLatest(Record record) {
        if (record == null || !record.needsRecovery()) {
            return new XbsRootApi.CommandOutput(2, "", "Latest configuration transaction does not require recovery");
        }
        try { return withTimeoutState(XbsRootApi.runRootLarge(buildRecoveryShell(record), 75)); }
        catch (IllegalArgumentException invalid) { return new XbsRootApi.CommandOutput(2, "", invalid.getMessage()); }
    }

    private static XbsRootApi.CommandOutput withTimeoutState(XbsRootApi.CommandOutput output) {
        if (output == null || (output.exitCode != 124 && output.exitCode != 125)) return output;
        Record durable = latest();
        String state = durable == null ? "unknown" : durable.status;
        String detail = output.error + "; durable transaction state=" + state;
        return new XbsRootApi.CommandOutput(output.exitCode, output.stdout, detail);
    }

    static XbsRootApi.CommandOutput withTimeoutStateForTest(XbsRootApi.CommandOutput output, Record durable) {
        if (output == null || (output.exitCode != 124 && output.exitCode != 125)) return output;
        String state = durable == null ? "unknown" : durable.status;
        return new XbsRootApi.CommandOutput(output.exitCode, output.stdout, output.error + "; durable transaction state=" + state);
    }

    static boolean isManagedListPath(String path) {
        return ManagedPathPolicy.isDirectChild(XbsConfigApi.LIST_DIR, path);
    }

    private static String requireManagedListPath(String path) {
        if (!isManagedListPath(path)) {
            throw new IllegalArgumentException("Path is outside the managed XBS list directory: " + path);
        }
        return path;
    }

    private static void requireSize(String label, String value, int maximum) {
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > maximum) throw new IllegalArgumentException(label + " exceeds " + maximum + " bytes");
    }

    private static String buildExecuteShell(Plan plan) {
        String tx = TRANSACTION_DIR + "/" + plan.id;
        String stage = tx + "/stage";
        String original = tx + "/original";
        String manifest = tx + "/manifest.tsv";
        String snapshots = tx + "/snapshots.tsv";
        String status = tx + "/status";
        StringBuilder shell = new StringBuilder(20_000);
        shell.append("set -eu; umask 077; ")
            .append(commonVariables(plan.id, tx, stage, original, manifest, snapshots, status))
            .append("LOCKED=false; TX_READY=false; PROMOTION_STARTED=false; RELOAD_STARTED=false; COMMITTED=false; ALLOW_PENDING=true; ")
            .append(managedDirectoryFunctions())
            .append(hashFunction())
            .append(lockFunctions())
            .append(previousGuardFunction())
            .append(statusFunction(plan.createdAt, plan.reload, plan.entries.size()))
            .append(restoreFunction(plan.entries, original))
            .append(verifyOriginalFunction(plan.entries, original))
            .append("on_exit(){ RC=$1; trap - EXIT HUP INT TERM; ")
            .append("if [ \"$RELOAD_STARTED\" = true ] && [ \"$COMMITTED\" != true ]; then write_status recovery_required 'Daemon reload outcome uncertain; preserve files and journal' >/dev/null 2>&1 || true; release_lock; exit \"$RC\"; fi; ")
            .append("if [ \"$TX_READY\" = true ] && [ \"$COMMITTED\" != true ]; then ")
            .append("ROLLBACK_OK=true; assert_managed_dirs >/dev/null 2>&1 || ROLLBACK_OK=false; ")
            .append("restore_original >/dev/null 2>&1 || ROLLBACK_OK=false; verify_original >/dev/null 2>&1 || ROLLBACK_OK=false; ");
        // No daemon reload is attempted from automatic rollback. Once a reload
        // starts, the uncertain journal is preserved for deliberate recovery.
        shell.append("if [ \"$ROLLBACK_OK\" = true ]; then write_status rolled_back 'Automatic rollback restored the complete old file set' >/dev/null 2>&1 || true; ")
            .append("else write_status rollback_failed 'Automatic rollback could not verify the complete old file set' >/dev/null 2>&1 || true; fi; fi; ")
            .append("release_lock; exit \"$RC\"; }; ")
            .append("trap 'on_exit $?' EXIT; trap 'exit 130' HUP INT TERM; ")
            .append("assert_managed_dirs; acquire_lock; guard_previous; ")
            .append("if [ -e \"$TX\" ] || [ -L \"$TX\" ]; then printf 'Configuration transaction identifier already exists\\n' >&2; exit 74; fi; ")
            .append("mkdir -p \"$STAGE\" \"$ORIGINAL\"; TX_READY=true; : > \"$MANIFEST\"; : > \"$SNAPSHOTS\"; sync >/dev/null 2>&1 || true; ");

        for (Entry entry : plan.entries) appendPlanManifest(shell, entry, manifest);

        shell.append("write_status preparing 'Staging configuration transaction'; ")
            .append("printf '%s\\n' \"$ID\" > ").append(XbsConfigApi.q(LATEST_FILE + ".tmp")).append("; mv -f ")
            .append(XbsConfigApi.q(LATEST_FILE + ".tmp")).append(' ').append(XbsConfigApi.q(LATEST_FILE)).append("; sync >/dev/null 2>&1 || true; ");

        for (int index = 0; index < plan.entries.size(); index++) {
            appendSnapshotAndStage(shell, plan.entries.get(index), index, original, stage, snapshots);
        }

        shell.append(": > \"$SNAPSHOTS.complete\"; sync >/dev/null 2>&1 || true; ALLOW_PENDING=false; write_status prepared 'All files staged and validated'; PROMOTION_STARTED=true; ")
            .append("write_status promoting 'Promoting staged files'; ");

        for (int index = 0; index < plan.entries.size(); index++) {
            appendPromote(shell, plan.entries.get(index), index, stage);
        }

        shell.append("write_status promoted 'Files promoted; validating runtime configuration'; ")
            .append(xbs("--json validate")).append(" >/dev/null; ");
        if (plan.reload) shell.append("RELOAD_STARTED=true; write_status reload_pending 'Awaiting exact configuration generation acknowledgement'; ")
            .append(confirmedReload(plan.entries.get(0).newDigest)).append(" >/dev/null; ");
        for (int index = 0; index < plan.entries.size(); index++) {
            appendVerifyCommitted(shell, plan.entries.get(index), index, stage);
        }
        shell.append("write_status committed 'Configuration transaction committed and verified'; COMMITTED=true; ")
            .append("release_lock; trap - EXIT HUP INT TERM; ")
            .append("printf 'transaction_id=%s\\nstatus=committed\\nentry_count=")
            .append(plan.entries.size()).append("\\n' \"$ID\";");
        return shell.toString();
    }

    private static String commonVariables(String id, String tx, String stage, String original, String manifest, String snapshots, String status) {
        return "ID=" + XbsConfigApi.q(id) + "; TX=" + XbsConfigApi.q(tx)
            + "; STAGE=" + XbsConfigApi.q(stage) + "; ORIGINAL=" + XbsConfigApi.q(original)
            + "; MANIFEST=" + XbsConfigApi.q(manifest) + "; SNAPSHOTS=" + XbsConfigApi.q(snapshots)
            + "; STATUS=" + XbsConfigApi.q(status) + "; LOCK=" + XbsConfigApi.q(LOCK_DIR) + "; ";
    }

    private static String managedDirectoryFunctions() {
        String configRoot = parent(XbsConfigApi.CONFIG);
        String configBase = parent(configRoot);
        String dirs = XbsConfigApi.q(configBase) + " " + XbsConfigApi.q(configRoot) + " "
            + XbsConfigApi.q(XbsConfigApi.LIST_DIR) + " " + XbsConfigApi.q(XbsConfigApi.BACKUP_DIR) + " "
            + XbsConfigApi.q(TRANSACTION_DIR);
        return "assert_managed_dirs(){ for D in " + dirs + "; do "
            + "if [ -L \"$D\" ]; then printf 'Managed XBS directory must not be a symbolic link: %s\\n' \"$D\" >&2; return 73; fi; done; "
            + "mkdir -p " + dirs + "; for D in " + dirs + "; do "
            + "if [ ! -d \"$D\" ] || [ -L \"$D\" ]; then printf 'Managed XBS directory is unsafe: %s\\n' \"$D\" >&2; return 73; fi; done; }; ";
    }

    private static String hashFunction() {
        return "hash_file(){ if command -v sha256sum >/dev/null 2>&1; then sha256sum \"$1\" | awk '{print $1}'; "
            + "elif command -v toybox >/dev/null 2>&1; then toybox sha256sum \"$1\" | awk '{print $1}'; "
            + "else printf 'SHA-256 utility is unavailable\\n' >&2; return 127; fi; }; ";
    }

    private static String lockFunctions() {
        return "release_lock(){ if [ \"$LOCKED\" = true ]; then "
            + "if [ -d \"$LOCK\" ] && [ ! -L \"$LOCK\" ] && [ ! -L \"$LOCK/id\" ] && [ ! -L \"$LOCK/pid\" ]; then OWNER=$(cat \"$LOCK/id\" 2>/dev/null || true); "
            + "if [ \"$OWNER\" = \"$ID\" ]; then rm -f \"$LOCK/pid\" \"$LOCK/id\"; rmdir \"$LOCK\" 2>/dev/null || true; sync >/dev/null 2>&1 || true; fi; fi; LOCKED=false; fi; }; "
            + "acquire_lock(){ if [ -L \"$LOCK\" ]; then printf 'Configuration transaction lock is a symbolic link\n' >&2; return 73; fi; "
            + "if ! mkdir \"$LOCK\" 2>/dev/null; then "
            + "if [ -L \"$LOCK\" ] || [ ! -d \"$LOCK\" ] || [ -L \"$LOCK/pid\" ] || [ -L \"$LOCK/id\" ]; then printf 'Configuration transaction lock metadata is unsafe\n' >&2; return 73; fi; "
            + "OLD_OWNER=$(cat \"$LOCK/id\" 2>/dev/null || true); case \"$OLD_OWNER\" in ''|*[!A-Za-z0-9._:-]*) printf 'Configuration transaction lock owner is unsafe\n' >&2; return 73;; esac; "
            + "OLD_PID=$(cat \"$LOCK/pid\" 2>/dev/null || true); case \"$OLD_PID\" in ''|*[!0-9]*) printf 'Configuration transaction lock pid is unsafe\n' >&2; return 73;; esac; "
            + "if kill -0 \"$OLD_PID\" 2>/dev/null; then printf 'Another configuration transaction is active\n' >&2; return 75; fi; "
            + "if [ -L \"$LOCK\" ] || [ -L \"$LOCK/pid\" ] || [ -L \"$LOCK/id\" ]; then printf 'Configuration transaction lock changed while reclaiming\n' >&2; return 73; fi; "
            + "rm -f \"$LOCK/pid\" \"$LOCK/id\"; rmdir \"$LOCK\" 2>/dev/null || { printf 'Could not reclaim stale configuration transaction lock\n' >&2; return 75; }; "
            + "mkdir \"$LOCK\" 2>/dev/null || { printf 'Could not reclaim stale configuration transaction lock\n' >&2; return 75; }; fi; "
            + "LOCKED=true; printf '%s\n' \"$ID\" > \"$LOCK/id\"; printf '%s\n' \"$$\" > \"$LOCK/pid\"; sync >/dev/null 2>&1 || true; }; ";
    }

    private static String previousGuardFunction() {
        return "guard_previous(){ POINTER=" + XbsConfigApi.q(LATEST_FILE) + "; "
            + "if [ -L \"$POINTER\" ]; then printf 'Previous transaction pointer is unsafe\\n' >&2; return 73; fi; "
            + "if [ ! -e \"$POINTER\" ]; then return 0; fi; "
            + "if [ ! -f \"$POINTER\" ]; then printf 'Previous transaction pointer is not regular\\n' >&2; return 73; fi; "
            + "OLD_SIZE=$(wc -c < \"$POINTER\" 2>/dev/null || printf 999999); "
            + "case \"$OLD_SIZE\" in ''|*[!0-9]*) return 73;; esac; if [ \"$OLD_SIZE\" -gt 128 ]; then printf 'Previous transaction pointer is oversized\\n' >&2; return 73; fi; "
            + "OLD_ID=$(cat \"$POINTER\" 2>/dev/null || true); "
            + "case \"$OLD_ID\" in ''|*[!A-Za-z0-9_-]*) printf 'Unresolved prior transaction identifier\\n' >&2; return 76;; esac; "
            + "OLD_DIR=" + XbsConfigApi.q(TRANSACTION_DIR) + "/$OLD_ID; "
            + "if [ -L \"$OLD_DIR\" ] || [ ! -d \"$OLD_DIR\" ] || [ -L \"$OLD_DIR/status\" ] || [ ! -f \"$OLD_DIR/status\" ]; then printf 'Unresolved prior transaction journal\\n' >&2; return 76; fi; "
            + "OLD_STATUS_SIZE=$(wc -c < \"$OLD_DIR/status\" 2>/dev/null || printf 999999); "
            + "case \"$OLD_STATUS_SIZE\" in ''|*[!0-9]*) return 73;; esac; if [ \"$OLD_STATUS_SIZE\" -gt 2048 ]; then printf 'Prior transaction status is oversized\\n' >&2; return 73; fi; "
            + "OLD_STATE=$(sed -n 's/^status=//p' \"$OLD_DIR/status\" | head -n1); "
            + "case \"$OLD_STATE\" in committed|rolled_back|recovered) return 0;; "
            + "*) printf 'Unresolved prior transaction: %s\\n' \"$OLD_STATE\" >&2; return 76;; esac; }; ";
    }

    private static String statusFunction(long createdAt, boolean reload, int entries) {
        return "write_status(){ S=\"$1\"; M=\"$2\"; NOW=$(date +%s 2>/dev/null || printf 0); "
            + "SNAPSHOTS_COMPLETE=false; if [ -f \"$SNAPSHOTS.complete\" ] && [ ! -L \"$SNAPSHOTS.complete\" ]; then SNAPSHOTS_COMPLETE=true; fi; "
            + "CLEAN_MESSAGE=$(printf '%s' \"$M\" | tr '\r\n\t' '   ' | cut -c1-512); "
            + "{ printf 'id=%s\n' \"$ID\"; printf 'created_at=" + createdAt + "\n'; printf 'updated_at=%s\n' \"$NOW\"; "
            + "printf 'status=%s\n' \"$S\"; printf 'reload=" + reload + "\n'; printf 'entry_count=" + entries + "\n'; "
            + "printf 'snapshots_complete=%s\n' \"$SNAPSHOTS_COMPLETE\"; printf 'message=%s\n' \"$CLEAN_MESSAGE\"; } > \"$STATUS.tmp\"; mv -f \"$STATUS.tmp\" \"$STATUS\"; sync >/dev/null 2>&1 || true; }; ";
    }

    private static void appendPlanManifest(StringBuilder shell, Entry entry, String manifest) {
        shell.append("printf '").append(entry.delete ? "D" : "W").append("\\t%s\\t%s\\n' ")
            .append(XbsConfigApi.q(encode(entry.path))).append(' ').append(XbsConfigApi.q(entry.newDigest))
            .append(" >> ").append(XbsConfigApi.q(manifest)).append("; ");
    }

    private static void appendSnapshotAndStage(StringBuilder shell, Entry entry, int index, String original, String stage, String snapshots) {
        String target = entry.path;
        String backup = original + "/" + index;
        String staged = stage + "/" + index;
        shell.append("if [ -L ").append(XbsConfigApi.q(target)).append(" ]; then printf 'Refusing symbolic-link target: %s\\n' ")
            .append(XbsConfigApi.q(target)).append(" >&2; exit 73; fi; ")
            .append("if [ -e ").append(XbsConfigApi.q(target)).append(" ] && [ ! -f ").append(XbsConfigApi.q(target))
            .append(" ]; then printf 'Managed target is not a regular file: %s\\n' ").append(XbsConfigApi.q(target)).append(" >&2; exit 73; fi; ")
            .append("OLD_STATE=absent; OLD_HASH=-; if [ -f ").append(XbsConfigApi.q(target)).append(" ]; then cp -p ")
            .append(XbsConfigApi.q(target)).append(' ').append(XbsConfigApi.q(backup)).append("; : > ")
            .append(XbsConfigApi.q(backup + ".present")).append("; OLD_STATE=present; OLD_HASH=$(hash_file ")
            .append(XbsConfigApi.q(backup)).append("); else : > ").append(XbsConfigApi.q(backup + ".absent")).append("; fi; ");
        if (entry.delete) {
            shell.append("NEW_HASH=-; ");
        } else {
            String encoded = encode(entry.content);
            int size = entry.content.getBytes(StandardCharsets.UTF_8).length;
            shell.append("printf %s ").append(XbsConfigApi.q(encoded)).append(" | base64 -d > ").append(XbsConfigApi.q(staged)).append("; ")
                .append("test \"$(wc -c < ").append(XbsConfigApi.q(staged)).append(")\" -eq ").append(size).append("; ")
                .append("NEW_HASH=$(hash_file ").append(XbsConfigApi.q(staged)).append("); test \"$NEW_HASH\" = ")
                .append(XbsConfigApi.q(entry.newDigest)).append("; ");
        }
        shell.append("printf '%s\\t%s\\t%s\\n' ")
            .append(XbsConfigApi.q(encode(target))).append(" \"$OLD_STATE\" \"$OLD_HASH\" >> ")
            .append(XbsConfigApi.q(snapshots)).append("; ");
    }

    private static String restoreFunction(List<Entry> entries, String original) {
        StringBuilder shell = new StringBuilder("restore_original(){ assert_managed_dirs; ");
        for (int index = 0; index < entries.size(); index++) {
            String target = entries.get(index).path;
            String backup = original + "/" + index;
            shell.append("if [ -f ").append(XbsConfigApi.q(backup + ".present")).append(" ]; then test -f ")
                .append(XbsConfigApi.q(backup)).append("; mkdir -p ").append(XbsConfigApi.q(parent(target))).append("; cp -f ")
                .append(XbsConfigApi.q(backup)).append(' ').append(XbsConfigApi.q(target + ".xbsapp.rollback"))
                .append("; mv -f ").append(XbsConfigApi.q(target + ".xbsapp.rollback")).append(' ').append(XbsConfigApi.q(target))
                .append("; sync >/dev/null 2>&1 || true; elif [ -f ").append(XbsConfigApi.q(backup + ".absent")).append(" ]; then rm -f ")
                .append(XbsConfigApi.q(target)).append("; sync >/dev/null 2>&1 || true; else if [ \"$ALLOW_PENDING\" != true ]; then printf 'Missing transaction snapshot marker for %s\\n' ")
                .append(XbsConfigApi.q(target)).append(" >&2; return 81; fi; fi; ");
        }
        return shell.append("}; ").toString();
    }

    private static String verifyOriginalFunction(List<Entry> entries, String original) {
        StringBuilder shell = new StringBuilder("verify_original(){ ");
        for (int index = 0; index < entries.size(); index++) {
            String target = entries.get(index).path;
            String backup = original + "/" + index;
            shell.append("if [ -f ").append(XbsConfigApi.q(backup + ".present")).append(" ]; then cmp -s ")
                .append(XbsConfigApi.q(backup)).append(' ').append(XbsConfigApi.q(target))
                .append(" || return 82; elif [ -f ").append(XbsConfigApi.q(backup + ".absent")).append(" ]; then test ! -e ")
                .append(XbsConfigApi.q(target)).append(" || return 82; else if [ \"$ALLOW_PENDING\" != true ]; then return 81; fi; fi; ");
        }
        return shell.append("}; ").toString();
    }

    private static void appendPromote(StringBuilder shell, Entry entry, int index, String stage) {
        String target = entry.path;
        shell.append("if [ -L ").append(XbsConfigApi.q(target)).append(" ]; then printf 'Refusing symbolic-link target during promotion: %s\\n' ")
            .append(XbsConfigApi.q(target)).append(" >&2; exit 73; fi; ");
        if (entry.delete) {
            shell.append("rm -f ").append(XbsConfigApi.q(target)).append("; sync >/dev/null 2>&1 || true; ");
        } else {
            String temp = target + ".xbsapp.tx";
            shell.append("mkdir -p ").append(XbsConfigApi.q(parent(target))).append("; cp -f ")
                .append(XbsConfigApi.q(stage + "/" + index)).append(' ').append(XbsConfigApi.q(temp))
                .append("; mv -f ").append(XbsConfigApi.q(temp)).append(' ').append(XbsConfigApi.q(target)).append("; sync >/dev/null 2>&1 || true; ");
        }
    }

    private static void appendVerifyCommitted(StringBuilder shell, Entry entry, int index, String stage) {
        if (entry.delete) {
            shell.append("test ! -e ").append(XbsConfigApi.q(entry.path)).append("; ");
        } else {
            shell.append("test -f ").append(XbsConfigApi.q(entry.path)).append("; cmp -s ")
                .append(XbsConfigApi.q(stage + "/" + index)).append(' ').append(XbsConfigApi.q(entry.path)).append("; ")
                .append("test \"$(hash_file ").append(XbsConfigApi.q(entry.path)).append(")\" = ")
                .append(XbsConfigApi.q(entry.newDigest)).append("; ");
        }
    }

    private static String buildReadLatestShell() {
        return "safe_cat(){ FILE=\"$1\"; LABEL=\"$2\"; LIMIT=\"$3\"; "
            + "if [ -L \"$FILE\" ]; then printf 'status=invalid\nmessage=%s is a symbolic link\n' \"$LABEL\"; exit 0; fi; "
            + "if [ ! -f \"$FILE\" ]; then return 1; fi; SIZE=$(wc -c < \"$FILE\" 2>/dev/null || printf 0); "
            + "case \"$SIZE\" in ''|*[!0-9]*) SIZE=0;; esac; if [ \"$SIZE\" -gt \"$LIMIT\" ]; then printf 'status=invalid\nmessage=%s exceeds bounded journal size\n' \"$LABEL\"; exit 0; fi; cat \"$FILE\"; }; "
            + "if [ -L " + XbsConfigApi.q(LATEST_FILE) + " ]; then printf 'status=invalid\nmessage=Latest transaction pointer is a symbolic link\n'; exit 0; fi; "
            + "if [ ! -e " + XbsConfigApi.q(LATEST_FILE) + " ]; then printf 'status=none\nmessage=No configuration transaction has been recorded\n'; exit 0; fi; "
            + "if [ ! -f " + XbsConfigApi.q(LATEST_FILE) + " ]; then printf 'status=invalid\nmessage=Latest transaction pointer is not a regular file\n'; exit 0; fi; "
            + "LATEST_SIZE=$(wc -c < " + XbsConfigApi.q(LATEST_FILE) + " 2>/dev/null || printf 0); case \"$LATEST_SIZE\" in ''|*[!0-9]*) LATEST_SIZE=0;; esac; "
            + "if [ \"$LATEST_SIZE\" -gt " + MAX_LATEST_POINTER_BYTES + " ]; then printf 'status=invalid\nmessage=Latest transaction pointer exceeds bounded journal size\n'; exit 0; fi; "
            + "ID=$(cat " + XbsConfigApi.q(LATEST_FILE) + "); case \"$ID\" in ''|*[!A-Za-z0-9_-]*) printf 'status=invalid\nmessage=Invalid latest transaction identifier\n'; exit 0;; esac; "
            + "TX=" + XbsConfigApi.q(TRANSACTION_DIR) + "/$ID; if [ -L \"$TX\" ]; then printf 'id=%s\nstatus=invalid\nmessage=Transaction directory is a symbolic link\n' \"$ID\"; exit 0; fi; "
            + "if [ ! -d \"$TX\" ]; then printf 'id=%s\nstatus=missing\nmessage=Latest transaction directory is missing\n' \"$ID\"; exit 0; fi; "
            + "safe_cat \"$TX/status\" 'Transaction status' " + MAX_STATUS_LINE_BYTES + " || printf 'id=%s\nstatus=unknown\nmessage=Transaction status is missing\n' \"$ID\"; "
            + "printf '__XBS_PLAN__\n'; safe_cat \"$TX/manifest.tsv\" 'Transaction manifest' " + MAX_JOURNAL_BYTES + " || true; "
            + "printf '__XBS_SNAPSHOTS__\n'; safe_cat \"$TX/snapshots.tsv\" 'Transaction snapshot journal' " + MAX_JOURNAL_BYTES + " || true;";
    }

    private static Record parseRecord(String output) {
        Map<String, String> fields = new LinkedHashMap<>();
        Map<String, Entry> planned = new LinkedHashMap<>();
        Map<String, String[]> snapshots = new LinkedHashMap<>();
        int section = 0;
        String parseError = "";
        int parsedLines = 0;
        for (String line : (output == null ? "" : output).replace("\r", "").split("\n")) {
            parsedLines++;
            if (parsedLines > MAX_ENTRIES * 3 + 24) {
                parseError = "Transaction journal has too many lines";
                break;
            }
            if (line.getBytes(StandardCharsets.UTF_8).length > MAX_STATUS_LINE_BYTES) {
                parseError = "Transaction journal line exceeds bounded size";
                continue;
            }
            if ("__XBS_PLAN__".equals(line)) { section = 1; continue; }
            if ("__XBS_SNAPSHOTS__".equals(line)) { section = 2; continue; }
            if (section == 1) {
                if (line.isEmpty()) continue;
                String[] parts = line.split("\\t", -1);
                if (parts.length != 3 || !("W".equals(parts[0]) || "D".equals(parts[0]))) {
                    parseError = "Malformed transaction plan";
                    continue;
                }
                try {
                    String path = decode(parts[1]);
                    boolean delete = "D".equals(parts[0]);
                    boolean pathAllowed = XbsConfigApi.CONFIG.equals(path) || isManagedListPath(path);
                    boolean digestAllowed = delete ? "-".equals(parts[2]) : isDigest(parts[2]);
                    if (!pathAllowed || !digestAllowed || planned.containsKey(path)) {
                        parseError = "Unsafe or inconsistent transaction plan";
                        continue;
                    }
                    planned.put(path, Entry.journal(path, delete, "pending", "-", parts[2]));
                } catch (IllegalArgumentException ignored) {
                    parseError = "Invalid transaction plan encoding";
                }
                continue;
            }
            if (section == 2) {
                if (line.isEmpty()) continue;
                String[] parts = line.split("\\t", -1);
                if (parts.length != 3) {
                    parseError = "Malformed transaction snapshot journal";
                    continue;
                }
                try {
                    String path = decode(parts[0]);
                    boolean oldStateAllowed = "present".equals(parts[1]) || "absent".equals(parts[1]);
                    boolean oldDigestAllowed = "absent".equals(parts[1]) ? "-".equals(parts[2]) : isDigest(parts[2]);
                    if (!planned.containsKey(path) || !oldStateAllowed || !oldDigestAllowed || snapshots.containsKey(path)) {
                        parseError = "Unsafe or inconsistent transaction snapshot journal";
                        continue;
                    }
                    snapshots.put(path, new String[]{parts[1], parts[2]});
                } catch (IllegalArgumentException ignored) {
                    parseError = "Invalid transaction snapshot encoding";
                }
                continue;
            }
            int equals = line.indexOf('=');
            if (equals > 0) fields.put(line.substring(0, equals).trim(), line.substring(equals + 1).trim());
        }

        List<Entry> entries = new ArrayList<>();
        for (Entry plan : planned.values()) {
            String[] old = snapshots.get(plan.path);
            entries.add(old == null ? plan : Entry.journal(plan.path, plan.delete, old[0], old[1], plan.newDigest));
        }
        int declaredCount = intValue(fields.get("entry_count"), entries.size());
        String id = fields.getOrDefault("id", "");
        String status = fields.getOrDefault("status", "none");
        if (!id.isEmpty() && !ManagedPathPolicy.safeInternalId(id, 80)) parseError = "Transaction status id is unsafe";
        if (!isKnownStatus(status)) parseError = "Unknown transaction status";
        if (declaredCount < 0 || declaredCount > MAX_ENTRIES) parseError = "Transaction plan entry count is outside bounds";
        if (!entries.isEmpty() && !XbsConfigApi.CONFIG.equals(entries.get(0).path)) {
            parseError = "Transaction plan does not start with the configuration file";
        }
        if (declaredCount != entries.size()) parseError = "Transaction plan entry count does not match status";
        return new Record(
            id, status,
            longValue(fields.get("created_at")), longValue(fields.get("updated_at")),
            Boolean.parseBoolean(fields.getOrDefault("reload", "false")),
            Boolean.parseBoolean(fields.getOrDefault("snapshots_complete", "false")),
            declaredCount, cleanMessage(fields.getOrDefault("message", "")),
            Collections.unmodifiableList(entries), parseError
        );
    }

    private static String buildRollbackShell(Record record) {
        String tx = TRANSACTION_DIR + "/" + record.id;
        String stage = tx + "/stage";
        String original = tx + "/original";
        String manifest = tx + "/manifest.tsv";
        String snapshots = tx + "/snapshots.tsv";
        String status = tx + "/status";
        StringBuilder shell = new StringBuilder(14_000);
        shell.append("set -eu; umask 077; ")
            .append(commonVariables(record.id, tx, stage, original, manifest, snapshots, status))
            .append("LOCKED=false; ROLLBACK_STARTED=false; RELOAD_STARTED=false; DONE=false; ALLOW_PENDING=false; ")
            .append(managedDirectoryFunctions()).append(hashFunction()).append(lockFunctions())
            .append(statusFunction(record.createdAt, record.reload, record.entries.size()))
            .append(latestGuardFunction(record.id))
            .append(restoreFunction(record.entries, original))
            .append(verifyOriginalFunction(record.entries, original))
            .append(reapplyCommittedFunction(record.entries, stage))
            .append(verifyCommittedFunction(record.entries, stage))
            .append("on_exit(){ RC=$1; trap - EXIT HUP INT TERM; "
                + "if [ \"$RELOAD_STARTED\" = true ] && [ \"$DONE\" != true ]; then write_status recovery_required 'Rollback reload outcome unknown; preserve both file sets' >/dev/null 2>&1 || true; release_lock; exit \"$RC\"; fi; "
                + "if [ \"$ROLLBACK_STARTED\" = true ] && [ \"$DONE\" != true ]; then ")
            .append("RESTORE_OK=true; reapply_committed >/dev/null 2>&1 || RESTORE_OK=false; verify_committed >/dev/null 2>&1 || RESTORE_OK=false; ");
        // File-restoration failure is recorded; no unaudited daemon reload is queued by the trap.
        shell.append("if [ \"$RESTORE_OK\" = true ]; then write_status rollback_failed 'Rollback failed; committed files were restored' >/dev/null 2>&1 || true; ")
            .append("else write_status rollback_failed 'Rollback failed and committed-state restoration could not be verified' >/dev/null 2>&1 || true; fi; fi; ")
            .append("release_lock; exit \"$RC\"; }; trap 'on_exit $?' EXIT; trap 'exit 130' HUP INT TERM; ")
            .append("assert_managed_dirs; acquire_lock; guard_latest; ACTUAL_STATUS=$(sed -n 's/^status=//p' \"$STATUS\" | head -n1); ")
            .append("if [ \"$ACTUAL_STATUS\" != committed ]; then printf 'Latest transaction is no longer committed\\n' >&2; exit 76; fi; ")
            .append("verify_committed || { printf 'Managed files changed after commit; refusing stale rollback\\n' >&2; exit 77; }; ")
            .append("ROLLBACK_STARTED=true; write_status rolling_back 'Restoring pre-transaction files'; restore_original; verify_original; ");
        if (record.reload) shell.append("RELOAD_STARTED=true; write_status reload_pending 'Awaiting original daemon generation'; ")
            .append(confirmedReload(originalConfigDigest(record))).append(" >/dev/null; ");
        shell.append("write_status rolled_back 'Latest configuration transaction rolled back by user'; DONE=true; release_lock; ")
            .append("trap - EXIT HUP INT TERM; printf 'transaction_id=%s\\nstatus=rolled_back\\n' \"$ID\";");
        return shell.toString();
    }

    private static String buildRecoveryShell(Record record) {
        String tx = TRANSACTION_DIR + "/" + record.id;
        String stage = tx + "/stage";
        String original = tx + "/original";
        String manifest = tx + "/manifest.tsv";
        String snapshots = tx + "/snapshots.tsv";
        String status = tx + "/status";
        StringBuilder shell = new StringBuilder(10_000);
        shell.append("set -eu; umask 077; ")
            .append(commonVariables(record.id, tx, stage, original, manifest, snapshots, status))
            .append("LOCKED=false; RECOVERY_STARTED=false; RELOAD_STARTED=false; DONE=false; ALLOW_PENDING=false; ")
            .append(managedDirectoryFunctions()).append(lockFunctions())
            .append(statusFunction(record.createdAt, record.reload, record.entries.size()))
            .append(latestGuardFunction(record.id))
            .append(restoreFunction(record.entries, original))
            .append(verifyOriginalFunction(record.entries, original))
            .append("on_exit(){ RC=$1; trap - EXIT HUP INT TERM; "
                + "if [ \"$RELOAD_STARTED\" = true ] && [ \"$DONE\" != true ]; then write_status recovery_required 'Recovery reload outcome unknown; manual reconciliation required' >/dev/null 2>&1 || true; release_lock; exit \"$RC\"; fi; "
                + "if [ \"$RECOVERY_STARTED\" = true ] && [ \"$DONE\" != true ]; then ")
            .append("write_status recovery_failed 'Recovery did not verify the complete old file set; retry is safe' >/dev/null 2>&1 || true; fi; ")
            .append("release_lock; exit \"$RC\"; }; trap 'on_exit $?' EXIT; trap 'exit 130' HUP INT TERM; ")
            .append("assert_managed_dirs; acquire_lock; guard_latest; ACTUAL_STATUS=$(sed -n 's/^status=//p' \"$STATUS\" | head -n1); ")
            .append("case \"$ACTUAL_STATUS\" in preparing|prepared|promoting|promoted|reload_pending|recovery_required|rolling_back|recovering|rollback_failed|recovery_failed) ;; ")
            .append("*) printf 'Latest transaction no longer requires recovery\\n' >&2; exit 76;; esac; ")
            .append("if [ -f \"$SNAPSHOTS.complete\" ]; then ALLOW_PENDING=false; else case \"$ACTUAL_STATUS\" in preparing|recovering|recovery_failed) ALLOW_PENDING=true;; *) printf 'Completed transaction is missing its snapshot-complete marker\\n' >&2; exit 78;; esac; fi; ")
            .append("RECOVERY_STARTED=true; write_status recovering 'Recovering interrupted configuration transaction'; restore_original; verify_original; ");
        if (record.reload) shell.append("RELOAD_STARTED=true; write_status reload_pending 'Awaiting recovered daemon generation'; ")
            .append(confirmedReload(originalConfigDigest(record))).append(" >/dev/null; ");
        shell.append("write_status recovered 'Interrupted configuration transaction recovered'; DONE=true; release_lock; ")
            .append("trap - EXIT HUP INT TERM; printf 'transaction_id=%s\\nstatus=recovered\\n' \"$ID\";");
        return shell.toString();
    }

    private static boolean isKnownStatus(String value) {
        if (value == null) return false;
        switch (value) {
            case "none":
            case "invalid":
            case "missing":
            case "unknown":
            case "unavailable":
            case "preparing":
            case "prepared":
            case "promoting":
            case "promoted":
            case "reload_pending":
            case "recovery_required":
            case "committed":
            case "rolling_back":
            case "rolled_back":
            case "recovering":
            case "recovered":
            case "rollback_failed":
            case "recovery_failed":
                return true;
            default:
                return false;
        }
    }

    private static String cleanMessage(String value) {
        String cleaned = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim();
        return cleaned.length() <= 512 ? cleaned : cleaned.substring(0, 511) + "…";
    }

    private static String latestGuardFunction(String id) {
        return "guard_latest(){ if [ -L " + XbsConfigApi.q(LATEST_FILE) + " ]; then printf 'Latest transaction pointer is unsafe\\n' >&2; return 73; fi; "
            + "CURRENT=$(cat " + XbsConfigApi.q(LATEST_FILE) + " 2>/dev/null || true); if [ \"$CURRENT\" != " + XbsConfigApi.q(id)
            + " ]; then printf 'Transaction is no longer the latest configuration save\\n' >&2; return 76; fi; "
            + "if [ -L \"$TX\" ] || [ ! -d \"$TX\" ] || [ -L \"$STATUS\" ] || [ ! -f \"$STATUS\" ] "
            + "|| [ -L \"$MANIFEST\" ] || [ ! -f \"$MANIFEST\" ] || [ -L \"$SNAPSHOTS\" ] || [ ! -f \"$SNAPSHOTS\" ] "
            + "|| [ -L \"$SNAPSHOTS.complete\" ] || [ -L \"$ORIGINAL\" ] || [ ! -d \"$ORIGINAL\" ] "
            + "|| [ -L \"$STAGE\" ] || [ ! -d \"$STAGE\" ]; then printf 'Transaction journal is incomplete or unsafe\\n' >&2; return 73; fi; }; ";
    }

    private static String reapplyCommittedFunction(List<Entry> entries, String stage) {
        StringBuilder shell = new StringBuilder("reapply_committed(){ assert_managed_dirs; ");
        for (int index = 0; index < entries.size(); index++) {
            Entry entry = entries.get(index);
            if (entry.delete) {
                shell.append("rm -f ").append(XbsConfigApi.q(entry.path)).append("; sync >/dev/null 2>&1 || true; ");
            } else {
                shell.append("test -f ").append(XbsConfigApi.q(stage + "/" + index)).append("; mkdir -p ")
                    .append(XbsConfigApi.q(parent(entry.path))).append("; cp -f ")
                    .append(XbsConfigApi.q(stage + "/" + index)).append(' ').append(XbsConfigApi.q(entry.path + ".xbsapp.reapply"))
                    .append("; mv -f ").append(XbsConfigApi.q(entry.path + ".xbsapp.reapply")).append(' ').append(XbsConfigApi.q(entry.path)).append("; sync >/dev/null 2>&1 || true; ");
            }
        }
        return shell.append("}; ").toString();
    }

    private static String verifyCommittedFunction(List<Entry> entries, String stage) {
        StringBuilder shell = new StringBuilder("verify_committed(){ ");
        for (int index = 0; index < entries.size(); index++) {
            Entry entry = entries.get(index);
            if (entry.delete) {
                shell.append("test ! -e ").append(XbsConfigApi.q(entry.path)).append(" || return 83; ");
            } else {
                shell.append("test -f ").append(XbsConfigApi.q(stage + "/" + index)).append(" || return 83; test -f ")
                    .append(XbsConfigApi.q(entry.path)).append(" || return 83; cmp -s ")
                    .append(XbsConfigApi.q(stage + "/" + index)).append(' ').append(XbsConfigApi.q(entry.path)).append(" || return 83; ");
            }
        }
        return shell.append("}; ").toString();
    }

    private static String xbs(String args) {
        return XbsRuntimeResolver.xbs(args);
    }

    private static String xbsctl(String args) {
        return XbsRuntimeResolver.xbsCtl(args);
    }

    private static String confirmedReload(String digest) {
        if (!isDigest(digest)) throw new IllegalArgumentException("Verified configuration generation is missing");
        return xbsctl("--json reload --wait --timeout 12 --expect-config-sha256 " + digest);
    }

    private static String originalConfigDigest(Record record) {
        if (record == null || record.entries.isEmpty()) throw new IllegalArgumentException("Original configuration generation is missing");
        Entry config = record.entries.get(0);
        if (!XbsConfigApi.CONFIG.equals(config.path) || !"present".equals(config.oldState) || !isDigest(config.oldDigest)) {
            throw new IllegalArgumentException("Original configuration generation is unavailable; manual reconciliation required");
        }
        return config.oldDigest;
    }

    private static String parent(String path) {
        int slash = path.lastIndexOf('/');
        return slash <= 0 ? "/" : path.substring(0, slash);
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte item : digest) out.append(String.format(Locale.ROOT, "%02x", item & 0xff));
            return out.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static boolean isDigest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static long longValue(String value) {
        try { return Long.parseLong(value == null ? "0" : value); } catch (NumberFormatException ignored) { return 0L; }
    }

    private static int intValue(String value, int fallback) {
        try { return Integer.parseInt(value == null ? "" : value); } catch (NumberFormatException ignored) { return fallback; }
    }

    static final class Entry {
        final String path;
        final String content;
        final boolean delete;
        final String oldState;
        final String oldDigest;
        final String newDigest;

        Entry(String path, String content, boolean delete, String oldState, String oldDigest, String newDigest) {
            this.path = path;
            this.content = content == null ? "" : content;
            this.delete = delete;
            this.oldState = oldState == null ? "unknown" : oldState;
            this.oldDigest = oldDigest == null ? "-" : oldDigest;
            this.newDigest = newDigest == null ? "-" : newDigest;
        }

        static Entry write(String path, String content) {
            String safeContent = content == null ? "" : content;
            return new Entry(path, safeContent, false, "unknown", "-", sha256(safeContent));
        }

        static Entry delete(String path) { return new Entry(path, "", true, "unknown", "-", "-"); }

        static Entry journal(String path, boolean delete, String oldState, String oldDigest, String newDigest) {
            return new Entry(path, "", delete, oldState, oldDigest, newDigest);
        }

        boolean hasPlanMetadata() {
            return (XbsConfigApi.CONFIG.equals(path) || isManagedListPath(path))
                && (delete ? "-".equals(newDigest) : isDigest(newDigest));
        }

        boolean hasJournalMetadata() {
            return hasPlanMetadata() && ("present".equals(oldState) || "absent".equals(oldState))
                && ("absent".equals(oldState) ? "-".equals(oldDigest) : isDigest(oldDigest));
        }

        @Override public String toString() { return (delete ? "D:" : "W:") + path + ":" + newDigest; }
    }

    static final class Plan {
        final String id;
        final long createdAt;
        final boolean reload;
        final List<Entry> entries;

        Plan(String id, long createdAt, boolean reload, List<Entry> entries) {
            this.id = id;
            this.createdAt = createdAt;
            this.reload = reload;
            this.entries = entries;
        }
    }

    static final class Record {
        final String id;
        final String status;
        final long createdAt;
        final long updatedAt;
        final boolean reload;
        final boolean snapshotsComplete;
        final int entryCount;
        final String message;
        final List<Entry> entries;
        final String error;

        Record(String id, String status, long createdAt, long updatedAt, boolean reload, boolean snapshotsComplete,
               int entryCount, String message, List<Entry> entries, String error) {
            this.id = id == null ? "" : id;
            this.status = status == null ? "unknown" : status;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.reload = reload;
            this.snapshotsComplete = snapshotsComplete;
            this.entryCount = entryCount;
            this.message = message == null ? "" : message;
            this.entries = entries == null ? Collections.emptyList() : entries;
            this.error = error == null ? "" : error;
        }

        static Record committed(Plan plan) {
            return new Record(plan.id, "committed", plan.createdAt, System.currentTimeMillis(), plan.reload, true,
                plan.entries.size(), "Configuration transaction committed", plan.entries, "");
        }

        static Record unavailable(String error) {
            return new Record("", "unavailable", 0L, 0L, false, false, 0,
                "Could not read configuration journal", Collections.emptyList(), error);
        }

        boolean exists() { return !id.isEmpty() && !"none".equals(status) && !"unavailable".equals(status); }

        boolean canRollback() {
            return exists() && error.isEmpty() && "committed".equals(status) && snapshotsComplete
                && entries.size() == entryCount && !entries.isEmpty() && allEntriesJournaled();
        }

        boolean needsRecovery() {
            if (!exists() || !error.isEmpty() || entries.size() != entryCount || entries.isEmpty() || !allEntriesPlanned()) return false;
            boolean recoverable = "preparing".equals(status) || "prepared".equals(status) || "promoting".equals(status)
                || "promoted".equals(status) || "reload_pending".equals(status) || "recovery_required".equals(status)
                || "rolling_back".equals(status) || "recovering".equals(status)
                || "rollback_failed".equals(status) || "recovery_failed".equals(status);
            if (!recoverable) return false;
            if (snapshotsComplete) return allEntriesJournaled();
            return "preparing".equals(status) || "recovering".equals(status) || "recovery_failed".equals(status);
        }

        private boolean allEntriesPlanned() {
            for (Entry entry : entries) if (!entry.hasPlanMetadata()) return false;
            return true;
        }

        private boolean allEntriesJournaled() {
            for (Entry entry : entries) if (!entry.hasJournalMetadata()) return false;
            return true;
        }

        String displayStatus() { return status.replace('_', ' ').toUpperCase(Locale.ROOT); }

        String supportText() {
            StringBuilder out = new StringBuilder();
            out.append("status=").append(status).append('\n');
            if (!id.isEmpty()) out.append("id=").append(id).append('\n');
            out.append("entries=").append(entryCount).append(" reload=").append(reload)
                .append(" snapshots_complete=").append(snapshotsComplete).append('\n');
            if (createdAt > 0) out.append("created_at=").append(createdAt).append('\n');
            if (updatedAt > 0) out.append("updated_at=").append(updatedAt).append('\n');
            if (!message.isEmpty()) out.append("message=").append(cleanLine(message)).append('\n');
            if (!error.isEmpty()) out.append("error=").append(cleanLine(error)).append('\n');
            return out.toString();
        }

        private static String cleanLine(String value) { return value.replace('\n', ' ').replace('\r', ' '); }
    }
}
