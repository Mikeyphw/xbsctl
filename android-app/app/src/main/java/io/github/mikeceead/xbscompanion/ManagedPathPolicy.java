package io.github.mikeceead.xbscompanion;

import java.util.Locale;

/** Strict lexical policy for every root-managed XBS path accepted from Android UI state. */
final class ManagedPathPolicy {
    static final int VERSION = 2;
    private static final int MAX_PATH = 512;
    private static final int MAX_LEAF = 180;
    private ManagedPathPolicy() {}

    static boolean isDirectChild(String root, String path) {
        String base = clean(root);
        String value = clean(path);
        if (base.isEmpty() || value.isEmpty() || value.length() > MAX_PATH) return false;
        if (!base.startsWith("/") || base.endsWith("/") || unsafe(value)) return false;
        String prefix = base + "/";
        if (!value.startsWith(prefix) || value.indexOf('/', prefix.length()) >= 0) return false;
        String leaf = value.substring(prefix.length());
        return safeLeaf(leaf) && value.equals(prefix + leaf);
    }

    static boolean safeLeaf(String leaf) {
        if (leaf == null || leaf.isEmpty() || leaf.length() > MAX_LEAF) return false;
        if (leaf.charAt(0) == '.' || ".".equals(leaf) || "..".equals(leaf)) return false;
        return leaf.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,179}");
    }

    static String safeLeafFrom(String value, String fallback) {
        String raw = clean(value).replaceAll("[^A-Za-z0-9_.-]", "_");
        while (raw.startsWith(".")) raw = raw.substring(1);
        if (raw.length() > MAX_LEAF) raw = raw.substring(0, MAX_LEAF);
        if (!safeLeaf(raw)) raw = clean(fallback);
        return safeLeaf(raw) ? raw : "xbs_item";
    }

    static boolean safeInternalId(String value, int max) {
        String item = clean(value);
        return !item.isEmpty() && item.length() <= Math.max(1, max)
            && item.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*");
    }

    private static boolean unsafe(String value) {
        if (value.contains("\\") || value.contains("%") || value.contains("//")
            || value.contains("/./") || value.contains("/../") || value.endsWith("/.") || value.endsWith("/..")) return true;
        for (int i=0;i<value.length();i++) if (Character.isISOControl(value.charAt(i))) return true;
        return false;
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
