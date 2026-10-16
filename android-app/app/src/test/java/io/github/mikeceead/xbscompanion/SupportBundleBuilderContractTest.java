package io.github.mikeceead.xbscompanion;

import org.junit.Test;
import static org.junit.Assert.*;

public final class SupportBundleBuilderContractTest {
    @Test public void bundleLimitsRemainBounded() {
        assertEquals(18, SupportBundleBuilder.FORMAT_VERSION);
        assertTrue(SupportBundleBuilder.MAX_RUNS <= 24);
        assertTrue(SupportBundleBuilder.MAX_EVENTS <= 12);
        assertTrue(SupportBundleBuilder.MAX_FIELD_CHARS <= RunRetentionPolicy.MAX_OUTPUT_CHARS);
        assertTrue(SupportBundleBuilder.MAX_COMMAND_CHARS < SupportBundleBuilder.MAX_FIELD_CHARS);
    }

    @Test public void bundleUsesVersionedRedaction() {
        assertEquals(2, SupportRedactor.POLICY_VERSION);
        assertTrue(SupportRedactor.MAX_INPUT_CHARS <= 256000);
    }
}
