package io.github.mikeceead.xbscompanion;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Immutable destination in the searchable More and Settings information architecture. */
final class SettingsDestination {
    static final int MAX_QUERY_CHARS = 160;
    static final int MAX_QUERY_TOKENS = 12;
    static final String QUERY_OVERSIZED = "__xbs_settings_query_oversized__";

    static final String RUNTIME = "runtime";
    static final String NOTIFICATIONS = "notifications";
    static final String APPEARANCE = "appearance";
    static final String TASKER = "tasker";
    static final String DATA = "data";
    static final String CONFIGURATION = "configuration";
    static final String DIAGNOSTICS = "diagnostics";
    static final String ABOUT = "about";

    final String id;
    final String title;
    final String description;
    final List<String> keywords;

    SettingsDestination(String id, String title, String description, String... keywords) {
        this.id = safe(id);
        this.title = safe(title);
        this.description = safe(description);
        this.keywords = Collections.unmodifiableList(Arrays.asList(keywords == null ? new String[0] : keywords.clone()));
    }

    boolean matches(String query) {
        String[] tokens = tokens(query);
        if (tokens.length == 0) return true;
        if (tokens.length == 1 && QUERY_OVERSIZED.equals(tokens[0])) return false;
        StringBuilder searchable = new StringBuilder();
        searchable.append(id).append(' ').append(title).append(' ').append(description);
        for (String keyword : keywords) searchable.append(' ').append(keyword);
        String haystack = normalize(searchable.toString());
        for (String token : tokens) {
            if (!token.isEmpty() && !haystack.contains(token)) return false;
        }
        return true;
    }

    static String boundedQuery(String query) {
        String clean = safe(query).replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').replaceAll(" +", " ");
        return clean.length() <= MAX_QUERY_CHARS ? clean : clean.substring(0, MAX_QUERY_CHARS);
    }

    static String[] tokens(String query) {
        String clean = safe(query);
        if (clean.length() > MAX_QUERY_CHARS) return new String[]{QUERY_OVERSIZED};
        String normalized = normalize(clean);
        if (normalized.isEmpty()) return new String[0];
        String[] raw = normalized.split("\\s+");
        if (raw.length > MAX_QUERY_TOKENS) return new String[]{QUERY_OVERSIZED};
        return raw;
    }

    private static String safe(String value) { return value == null ? "" : value.trim(); }
    private static String normalize(String value) { return safe(value).toLowerCase(Locale.ROOT); }
}
