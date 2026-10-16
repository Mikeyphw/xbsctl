package io.github.mikeceead.xbscompanion;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic, bounded support-data redaction with correlation-preserving pseudonyms. */
final class SupportRedactor {
    static final int POLICY_VERSION = 2;
    static final int MAX_INPUT_CHARS = 256_000;

    /** Redact every Authorization value, regardless of authentication scheme. */
    private static final Pattern AUTHORIZATION = Pattern.compile(
        "(?im)([\"']?\\bauthorization\\b[\"']?\\s*[:=]\\s*)(?:\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'|[^\\r\\n,;]+)"
    );

    /** Redact complete quoted values, including spaces, and complete unquoted values to a safe delimiter. */
    private static final Pattern SECRET = Pattern.compile(
        "(?im)([\"']?(?:client[_-]?secret|access[_-]?token|refresh[_-]?token|auth[_-]?token|api[_-]?key|access[_-]?key|private[_-]?key|credential|token|password|passwd|secret)[\"']?"
            + "\\s*[:=]\\s*)(?:\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'|[^\\r\\n,;]+)"
    );

    private static final String PRIVATE_PATH_ROOT =
        "(?:/data/local/tmp/XtremeBS"
            + "|/data/adb/modules(?:_update)?/[^/\\s\"'<>]+"
            + "|/data/(?:data|user/[0-9]+)/[^/\\s\"'<>]+"
            + "|/storage/emulated/[0-9]+"
            + "|/sdcard"
            + "|/home/[^/\\s\"'<>]+)";

    private static final Pattern PRIVATE_PATH = Pattern.compile(
        "(?i)(?:\"" + PRIVATE_PATH_ROOT + "[^\"\\r\\n]*\""
            + "|'" + PRIVATE_PATH_ROOT + "[^'\\r\\n]*'"
            + "|" + PRIVATE_PATH_ROOT + "(?:/[^\\s\"'<>]*)?)"
    );

    private static final Pattern EMAIL = Pattern.compile("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");
    private static final Pattern IPV4 = Pattern.compile("(?<![0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9])");
    private static final Pattern IPV6_CANDIDATE = Pattern.compile(
        "(?i)(?<![0-9a-f:])(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}(?:%[a-z0-9_.-]+)?(?![0-9a-f:])"
    );

    /** Android package-like names with at least two components. */
    private static final Pattern PACKAGE = Pattern.compile("\\b(?:[a-zA-Z][a-zA-Z0-9_]*\\.)+[a-zA-Z][a-zA-Z0-9_]*\\b");

    /** Any canonical UUID version, including UUIDv6/v7/v8, plus long opaque hexadecimal identifiers. */
    private static final Pattern UUID = Pattern.compile(
        "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b|\\b[0-9a-f]{32,64}\\b"
    );

    static final class Result {
        final String text;
        final int secrets;
        final int paths;
        final int packages;
        final int addresses;
        final int identifiers;
        final boolean truncated;

        Result(String text, int secrets, int paths, int packages, int addresses, int identifiers, boolean truncated) {
            this.text = text == null ? "" : text;
            this.secrets = secrets;
            this.paths = paths;
            this.packages = packages;
            this.addresses = addresses;
            this.identifiers = identifiers;
            this.truncated = truncated;
        }

        String evidence() {
            return "policy=" + POLICY_VERSION + " secrets=" + secrets + " paths=" + paths
                + " packages=" + packages + " addresses=" + addresses
                + " identifiers=" + identifiers + " truncated=" + truncated;
        }
    }

    private SupportRedactor() {}

    static Result redact(String source, String ownPackage) {
        String input = source == null ? "" : source;
        boolean truncated = input.length() > MAX_INPUT_CHARS;
        if (truncated) input = input.substring(0, MAX_INPUT_CHARS) + "\n... support input truncated before redaction\n";
        Counter c = new Counter();
        String text = replaceSecretValues(input, AUTHORIZATION, c);
        text = replaceSecretValues(text, SECRET, c);
        text = pseudonymize(text, PRIVATE_PATH, "private-path", c, Kind.PATH, value -> true);
        text = pseudonymize(text, EMAIL, "email", c, Kind.ADDRESS, value -> true);
        text = pseudonymize(text, IPV4, "ip", c, Kind.ADDRESS,
            value -> !value.startsWith("127.") && !"0.0.0.0".equals(value));
        text = pseudonymize(text, IPV6_CANDIDATE, "ip6", c, Kind.ADDRESS, SupportRedactor::isPublicIpv6Candidate);
        final String app = ownPackage == null ? "" : ownPackage.trim();
        text = pseudonymize(text, PACKAGE, "package", c, Kind.PACKAGE,
            value -> !allowedPackage(value, app));
        text = pseudonymize(text, UUID, "id", c, Kind.IDENTIFIER, value -> true);
        return new Result(text, c.secrets, c.paths, c.packages, c.addresses, c.identifiers, truncated);
    }

    private static String replaceSecretValues(String input, Pattern pattern, Counter c) {
        Matcher matcher = pattern.matcher(input);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            c.secrets++;
            matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group(1) + "<redacted-secret>"));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private interface Predicate { boolean redact(String value); }

    private static String pseudonymize(String input, Pattern pattern, String label, Counter c, Kind kind, Predicate predicate) {
        Matcher matcher = pattern.matcher(input);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String value = matcher.group();
            if (!predicate.redact(value)) {
                matcher.appendReplacement(out, Matcher.quoteReplacement(value));
                continue;
            }
            increment(c, kind);
            matcher.appendReplacement(out, Matcher.quoteReplacement("<" + label + ":" + digest(value) + ">"));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static boolean isPublicIpv6Candidate(String value) {
        try {
            String address = value;
            int zone = address.indexOf('%');
            if (zone >= 0) address = address.substring(0, zone);
            InetAddress parsed = InetAddress.getByName(address);
            if (!(parsed instanceof Inet6Address)) return false;
            return !parsed.isLoopbackAddress() && !parsed.isAnyLocalAddress();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean allowedPackage(String value, String ownPackage) {
        String lower = value.toLowerCase(Locale.US);
        return value.equals(ownPackage) || lower.startsWith("android.") || lower.startsWith("com.android.")
            || lower.startsWith("java.") || lower.startsWith("javax.") || lower.startsWith("org.json.")
            || lower.startsWith("androidx.") || lower.startsWith("io.github.mikeceead.xbscompanion");
    }

    private static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(8);
            for (int i = 0; i < 4; i++) out.append(String.format(Locale.US, "%02x", hash[i]));
            return out.toString();
        } catch (Exception ignored) { return "unknown"; }
    }

    private static void increment(Counter c, Kind kind) {
        if (kind == Kind.PATH) c.paths++;
        else if (kind == Kind.PACKAGE) c.packages++;
        else if (kind == Kind.ADDRESS) c.addresses++;
        else if (kind == Kind.IDENTIFIER) c.identifiers++;
    }

    private enum Kind { PATH, PACKAGE, ADDRESS, IDENTIFIER }
    private static final class Counter { int secrets; int paths; int packages; int addresses; int identifiers; }
}
