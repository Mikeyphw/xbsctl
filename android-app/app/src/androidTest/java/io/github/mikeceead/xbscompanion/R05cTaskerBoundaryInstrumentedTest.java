package io.github.mikeceead.xbscompanion;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** An untrusted caller must never launch a root command or satisfy Tasker condition. */
@RunWith(AndroidJUnit4.class)
public final class R05cTaskerBoundaryInstrumentedTest {
    private static final byte[] SECRET="r05c-test-only-fixed-local-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private final Context app = ApplicationProvider.getApplicationContext();

    private int orderedResult(Intent request) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger result = new AtomicInteger(Integer.MIN_VALUE);
        app.sendOrderedBroadcast(request, null, new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                result.set(getResultCode());
                latch.countDown();
            }
        }, new Handler(Looper.getMainLooper()), 0, null, null);
        assertTrue("Ordered receiver did not finish", latch.await(15, TimeUnit.SECONDS));
        return result.get();
    }

    @Test public void unsignedActionFailsWithoutStartingRoot() throws Exception {
        Intent request = new Intent(app, TaskerPluginReceiver.class).setAction(TaskerProtocol.ACTION_FIRE_SETTING);
        Bundle invalid = new Bundle(); invalid.putString(TaskerProtocol.KEY_CLI_COMMAND, "ctl_pause");
        request.putExtra(TaskerProtocol.EXTRA_BUNDLE, invalid);
        assertEquals(TaskerProtocol.RESULT_FAILED, orderedResult(request));
        assertFalse(ExternalEntryStore.cached(app).accepted);
    }

    @Test public void unsignedConditionIsUnknownRatherThanSatisfied() throws Exception {
        Intent request = new Intent(app, TaskerConditionReceiver.class)
            .setAction(TaskerConditionProtocol.ACTION_QUERY_CONDITION);
        assertEquals(TaskerConditionProtocol.UNKNOWN, orderedResult(request));
        assertFalse(ExternalEntryStore.cached(app).accepted);
    }

    @Test public void policyDetectsModifiedSignedPayload() {
        Map<String,String> values = new LinkedHashMap<>();
        values.put(TaskerRequestPolicy.KEY_VERSION, "2");
        values.put(TaskerRequestPolicy.KEY_HOST_PACKAGE, TaskerHostIdentity.TASKER);
        values.put(TaskerRequestPolicy.KEY_HOST_CERT, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        values.put(TaskerRequestPolicy.KEY_NONCE, "abcdefghijklmnop1234");
        values.put(TaskerRequestPolicy.KEY_ALLOW_DESTRUCTIVE, "false");
        values.put(TaskerProtocol.KEY_MODULE, "xbs");
        values.put(TaskerProtocol.KEY_BACKEND, "root");
        values.put(TaskerProtocol.KEY_CLI_COMMAND, "status");
        values.put(TaskerProtocol.KEY_CLI_VALUES, "{}");
        values.put(TaskerRequestPolicy.KEY_SIGNATURE, TaskerRequestPolicy.sign(values, SECRET));
        assertTrue(TaskerRequestPolicy.verify(values, TaskerRequestPolicy.actionKeys(), SECRET).valid);
        values.put(TaskerProtocol.KEY_CLI_COMMAND, "ctl_safe");
        assertFalse(TaskerRequestPolicy.verify(values, TaskerRequestPolicy.actionKeys(), SECRET).valid);
    }

    @Test public void exportedBoundaryIsLimitedToTaskerReceivers() throws Exception {
        PackageManager pm = app.getPackageManager();
        ActivityInfo action = pm.getReceiverInfo(new ComponentName(app, TaskerPluginReceiver.class), 0);
        ActivityInfo condition = pm.getReceiverInfo(new ComponentName(app, TaskerConditionReceiver.class), 0);
        assertTrue(action.exported);
        assertTrue(condition.exported);
        assertFalse(pm.getServiceInfo(new ComponentName(app, RootCommandService.class), 0).exported);
        assertFalse(pm.getServiceInfo(new ComponentName(app, EventMonitorService.class), 0).exported);
    }
}
