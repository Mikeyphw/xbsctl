package io.github.mikeceead.xbscompanion;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** Central outbound/inbound intent seal. Never forwards nested intents, URIs, selectors, clips, or grants. */
final class IntentSecurityPolicy {
    private static final int URI_GRANTS = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;
    static final int MAX_SHARE_CHARS = 180_000;
    private IntentSecurityPolicy() {}

    static Intent textShare(String subject, String text) {
        String safeSubject = bounded(subject, 120);
        String safeText = bounded(text, MAX_SHARE_CHARS);
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, safeSubject).putExtra(Intent.EXTRA_TEXT, safeText);
        return stripCapabilities(send);
    }

    static Intent stripCapabilities(Intent intent) {
        if (intent == null) return null;
        intent.setData(null); intent.setClipData(null); intent.setSelector(null);
        intent.setFlags(intent.getFlags() & ~URI_GRANTS);
        return intent;
    }

    static String internalId(Intent intent, String key, int max) {
        if (intent == null || key == null) return "";
        Object raw = intent.getExtras() == null ? null : intent.getExtras().get(key);
        if (!(raw instanceof String)) return "";
        String value = ((String)raw).trim();
        return ManagedPathPolicy.safeInternalId(value, max) ? value : "";
    }

    static int page(Intent intent, String key, int fallback, int count) {
        if (intent == null || count <= 0 || intent.getExtras() == null) return fallback;
        Object raw = intent.getExtras().get(key);
        if (!(raw instanceof Integer)) return fallback;
        int value = (Integer)raw;
        return value >= 0 && value < count ? value : fallback;
    }

    static boolean noNestedCapabilities(Intent intent) {
        if (intent == null || intent.getData() != null || intent.getClipData() != null || intent.getSelector() != null) return false;
        if ((intent.getFlags() & URI_GRANTS) != 0) return false;
        Bundle extras = intent.getExtras();
        if (extras == null) return true;
        for (String key : extras.keySet()) {
            Object value = extras.get(key);
            if (value instanceof Intent || value instanceof android.net.Uri) return false;
        }
        return true;
    }

    private static String bounded(String value, int max) {
        String out = value == null ? "" : value;
        return out.length() <= max ? out : out.substring(0, max);
    }
}
