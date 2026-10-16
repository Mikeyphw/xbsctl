package io.github.mikeceead.xbscompanion;

import static org.junit.Assert.*;
import org.junit.Test;

public final class SupportRedactorTest {
    @Test public void redactsSecretsPathsPackagesAddressesAndIdentifiers() {
        String raw = "token=abc123; password: hunter2; Authorization: Bearer xyz\n"
            + "/data/data/com.example.secret/files/account/private-name.txt /sdcard/Download/Mike-tax-return.pdf /data/adb/modules/XtremeBS/private.log\n"
            + "target=com.example.bank email=a@example.com ip=192.168.1.9 ipv6=2001:db8::1234 id=123e4567-e89b-72d3-a456-426614174000\n"
            + "package=io.github.mikeceead.xbscompanion";
        SupportRedactor.Result result = SupportRedactor.redact(raw, "io.github.mikeceead.xbscompanion");
        assertFalse(result.text.contains("abc123"));
        assertFalse(result.text.contains("hunter2"));
        assertFalse(result.text.contains("xyz"));
        assertFalse(result.text.contains("com.example.bank"));
        assertFalse(result.text.contains("a@example.com"));
        assertFalse(result.text.contains("192.168.1.9"));
        assertFalse(result.text.contains("2001:db8::1234"));
        assertFalse(result.text.contains("123e4567-e89b-72d3-a456-426614174000"));
        assertFalse(result.text.contains("private-name.txt"));
        assertFalse(result.text.contains("Mike-tax-return.pdf"));
        assertTrue(result.text.contains("<private-path:"));
        assertTrue(result.text.contains("io.github.mikeceead.xbscompanion"));
        assertTrue(result.secrets >= 3);
        assertTrue(result.paths >= 3);
    }

    @Test public void redactsEveryAuthorizationSchemeAndQuotedMultiwordSecrets() {
        String raw = "Authorization: Basic dXNlcjpwYXNz\n"
            + "authorization=ApiKey supersecret\n"
            + "client_secret = \"secret with spaces\"\n"
            + "password='another secret value'\n"
            + "{\"token\":\"json secret value\",\"authorization\":\"Digest opaque value\"}\n";
        String text = SupportRedactor.redact(raw, "").text;
        assertFalse(text.contains("dXNlcjpwYXNz"));
        assertFalse(text.contains("supersecret"));
        assertFalse(text.contains("secret with spaces"));
        assertFalse(text.contains("another secret value"));
        assertFalse(text.contains("json secret value"));
        assertFalse(text.contains("opaque value"));
        assertEquals(6, count(text, "<redacted-secret>"));
    }

    @Test public void redactsTwoComponentPackagesIpv6AndModernUuidVersions() {
        String raw = "package=com.bank ipv6=2001:db8::1234 uuid7=01890f5b-5d8b-7cc2-98d5-4f9f6f20b7a1";
        String text = SupportRedactor.redact(raw, "").text;
        assertFalse(text.contains("com.bank"));
        assertFalse(text.contains("2001:db8::1234"));
        assertFalse(text.contains("01890f5b-5d8b-7cc2-98d5-4f9f6f20b7a1"));
        assertTrue(text.contains("<package:"));
        assertTrue(text.contains("<ip6:"));
        assertTrue(text.contains("<id:"));
    }

    @Test public void pseudonymsAreDeterministic() {
        String raw = "com.example.private com.example.private /sdcard/Private/file.txt /sdcard/Private/file.txt \"/sdcard/Private Folder/tax return.pdf\"";
        String first = SupportRedactor.redact(raw, "").text;
        String second = SupportRedactor.redact(raw, "").text;
        assertEquals(first, second);
        assertEquals(2, count(first, "<package:"));
        assertEquals(3, count(first, "<private-path:"));
        assertFalse(first.contains("tax return.pdf"));
    }

    private static int count(String text, String needle) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }
}
