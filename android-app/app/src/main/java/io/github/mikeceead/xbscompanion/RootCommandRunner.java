package io.github.mikeceead.xbscompanion;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

final class RootCommandRunner {
    private static final int MAX_STDOUT = 64000;
    private static final int MAX_STDERR = 24000;
    private static final SecureRandom IDS = new SecureRandom();
    private static final ConcurrentHashMap<String,Process> RUNNING = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String,String> CANCELLATIONS = new ConcurrentHashMap<>();

    private RootCommandRunner() {}

    static CommandLaunchResult run(Context context, SuiteAction action, String argument, Intent taskerIntent) {
        return run(context, action, argument, taskerIntent, ExecutionSource.fromTaskerIntent(taskerIntent));
    }

    static CommandLaunchResult run(Context context, SuiteAction action, String argument, Intent taskerIntent,
                                   ExecutionSource source) {
        final XbsCommandSpec spec;
        try {
            spec = XbsCommandSpec.fromAction(action, argument, source);
        } catch (IllegalArgumentException error) {
            return new CommandLaunchResult(false, -1, error.getMessage());
        }

        return launch(context, spec, taskerIntent);
    }

    static CommandLaunchResult runCatalog(Context context, CliCommand command, Map<String,String> values) {
        return runCatalog(context, command, values, ExecutionSource.APP);
    }

    static CommandLaunchResult runCatalog(Context context, CliCommand command, Map<String,String> values,
                                          ExecutionSource source) {
        final XbsCommandSpec spec;
        try {
            CommandCenterPolicy.Descriptor descriptor = CommandCenterPolicy.describe(
                command,
                RootDiagnosticsStore.cached(context),
                XbsCapabilityRegistry.cached(context).report,
                System.currentTimeMillis());
            if (descriptor.availability == CommandCenterPolicy.Availability.BLOCKED) {
                return new CommandLaunchResult(false, -1,
                    descriptor.availabilityLabel + (descriptor.guidance.isEmpty() ? "" : ": " + descriptor.guidance));
            }
            spec = XbsCommandSpec.fromCatalog(command, values, source);
        } catch (IllegalArgumentException error) {
            return new CommandLaunchResult(false, -1, error.getMessage());
        }
        return launch(context, spec, null);
    }

    private static CommandLaunchResult launch(Context context, XbsCommandSpec spec, Intent taskerIntent) {
        int executionId = nextId();
        String runId = "root-" + Integer.toUnsignedString(executionId, 16);
        Context app = context.getApplicationContext();
        RunStore.started(app, runId, spec);
        ForegroundServiceLaunchResult launch = RootCommandService.start(app, runId, executionId, spec, taskerIntent);
        if (!launch.started) {
            failBeforeExecution(app, runId, executionId, spec, taskerIntent, launch.detail);
            return new CommandLaunchResult(false, executionId, runId, launch.detail);
        }
        return new CommandLaunchResult(true, executionId, runId, "");
    }

    static CommandLaunchResult runCommand(Context context, String id, String title, String description,
                                          String command, boolean destructive, Intent taskerIntent) {
        return runCommand(context, id, title, description, command, destructive, taskerIntent,
            ExecutionSource.fromTaskerIntent(taskerIntent));
    }

    static CommandLaunchResult runCommand(Context context, String id, String title, String description,
                                          String command, boolean destructive, Intent taskerIntent,
                                          ExecutionSource source) {
        SuiteAction action = SuiteAction.custom(id, title, description, command, destructive);
        return run(context, action, "", taskerIntent, source);
    }

    static void failBeforeExecution(Context context, String runId, int executionId, XbsCommandSpec spec,
                                    Intent taskerIntent, String detail) {
        String error = detail == null || detail.trim().isEmpty() ? "Foreground execution unavailable" : detail.trim();
        RunStore.finish(context, runId, -1, "", "", error);
        RunRecord record = RunStore.find(context, runId);
        if (record != null) new NotificationController(context).post(record);
        if (taskerIntent != null) {
            Bundle variables = TaskerProtocol.variables(executionId, -1, "", "", error,
                spec.action.module, spec.action.id);
            TaskerProtocol.signalFinish(context, taskerIntent, TaskerProtocol.RESULT_FAILED, variables);
        }
    }

    static void execute(Context context, String runId, int executionId, XbsCommandSpec spec,
                        Intent taskerIntent) {
        int exitCode = -1;
        String error = "";
        RootCommandOutputBuffer stdoutBuffer = new RootCommandOutputBuffer(MAX_STDOUT);
        RootCommandOutputBuffer stderrBuffer = new RootCommandOutputBuffer(MAX_STDERR);
        String pidFile = RootProcessTree.pidFile(runId);
        String shell = RootProcessTree.wrap(pidFile, buildRootShell(spec.command));
        Process process = null;
        boolean timedOut = false;
        String preCancelled = CANCELLATIONS.get(runId);
        try {
            if (preCancelled != null) {
                exitCode = 130;
                error = "Root command cancelled: " + preCancelled;
            } else {
                process = new ProcessBuilder("su", "-c", shell).redirectErrorStream(false).start();
                RUNNING.put(runId, process);
                Thread stdoutThread = stdoutBuffer.drain(process.getInputStream(), "xbs-root-stdout");
                Thread stderrThread = stderrBuffer.drain(process.getErrorStream(), "xbs-root-stderr");
                if (!process.waitFor(spec.timeout.seconds, TimeUnit.SECONDS)) {
                    timedOut = true;
                    RootProcessTree.TerminationResult termination = RootProcessTree.terminate(pidFile);
                    process.destroy();
                    if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
                    if (!termination.terminated) termination = RootProcessTree.terminate(pidFile);
                    exitCode = termination.terminated ? 124 : 125;
                    error = "Root command timed out after " + spec.timeout.seconds + " seconds; privileged process tree=" + termination.status;
                    if (!termination.detail.isEmpty()) error += " (" + termination.detail + ")";
                } else {
                    exitCode = process.exitValue();
                }
                stdoutThread.join(1500L);
                stderrThread.join(1500L);
                String cancellation = CANCELLATIONS.remove(runId);
                if (cancellation != null && !timedOut) {
                    exitCode = 130;
                    error = "Root command cancelled: " + cancellation;
                }
            }
        } catch (Exception exception) {
            error = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            if (process != null) {
                RootProcessTree.terminate(pidFile);
                process.destroyForcibly();
            }
        } finally {
            RUNNING.remove(runId);
            CANCELLATIONS.remove(runId);
        }

        String stdout = stdoutBuffer.text();
        String stderr = stderrBuffer.text();
        RunStore.finish(context, runId, exitCode, stdout, stderr, error);
        RunRecord record = RunStore.find(context, runId);
        if (record != null) new NotificationController(context).post(record);
        XbsStateProcessor.process(context, XbsRootApi.readSnapshot(context), true);
        EventMonitorService.refresh(context);
        if (taskerIntent != null) {
            Bundle variables = TaskerProtocol.variables(executionId, exitCode, stdout, stderr, error,
                spec.action.module, spec.action.id);
            MachineResult machine = MachineResult.parse(stdout);
            boolean ok = exitCode == 0 && error.trim().isEmpty() && (!machine.parsed || machine.ok);
            TaskerProtocol.signalFinish(context, taskerIntent,
                ok ? TaskerProtocol.RESULT_OK : TaskerProtocol.RESULT_FAILED, variables);
        }
    }

    static boolean cancel(Context context, String runId) {
        if (runId == null || runId.trim().isEmpty()) return false;
        RunRecord record = RunStore.find(context, runId);
        if (record == null || !("running".equals(record.status) || "cancelling".equals(record.status))) return false;
        CANCELLATIONS.put(runId, "user request");
        Process process = RUNNING.get(runId);
        RootProcessTree.TerminationResult termination = RootProcessTree.terminateToken(runId);
        if (process != null && process.isAlive()) process.destroy();
        RunStore.cancelling(context, runId, termination.terminated ? "user request" : "user request; root tree=" + termination.status);
        return termination.terminated;
    }

    static boolean cancel(String runId) {
        if (runId == null || runId.trim().isEmpty()) return false;
        Process process = RUNNING.get(runId);
        if (process == null || !process.isAlive()) return false;
        CANCELLATIONS.put(runId, "user request");
        RootProcessTree.TerminationResult termination = RootProcessTree.terminateToken(runId);
        process.destroy();
        return termination.terminated;
    }

    static boolean isRunning(String runId) {
        Process process = RUNNING.get(runId);
        return process != null && process.isAlive();
    }

    private static String buildRootShell(String command) {
        if (command.startsWith("XBSctl ") || command.equals("XBSctl")) {
            String suffix = command.length() > 6 ? command.substring(6) : "";
            return XbsRuntimeResolver.xbsCtlJsonError(suffix, "xbsctl.detect",
                "XBSctl executable not found", "Install or enable the XtremeBS module");
        }
        if (command.startsWith("xbs ") || command.equals("xbs")) {
            String suffix = command.length() > 3 ? command.substring(3) : "";
            return XbsRuntimeResolver.xbsJsonError(suffix, "xbs.detect",
                "XBS executable not found", "Install or enable the XtremeBS module");
        }
        return command;
    }

    private static int nextId() {
        int value;
        do value = IDS.nextInt(Integer.MAX_VALUE); while (value == 0);
        return value;
    }
}
