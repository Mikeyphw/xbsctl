package io.github.mikeceead.xbscompanion;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class TaskerAuthorizationMatrixTest {
    private Map<String,String> signed(byte[] key) {
        Map<String,String> m = new LinkedHashMap<>();
        m.put(TaskerRequestPolicy.KEY_VERSION, "2");
        m.put(TaskerRequestPolicy.KEY_HOST_PACKAGE, TaskerHostIdentity.TASKER);
        m.put(TaskerRequestPolicy.KEY_HOST_CERT, String.join("", Collections.nCopies(64, "a")));
        m.put(TaskerRequestPolicy.KEY_NONCE, "abcdefghijklmnop");
        m.put(TaskerProtocol.KEY_MODULE, "xbs");
        m.put(TaskerProtocol.KEY_CLI_COMMAND, "status");
        m.put(TaskerProtocol.KEY_CLI_VALUES, "{}");
        m.put(TaskerProtocol.KEY_BACKEND, "root");
        m.put(TaskerRequestPolicy.KEY_ALLOW_DESTRUCTIVE, "false");
        m.put(TaskerRequestPolicy.KEY_SIGNATURE, TaskerRequestPolicy.sign(m, key));
        return m;
    }

    @Test public void apiPolicyCovers26_28_33_34AndCurrent() {
        for (int api : new int[]{26, 28, 33}) {
            assertTrue(TaskerConfigCodec.legacySenderPolicy(api, true));
            assertFalse(TaskerConfigCodec.legacySenderPolicy(api, false));
        }
        for (int api : new int[]{34, 36}) assertFalse(TaskerConfigCodec.legacySenderPolicy(api, true));
    }

    @Test public void signedRequestAndSpoofedPackageCertificateAndNonceAreCovered() {
        byte[] key = new byte[32]; Arrays.fill(key, (byte)11);
        Map<String,String> original = signed(key);
        assertTrue(TaskerRequestPolicy.verify(original, TaskerRequestPolicy.actionKeys(), key).valid);
        for (String field : new String[]{TaskerRequestPolicy.KEY_HOST_PACKAGE, TaskerRequestPolicy.KEY_HOST_CERT, TaskerRequestPolicy.KEY_NONCE}) {
            Map<String,String> changed = new LinkedHashMap<>(original);
            changed.put(field, field.equals(TaskerRequestPolicy.KEY_HOST_PACKAGE) ? TaskerHostIdentity.TASKER_LEGACY
                : field.equals(TaskerRequestPolicy.KEY_HOST_CERT) ? String.join("", Collections.nCopies(64, "b"))
                : "ponmlkjihgfedcba");
            assertEquals("bad_signature", TaskerRequestPolicy.verify(changed, TaskerRequestPolicy.actionKeys(), key).code);
        }
    }

    @Test public void replayAttemptCannotMutateSignedExecutionMetadata() {
        byte[] key = new byte[32]; Arrays.fill(key, (byte)17);
        Map<String,String> replay = signed(key);
        assertTrue(TaskerRequestPolicy.verify(replay, TaskerRequestPolicy.actionKeys(), key).valid);
        Map<String,String> mutatedReplay = new LinkedHashMap<>(replay);
        mutatedReplay.put(TaskerProtocol.KEY_CLI_COMMAND, "runtime_pause");
        assertEquals("bad_signature", TaskerRequestPolicy.verify(mutatedReplay, TaskerRequestPolicy.actionKeys(), key).code);
    }
}
