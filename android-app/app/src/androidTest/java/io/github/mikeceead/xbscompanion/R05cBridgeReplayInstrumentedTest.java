package io.github.mikeceead.xbscompanion;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** No root calls: exercises the actual Android persistent anti-replay cursor. */
@RunWith(AndroidJUnit4.class)
public final class R05cBridgeReplayInstrumentedTest {
    private static final String K="aaaaaaaaaaaaaaaa", K2="bbbbbbbbbbbbbbbb";
    private static final String D="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String D2="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private Context app;

    @Before public void before() {
        app = ApplicationProvider.getApplicationContext();
        assertTrue(app.getSharedPreferences("event_bridge_cursor_v1", Context.MODE_PRIVATE).edit().clear().commit());
    }
    @After public void after() {
        app.getSharedPreferences("event_bridge_cursor_v1", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test public void olderSequenceCannotOverwriteCommittedCursor() {
        assertTrue(EventBridgeStore.accept(app, K, 17, D));
        assertFalse(EventBridgeStore.accept(app, K, 16, D2));
        EventBridgeStore.Cursor observed = EventBridgeStore.cursor(app);
        assertEquals(17L, observed.sequence);
        assertEquals(D, observed.digest);
    }

    @Test public void equivocationRefusedAndDuplicateIdempotent() {
        assertTrue(EventBridgeStore.accept(app, K, 19, D));
        assertFalse(EventBridgeStore.accept(app, K, 19, D2));
        assertTrue(EventBridgeStore.accept(app, K, 19, D));
        assertEquals(D, EventBridgeStore.cursor(app).digest);
    }

    @Test public void authenticatedRotationRemainsSupported() {
        assertTrue(EventBridgeStore.accept(app, K, 100, D));
        assertTrue(EventBridgeStore.accept(app, K2, 1, D2));
        assertEquals(K2, EventBridgeStore.cursor(app).keyId);
        assertEquals(1L, EventBridgeStore.cursor(app).sequence);
    }

    @Test public void forgedAndMalformedBridgeEvidenceNeverBecomesAuthenticated() {
        assertFalse(EventBridgePolicy.fallbackAllowed("invalid_digest"));
        assertFalse(EventBridgePolicy.fallbackAllowed("replay"));
        assertFalse(EventBridgePolicy.fallbackAllowed("equivocation"));
        assertFalse(EventBridgePolicy.fallbackAllowed("invalid_signature"));
        assertFalse(EventBridgeStore.accept(app, "malformed", 5, D));
        assertEquals(0L, EventBridgeStore.cursor(app).sequence);
    }
}
