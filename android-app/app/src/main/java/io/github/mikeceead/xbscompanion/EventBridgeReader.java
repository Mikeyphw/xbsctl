package io.github.mikeceead.xbscompanion;

import android.content.Context;

/** Preferred authenticated snapshot reader with an explicit bounded availability/freshness fallback. */
final class EventBridgeReader {
    private EventBridgeReader() {}

    static XbsSnapshot read(Context context) {
        Context app = context.getApplicationContext();
        long now = System.currentTimeMillis();
        XbsRootApi.CommandOutput output = XbsRootApi.runRootLarge(verifiedCommand(), 20);
        String machineOutput = output.stdout == null ? "" : output.stdout.trim();
        EventBridgeEnvelope envelope = machineOutput.isEmpty() && output.exitCode != 0
            ? EventBridgeEnvelope.failure("verifier_unavailable",
                output.error.isEmpty() ? "Authenticated bridge verifier could not start" : output.error)
            : EventBridgeEnvelope.parse(machineOutput);
        if (!envelope.authenticated) {
            String detail = envelope.detail;
            if (detail.isEmpty()) detail = output.error.isEmpty() ? "Authenticated bridge verifier is unavailable" : output.error;
            String reasonCode = envelope.reasonCode.isEmpty() ? "verifier_unavailable" : envelope.reasonCode;
            return EventBridgePolicy.fallbackAllowed(reasonCode)
                ? fallback(app, now, reasonCode, detail, envelope)
                : rejectSecurityFailure(app, now, reasonCode, detail, envelope);
        }

        EventBridgeStore.Cursor cursor = EventBridgeStore.cursor(app);
        EventBridgePolicy.Decision decision = EventBridgePolicy.evaluate(now, envelope.sequence,
            envelope.issuedAt, envelope.keyId, envelope.digest, envelope.payloadChars,
            cursor.sequence, cursor.keyId, cursor.digest);
        if (!decision.accepted) {
            return EventBridgePolicy.fallbackAllowed(decision.code)
                ? fallback(app, now, decision.code, decision.detail, envelope)
                : rejectSecurityFailure(app, now, decision.code, decision.detail, envelope);
        }

        XbsSnapshot snapshot = XbsSnapshot.parse(envelope.snapshotJson);
        if (!snapshot.available) {
            return rejectSecurityFailure(app, now, "invalid_verified_snapshot", snapshot.error, envelope);
        }
        if ((!decision.duplicate || decision.keyRotated)
            && !EventBridgeStore.accept(app, envelope.keyId, envelope.sequence, envelope.digest)) {
            return rejectSecurityFailure(app, now, "cursor_persist_failed",
                "Authenticated snapshot could not be durably recorded", envelope);
        }
        EventBridgeStore.save(app, EventBridgeReport.authenticated(now, envelope.algorithm,
            envelope.keyId, envelope.sequence, envelope.issuedAt, envelope.digest,
            decision.duplicate, decision.keyRotated));
        return snapshot;
    }

    private static XbsSnapshot fallback(Context context, long now, String reasonCode, String detail,
                                        EventBridgeEnvelope envelope) {
        XbsSnapshot snapshot = XbsRootApi.readLegacySnapshot();
        EventBridgeStore.save(context, EventBridgeReport.fallback(now, reasonCode, detail,
            envelope.sequence, envelope.issuedAt, envelope.digest, false, snapshot.available));
        return snapshot;
    }

    private static XbsSnapshot rejectSecurityFailure(Context context, long now, String reasonCode,
                                                     String detail, EventBridgeEnvelope envelope) {
        EventBridgeStore.save(context, EventBridgeReport.fallback(now, reasonCode, detail,
            envelope.sequence, envelope.issuedAt, envelope.digest, true, false));
        return XbsSnapshot.unavailable("Authenticated event bridge rejected the snapshot: " + detail);
    }

    static String verifiedCommand() {
        return "FOUND=0; "
            + "run_xbs_bridge() { X=\"$1\"; [ -x \"$X\" ] || return 127; FOUND=1; "
            + "OUT=$(\"$X\" --json event-bridge 2>&1); case \"$OUT\" in \\{*) printf '%s\\n' \"$OUT\"; exit 0;; esac; return 126; }; "
            + XbsRuntimeResolver.probeCalls("run_xbs_bridge", "xbs")
            + "XBSPATH=$(command -v xbs 2>/dev/null || true); [ -n \"$XBSPATH\" ] && run_xbs_bridge \"$XBSPATH\"; "
            + "if [ \"$FOUND\" = 1 ]; then "
            + "printf '%s\\n' '{\"ok\":false,\"data\":{\"bridge\":{\"authenticated\":false,\"reason_code\":\"verifier_usage\",\"detail\":\"xbs event-bridge emitted non-JSON CLI usage; adaptive fallback will poll legacy runtime state\"}}}'; exit 126; "
            + "else printf '%s\\n' '{\"ok\":false,\"data\":{\"bridge\":{\"authenticated\":false,\"reason_code\":\"xbs_missing\",\"detail\":\"xbs executable not found\"}}}'; exit 127; fi";
    }
}
