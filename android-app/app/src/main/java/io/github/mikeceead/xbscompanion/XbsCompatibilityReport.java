package io.github.mikeceead.xbscompanion;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable compatibility evidence published by the installed XBS module. */
final class XbsCompatibilityReport {
    static final int APP_CONTRACT_VERSION = 1;
    static final int APP_CONFIG_VERSION = 2;
    static final long STALE_AFTER_MS = 6 * 60 * 60_000L;
    static final String SOURCE_LIVE = "live";
    static final String SOURCE_FALLBACK = "fallback";
    static final String SOURCE_UNAVAILABLE = "unavailable";
    static final String EXPECTED_CONFIG_SCHEMA_DIGEST = "237caba0ac9dfd8e9ecd61ea97bcad375a3ce1e7a6fbbc6d77082c78a605f318";
    static final String EXPECTED_COMMAND_SCHEMA_DIGEST = "c38faee1997d7c1d9f382dd572bc44c494aab675fc523e7577dcf0eecba54d0b";
    static final int MAX_RAW_CHARS = 96 * 1024;
    static final int MAX_COMMAND_IDS = 96;
    static final int MAX_EVENT_FIELDS = 96;
    static final int MAX_ID_CHARS = 96;
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,95}$");

    final long capturedAt;
    final String source;
    final String contractVersion;
    final String moduleId;
    final String moduleVersion;
    final long moduleVersionCode;
    final String cliVersion;
    final String commandSchemaVersion;
    final int minimumAndroidContract;
    final int maximumAndroidContract;
    final long minimumAppVersionCode;
    final int configVersion;
    final int maximumConfigVersion;
    final String configSchemaDigest;
    final String contractDigest;
    final Set<String> commandIds;
    final Set<String> eventFields;
    final String runtimeKind;
    final boolean pythonRequired;
    final boolean yqRequired;
    final boolean bashRequired;
    final boolean bcRequired;
    final String error;

    XbsCompatibilityReport(long capturedAt, String source, String contractVersion,
                           String moduleId, String moduleVersion, long moduleVersionCode,
                           String cliVersion, String commandSchemaVersion,
                           int minimumAndroidContract, int maximumAndroidContract,
                           long minimumAppVersionCode, int configVersion, int maximumConfigVersion,
                           String configSchemaDigest, String contractDigest,
                           Set<String> commandIds, Set<String> eventFields,
                           String runtimeKind, boolean pythonRequired, boolean yqRequired,
                           boolean bashRequired, boolean bcRequired, String error) {
        this.capturedAt = Math.max(0L, capturedAt);
        this.source = clean(source);
        this.contractVersion = clean(contractVersion);
        this.moduleId = clean(moduleId);
        this.moduleVersion = clean(moduleVersion);
        this.moduleVersionCode = Math.max(0L, moduleVersionCode);
        this.cliVersion = clean(cliVersion);
        this.commandSchemaVersion = clean(commandSchemaVersion);
        this.minimumAndroidContract = Math.max(0, minimumAndroidContract);
        this.maximumAndroidContract = Math.max(0, maximumAndroidContract);
        this.minimumAppVersionCode = Math.max(0L, minimumAppVersionCode);
        this.configVersion = Math.max(0, configVersion);
        this.maximumConfigVersion = Math.max(0, maximumConfigVersion);
        this.configSchemaDigest = cleanDigest(configSchemaDigest);
        this.contractDigest = cleanDigest(contractDigest);
        this.commandIds = immutable(commandIds);
        this.eventFields = immutable(eventFields);
        this.runtimeKind = clean(runtimeKind).isEmpty() ? "unknown" : clean(runtimeKind);
        this.pythonRequired = pythonRequired;
        this.yqRequired = yqRequired;
        this.bashRequired = bashRequired;
        this.bcRequired = bcRequired;
        this.error = clean(error);
    }

    static XbsCompatibilityReport unavailable(String error) {
        return new XbsCompatibilityReport(0L, SOURCE_UNAVAILABLE, "", "XtremeBS", "unknown", 0L,
            "", "", 0, 0, 0L, 0, APP_CONFIG_VERSION, "", "",
            Collections.emptySet(), Collections.emptySet(), "unknown", false, false, false, false, error);
    }

    static XbsCompatibilityReport fallback(long now, String reason) {
        LinkedHashSet<String> commands = new LinkedHashSet<>();
        for (CliCommand command : XbsCliCatalog.all()) if (!command.isNative()) commands.add(command.id);
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        Collections.addAll(fields, "allowlist", "denylist", "proc_file", "keep_on_charge", "quit",
            "handle_cores", "disable_cores", "handle_apps", "handle_gms", "handle_proc",
            "low_ram", "doze", "kill_wifi");
        return new XbsCompatibilityReport(now, SOURCE_FALLBACK, "offline-static", "XtremeBS", "unknown", 0L,
            "", "offline-static", 1, 1, 1L, APP_CONFIG_VERSION, APP_CONFIG_VERSION, "", "",
            commands, fields, "offline-static", false, false, false, false, reason);
    }

    static XbsCompatibilityReport parse(String raw, long capturedAt) {
        String payload = raw == null ? "" : raw.trim();
        if (payload.isEmpty()) return unavailable("Compatibility payload is empty");
        if (payload.length() > MAX_RAW_CHARS) return unavailable("Compatibility payload exceeds bounded parser limit");
        try {
            JSONObject root = new JSONObject(payload);
            JSONObject data = root.optJSONObject("data");
            JSONObject compatibility = data == null ? null : data.optJSONObject("compatibility");
            if (compatibility == null) return unavailable("Compatibility payload is missing data.compatibility");
            JSONObject module = compatibility.optJSONObject("module");
            JSONObject cli = compatibility.optJSONObject("cli");
            JSONObject android = compatibility.optJSONObject("android_contract");
            JSONObject config = compatibility.optJSONObject("config");
            JSONObject runtime = compatibility.optJSONObject("runtime");
            JSONArray commandValues = compatibility.optJSONArray("commands");
            JSONArray fieldValues = config == null ? null : config.optJSONArray("event_fields");
            ensureArrayBound(commandValues, MAX_COMMAND_IDS, "command array");
            ensureArrayBound(fieldValues, MAX_EVENT_FIELDS, "event-field array");
            Set<String> commands = parseCommands(commandValues);
            Set<String> fields = parseStrings(fieldValues, MAX_EVENT_FIELDS);
            return new XbsCompatibilityReport(
                capturedAt, SOURCE_LIVE, stringValue(compatibility, "contract_version", ""),
                module == null ? "XtremeBS" : stringValue(module, "id", "XtremeBS"),
                module == null ? "unknown" : stringValue(module, "version", "unknown"),
                module == null ? 0L : module.optLong("version_code", 0L),
                cli == null ? "" : stringValue(cli, "version", ""),
                cli == null ? "" : stringValue(cli, "command_schema_version", ""),
                android == null ? 0 : android.optInt("minimum", 0),
                android == null ? 0 : android.optInt("maximum", 0),
                android == null ? 0L : android.optLong("minimum_app_version_code", 0L),
                config == null ? 0 : config.optInt("active_version", 0),
                config == null ? 0 : config.optInt("maximum_supported_version", 0),
                config == null ? "" : stringValue(config, "schema_digest", ""),
                stringValue(compatibility, "digest", ""), commands, fields,
                runtime == null ? "unknown" : stringValue(runtime, "kind", "unknown"),
                runtime != null && runtime.optBoolean("python_required", false),
                runtime != null && runtime.optBoolean("yq_required", false),
                runtime != null && runtime.optBoolean("bash_required", false),
                runtime != null && runtime.optBoolean("bc_required", false), "");
        } catch (Exception exception) {
            return unavailable("Compatibility payload could not be parsed: " + clean(exception.getMessage()));
        }
    }

    boolean isLive() { return SOURCE_LIVE.equals(source); }
    boolean isFallback() { return SOURCE_FALLBACK.equals(source); }
    boolean isFresh(long now) { return capturedAt > 0L && now >= capturedAt && now - capturedAt <= STALE_AFTER_MS; }
    boolean contractSupported() {
        return minimumAndroidContract <= APP_CONTRACT_VERSION
            && (maximumAndroidContract == 0 || maximumAndroidContract >= APP_CONTRACT_VERSION);
    }
    boolean appVersionSupported() { return minimumAppVersionCode <= BuildConfig.VERSION_CODE; }
    boolean configSupported() {
        return configVersion > 0 && configVersion <= APP_CONFIG_VERSION
            && (maximumConfigVersion == 0 || configVersion <= maximumConfigVersion);
    }
    boolean runtimeIsGoNative() { return "go-native".equals(runtimeKind); }
    boolean runtimeDependencyFree() { return !pythonRequired && !yqRequired && !bashRequired && !bcRequired; }
    boolean compatible() { return isLive() && contractSupported() && appVersionSupported() && configSupported() && !commandIds.isEmpty() && runtimeDependencyFree(); }
    boolean configSchemaMatchesApp() { return EXPECTED_CONFIG_SCHEMA_DIGEST.equals(configSchemaDigest); }
    boolean commandSchemaMatchesApp() { return EXPECTED_COMMAND_SCHEMA_DIGEST.equals(contractDigest); }
    boolean schemaDigestsMatchApp() { return configSchemaMatchesApp() && commandSchemaMatchesApp(); }
    boolean supportsCommand(String id) { return id != null && commandIds.contains(id); }
    boolean supportsCommand(CliCommand command) { return command != null && (command.isNative() || supportsCommand(command.id)); }
    boolean supportsEventField(String field) { return field != null && eventFields.contains(field); }

    String headline() {
        if (isLive() && compatible() && schemaDigestsMatchApp()) return "Live module contract compatible";
        if (isLive() && compatible()) return "Live contract compatible with schema drift";
        if (isLive() && !contractSupported()) return "Android contract version is incompatible";
        if (isLive() && !appVersionSupported()) return "XBS Companion is too old for this module contract";
        if (isLive() && !configSupported()) return "Configuration version is not supported";
        if (isFallback()) return "Static offline compatibility fallback";
        return "Compatibility evidence unavailable";
    }

    String compactSummary() {
        return "source=" + source + " contract=" + value(contractVersion) + " module=" + value(moduleVersion)
            + " cli=" + value(cliVersion) + " runtime=" + value(runtimeKind) + " config=" + configVersion + " commands=" + commandIds.size()
            + " fields=" + eventFields.size();
    }

    String supportText() {
        StringBuilder out = new StringBuilder(768);
        out.append("captured_at=").append(capturedAt).append(" source=").append(source)
            .append(" fresh=").append(isFresh(System.currentTimeMillis())).append('\n');
        out.append("headline=").append(headline()).append('\n');
        out.append("contract_version=").append(contractVersion)
            .append(" android_min=").append(minimumAndroidContract)
            .append(" android_max=").append(maximumAndroidContract)
            .append(" minimum_app_version_code=").append(minimumAppVersionCode).append('\n');
        out.append("module_id=").append(moduleId).append(" module_version=").append(moduleVersion)
            .append(" module_version_code=").append(moduleVersionCode)
            .append(" cli_version=").append(cliVersion)
            .append(" command_schema_version=").append(commandSchemaVersion).append('\n');
        out.append("config_version=").append(configVersion)
            .append(" maximum_config_version=").append(maximumConfigVersion)
            .append(" config_schema_digest=").append(configSchemaDigest).append('\n');
        out.append("contract_digest=").append(contractDigest)
            .append(" config_digest_matches_app=").append(configSchemaMatchesApp())
            .append(" command_digest_matches_app=").append(commandSchemaMatchesApp())
            .append(" commands=").append(commandIds.size())
            .append(" event_fields=").append(eventFields.size()).append('\n');
        if (!error.isEmpty()) out.append("error=").append(error).append('\n');
        return out.toString();
    }

    JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("capturedAt", capturedAt);
            json.put("source", source);
            json.put("contractVersion", contractVersion);
            json.put("moduleId", moduleId);
            json.put("moduleVersion", moduleVersion);
            json.put("moduleVersionCode", moduleVersionCode);
            json.put("cliVersion", cliVersion);
            json.put("commandSchemaVersion", commandSchemaVersion);
            json.put("minimumAndroidContract", minimumAndroidContract);
            json.put("maximumAndroidContract", maximumAndroidContract);
            json.put("minimumAppVersionCode", minimumAppVersionCode);
            json.put("configVersion", configVersion);
            json.put("maximumConfigVersion", maximumConfigVersion);
            json.put("configSchemaDigest", configSchemaDigest);
            json.put("contractDigest", contractDigest);
            json.put("commandIds", new JSONArray(commandIds));
            json.put("eventFields", new JSONArray(eventFields));
            json.put("runtimeKind", runtimeKind);
            json.put("pythonRequired", pythonRequired);
            json.put("yqRequired", yqRequired);
            json.put("bashRequired", bashRequired);
            json.put("bcRequired", bcRequired);
            json.put("error", error);
        } catch (Exception ignored) {}
        return json;
    }

    static XbsCompatibilityReport fromJson(JSONObject json) {
        if (json == null) return unavailable("Compatibility cache is empty");
        JSONArray commandValues = json.optJSONArray("commandIds");
        JSONArray fieldValues = json.optJSONArray("eventFields");
        if (arrayTooLarge(commandValues, MAX_COMMAND_IDS) || arrayTooLarge(fieldValues, MAX_EVENT_FIELDS)) {
            return unavailable("Compatibility cache identifier array exceeds bounded parser limit");
        }
        return new XbsCompatibilityReport(
            json.optLong("capturedAt", 0L), stringValue(json, "source", SOURCE_UNAVAILABLE),
            stringValue(json, "contractVersion", ""), stringValue(json, "moduleId", "XtremeBS"),
            stringValue(json, "moduleVersion", "unknown"), json.optLong("moduleVersionCode", 0L),
            stringValue(json, "cliVersion", ""), stringValue(json, "commandSchemaVersion", ""),
            json.optInt("minimumAndroidContract", 0), json.optInt("maximumAndroidContract", 0),
            json.optLong("minimumAppVersionCode", 0L), json.optInt("configVersion", 0),
            json.optInt("maximumConfigVersion", 0), stringValue(json, "configSchemaDigest", ""),
            stringValue(json, "contractDigest", ""), parseStrings(commandValues, MAX_COMMAND_IDS),
            parseStrings(fieldValues, MAX_EVENT_FIELDS), stringValue(json, "runtimeKind", "unknown"),
            json.optBoolean("pythonRequired", false), json.optBoolean("yqRequired", false),
            json.optBoolean("bashRequired", false), json.optBoolean("bcRequired", false),
            stringValue(json, "error", ""));
    }


    private static void ensureArrayBound(JSONArray values, int maximum, String label) {
        if (arrayTooLarge(values, maximum)) throw new IllegalArgumentException(
            "Compatibility " + label + " exceeds bounded parser limit");
    }

    private static boolean arrayTooLarge(JSONArray values, int maximum) {
        return values != null && values.length() > maximum;
    }

    private static Set<String> parseCommands(JSONArray values) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (values == null) return out;
        for (int i = 0; i < values.length() && out.size() < MAX_COMMAND_IDS; i++) {
            Object value = values.opt(i);
            if (value instanceof JSONObject) add(out, stringValue((JSONObject) value, "id", ""));
            else if (value instanceof String) add(out, String.valueOf(value));
        }
        return out;
    }

    private static Set<String> parseStrings(JSONArray values, int maximum) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (values == null) return out;
        for (int i = 0; i < values.length() && out.size() < maximum; i++) {
            Object value = values.opt(i);
            if (value instanceof String) add(out, String.valueOf(value));
        }
        return out;
    }

    private static void add(Set<String> out, String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() <= MAX_ID_CHARS && SAFE_ID.matcher(normalized).matches()) out.add(normalized);
    }

    private static String stringValue(JSONObject json, String key, String fallback) {
        if (json == null || key == null) return fallback;
        Object value = json.opt(key);
        return value instanceof String ? (String) value : fallback;
    }
    private static Set<String> immutable(Set<String> source) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(source == null ? Collections.emptySet() : source));
    }
    private static String cleanDigest(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.US);
        return normalized.matches("[0-9a-f]{64}") ? normalized : "";
    }
    private static String clean(String value) {
        String normalized = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim().replaceAll(" +", " ");
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 239) + "…";
    }
    private static String value(String value) { return value == null || value.isEmpty() ? "unknown" : value; }
}
