package io.github.mikeceead.xbscompanion;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Foreground owner for bounded root command execution. */
public final class RootCommandService extends Service {
    static final String ACTION_RUN = "io.github.mikeceead.xbscompanion.RUN_ROOT_COMMAND";
    static final String ACTION_CANCEL = "io.github.mikeceead.xbscompanion.CANCEL_ROOT_COMMAND";
    static final String EXTRA_RUN_ID = "run_id";
    static final int FOREGROUND_ID = 0x584254;

    private static final ConcurrentHashMap<String, PendingCommand> PENDING = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, PendingCommand> ACTIVE = new ConcurrentHashMap<>();
    private static final AtomicBoolean SERVICE_ACTIVE = new AtomicBoolean(false);

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final AtomicInteger activeRuns = new AtomicInteger(0);
    private NotificationController notifications;
    private volatile boolean foregroundStarted;

    static boolean isServiceActive() { return SERVICE_ACTIVE.get(); }
    /** Duplicate service intents must not terminate or replay an already owned command. */
    static boolean isDuplicateRunForTest(String runId) { return runId != null && ACTIVE.containsKey(runId); }
    static void clearInMemoryForTest() { PENDING.clear(); ACTIVE.clear(); }

    static ForegroundServiceLaunchResult start(Context context, String runId, int executionId, XbsCommandSpec spec, Intent taskerIntent) {
        Context app = context.getApplicationContext();
        PendingCommand queued = new PendingCommand(runId, executionId, spec,
            taskerIntent == null ? null : new Intent(taskerIntent));
        PendingCommandStore.save(app, runId, executionId, spec, taskerIntent != null);
        PENDING.put(runId, queued);
        Intent intent = new Intent(app, RootCommandService.class)
            .setAction(ACTION_RUN)
            .putExtra(EXTRA_RUN_ID, runId);
        ForegroundServiceLaunchResult result = ForegroundServiceLauncher.start(app, intent, "root_command");
        if (!result.started) { PENDING.remove(runId); PendingCommandStore.remove(app, runId); }
        return result;
    }

    static ForegroundServiceLaunchResult requestCancel(Context context, String runId) {
        if (runId == null || runId.trim().isEmpty()) return new ForegroundServiceLaunchResult(false, "cancel_command", "invalid_run", "Missing run ID", System.currentTimeMillis());
        Context app = context.getApplicationContext();
        Intent intent = new Intent(app, RootCommandService.class)
            .setAction(ACTION_CANCEL)
            .putExtra(EXTRA_RUN_ID, runId);
        return ForegroundServiceLauncher.start(app, intent, "cancel_command");
    }

    @Override public void onCreate() {
        super.onCreate();
        notifications = new NotificationController(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_RUN : intent.getAction();
        String runId = intent == null ? "" : intent.getStringExtra(EXTRA_RUN_ID);
        if (runId == null) runId = "";

        if (ACTION_CANCEL.equals(action)) {
            ForegroundServiceLaunchResult promotion = ensureForeground("Preparing cancellation…");
            if (!promotion.started) {
                RuntimeNotificationStore.recordAction(this, promotion.code, "cancel_command");
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            boolean accepted = RootCommandRunner.cancel(this, runId);
            if (accepted) ensureForeground("Cancelling root command…");
            stopIfIdle();
            return START_NOT_STICKY;
        }
        if (!ACTION_RUN.equals(action)) {
            stopIfIdle();
            return START_NOT_STICKY;
        }

        if (isDuplicateRunForTest(runId)) {
            RuntimeNotificationStore.recordAction(this, "duplicate_run_ignored", "root_command");
            return START_NOT_STICKY;
        }
        PendingCommand command = PENDING.remove(runId);
        if (command == null) {
            PendingCommandStore.Recovered recovered = PendingCommandStore.load(this, runId);
            if (recovered != null && recovered.valid && !recovered.tasker && !recovered.owned) {
                command = new PendingCommand(runId, recovered.executionId, recovered.spec, null);
            } else {
                ensureForeground("Finalizing interrupted root command…");
                String detail;
                if (recovered != null && recovered.owned) {
                    RootProcessTree.TerminationResult termination;
                    try { termination = RootProcessTree.terminate(RootProcessTree.pidFile(runId)); }
                    catch (IllegalArgumentException invalid) { termination = new RootProcessTree.TerminationResult(false, "verification_failed", "invalid run id"); }
                    detail = "Previously owned root command will not be re-executed after process recreation; root tree=" + termination.status;
                } else if (recovered != null && recovered.tasker) {
                    detail = "Pending Tasker command could not be safely recovered after process recreation";
                } else if (recovered != null && !recovered.reason.isEmpty()) {
                    detail = "Pending root command could not be recovered after process recreation: " + recovered.reason;
                } else {
                    detail = "Pending root command was missing after process recreation";
                }
                RunRecord record = RunStore.find(this, runId);
                if (record != null && ("running".equals(record.status) || "cancelling".equals(record.status))) {
                    RunStore.finish(this, runId, -1, "", "", detail);
                }
                PendingCommandStore.remove(this, runId);
                stopIfIdle();
                return START_NOT_STICKY;
            }
        }
        ACTIVE.put(runId, command);
        if (!PendingCommandStore.markOwned(this, runId)) {
            ensureForeground("Finalizing root command ownership failure…");
            command.state.set(2);
            ACTIVE.remove(runId, command);
            RootCommandRunner.failBeforeExecution(this, command.runId, command.executionId, command.spec,
                command.taskerIntent, "Pending root command ownership could not be durably persisted");
            PendingCommandStore.remove(this, command.runId);
            stopIfIdle();
            return START_NOT_STICKY;
        }
        ForegroundServiceLaunchResult promotion = ensureForeground(command.spec.action.title);
        if (!promotion.started) {
            command.state.set(2);
            ACTIVE.remove(runId, command);
            RootCommandRunner.failBeforeExecution(this, command.runId, command.executionId, command.spec,
                command.taskerIntent, promotion.detail);
            PendingCommandStore.remove(this, command.runId);
            stopIfIdle();
            return START_NOT_STICKY;
        }
        activeRuns.incrementAndGet();
        final PendingCommand ownedCommand = command;
        try {
            executor.execute(() -> {
                // The timeout/abort path owns accounting if it won the state race.
                // A rejected claim MUST NOT decrement activeRuns again.
                if (!ownedCommand.state.compareAndSet(0, 1)) return;
                try {
                    RootCommandRunner.execute(this, ownedCommand.runId, ownedCommand.executionId, ownedCommand.spec, ownedCommand.taskerIntent);
                } finally {
                    ownedCommand.state.set(2);
                    ACTIVE.remove(ownedCommand.runId, ownedCommand);
                    PendingCommandStore.remove(this, ownedCommand.runId);
                    int remaining = Math.max(0, activeRuns.decrementAndGet());
                    if (remaining > 0) ensureForeground("Root commands still running");
                    else stopIfIdle();
                }
            });
        } catch (RuntimeException rejected) {
            if (command.state.compareAndSet(0, 2)) {
                ACTIVE.remove(command.runId, command);
                activeRuns.updateAndGet(value -> Math.max(0, value - 1));
                RootCommandRunner.failBeforeExecution(this, command.runId, command.executionId, command.spec,
                    command.taskerIntent, "Root command worker unavailable: " + rejected.getClass().getSimpleName());
                PendingCommandStore.remove(this, command.runId);
            }
            stopIfIdle();
        }
        return START_NOT_STICKY;
    }

    private ForegroundServiceLaunchResult ensureForeground(String detail) {
        if (notifications == null) notifications = new NotificationController(this);
        if (!foregroundStarted) {
            ForegroundServiceLaunchResult result = ForegroundServiceLauncher.promote(this, FOREGROUND_ID,
                notifications.buildCommandServiceStatus(RunStore.active(this), detail), "root_command_promote");
            if (result.started) {
                foregroundStarted = true;
                SERVICE_ACTIVE.set(true);
                RuntimeNotificationStore.recordService(this, true, "Root command service active");
            }
            return result;
        }
        notifications.postCommandServiceStatus(detail);
        return ForegroundServiceLaunchResult.started("root_command_promote", System.currentTimeMillis());
    }

    private void stopIfIdle() {
        if (activeRuns.get() > 0 || !RunStore.active(this).isEmpty()) return;
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foregroundStarted = false;
        }
        stopSelf();
    }

    @Override public void onTimeout(int startId, int fgsType) {
        RuntimeNotificationStore.recordTimeout(this, "Root command foreground service timed out");
        abortOutstanding("Foreground service timed out");
        executor.shutdownNow();
        stopForeground(STOP_FOREGROUND_REMOVE);
        foregroundStarted = false;
        stopSelf(startId);
    }

    private void abortOutstanding(String detail) {
        for (PendingCommand command : ACTIVE.values()) {
            if (command.state.compareAndSet(0, 2)) {
                ACTIVE.remove(command.runId, command);
                activeRuns.updateAndGet(value -> Math.max(0, value - 1));
                RootCommandRunner.failBeforeExecution(this, command.runId, command.executionId, command.spec,
                    command.taskerIntent, detail);
                PendingCommandStore.remove(this, command.runId);
            } else if (command.state.get() == 1) {
                RootCommandRunner.cancel(this, command.runId);
            }
        }
    }

    @Override public void onDestroy() {
        abortOutstanding("Root command service stopped before execution completed");
        SERVICE_ACTIVE.set(false);
        RuntimeNotificationStore.recordService(this, false, "Root command service stopped");
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private static final class PendingCommand {
        final String runId;
        final int executionId;
        final XbsCommandSpec spec;
        final Intent taskerIntent;
        final AtomicInteger state = new AtomicInteger(0); // 0 queued, 1 running, 2 finalized

        PendingCommand(String runId, int executionId, XbsCommandSpec spec, Intent taskerIntent) {
            this.runId = runId;
            this.executionId = executionId;
            this.spec = spec;
            this.taskerIntent = taskerIntent;
        }
    }
}
