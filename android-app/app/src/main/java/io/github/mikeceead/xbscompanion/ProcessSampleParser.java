package io.github.mikeceead.xbscompanion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure-Java parser and CPU-delta policy for root /proc samples. */
final class ProcessSampleParser {
    private static final int MAX_SCAN_OUTPUT_CHARS = 2_000_000;
    private static final int MAX_SCAN_LINES = 50_000;
    private static final int MAX_PROCESS_ROWS = 25_000;
    private static final int MAX_FIELD_CHARS = 8192;

    private ProcessSampleParser() {}

    static Sample parse(String output) {
        long totalTicks = -1L;
        int cpuCount = 1;
        int scanned = 0;
        int readable = 0;
        int restricted = 0;
        int malformed = 0;
        int cmdlineUnavailable = 0;
        int executableUnavailable = 0;
        int contextUnavailable = 0;
        int localMalformed = 0;
        String explicitError = "";
        List<Row> rows = new ArrayList<>();

        if (output == null) output = "";
        if (output.length() > MAX_SCAN_OUTPUT_CHARS) {
            return Sample.error("Process scan output exceeded the parser safety bound.", totalTicks, cpuCount,
                scanned, readable, restricted, malformed + 1,
                cmdlineUnavailable, executableUnavailable, contextUnavailable);
        }
        int lineCount = 0;
        for (String rawLine : output.split("\n", -1)) {
            lineCount++;
            if (lineCount > MAX_SCAN_LINES) {
                return Sample.error("Process scan returned too many rows to parse safely.", totalTicks, cpuCount,
                    scanned, readable, restricted, malformed + localMalformed + 1,
                    cmdlineUnavailable, executableUnavailable, contextUnavailable);
            }
            String line = rawLine == null ? "" : rawLine.replace("\r", "");
            if (line.isEmpty()) continue;
            if (line.length() > MAX_FIELD_CHARS * 4) {
                return Sample.error("Process scan contained an oversized line and was rejected before caching.", totalTicks, cpuCount,
                    scanned, readable, restricted, malformed + localMalformed + 1,
                    cmdlineUnavailable, executableUnavailable, contextUnavailable);
            }
            if (line.startsWith("ERROR\t")) {
                explicitError = line.substring(6).trim();
                continue;
            }
            if (line.startsWith("CPU\t")) {
                totalTicks = parseCpuTotal(line.substring(4));
                if (totalTicks < 0L) localMalformed++;
                continue;
            }
            if (line.startsWith("META\t")) {
                String[] fields = line.split("\\t", -1);
                if (fields.length < 9) {
                    localMalformed++;
                    continue;
                }
                scanned = nonNegativeInt(fields[1], scanned);
                readable = nonNegativeInt(fields[2], readable);
                restricted = nonNegativeInt(fields[3], restricted);
                malformed = nonNegativeInt(fields[4], malformed);
                cmdlineUnavailable = nonNegativeInt(fields[5], cmdlineUnavailable);
                executableUnavailable = nonNegativeInt(fields[6], executableUnavailable);
                contextUnavailable = nonNegativeInt(fields[7], contextUnavailable);
                cpuCount = Math.max(1, nonNegativeInt(fields[8], cpuCount));
                continue;
            }
            if (!line.startsWith("PROC\t")) {
                localMalformed++;
                continue;
            }
            String[] fields = line.split("\\t", -1);
            if (fields.length > 15) {
                return Sample.error("Process scan returned an ambiguous process row with extra fields.", totalTicks, cpuCount,
                    scanned, readable, restricted, malformed + localMalformed + 1,
                    cmdlineUnavailable, executableUnavailable, contextUnavailable);
            }
            if (fields.length < 15) {
                localMalformed++;
                continue;
            }
            if (hasOversizedField(fields)) {
                return Sample.error("Process scan returned an oversized process field and was rejected before caching.", totalTicks, cpuCount,
                    scanned, readable, restricted, malformed + localMalformed + 1,
                    cmdlineUnavailable, executableUnavailable, contextUnavailable);
            }
            if (rows.size() >= MAX_PROCESS_ROWS) {
                return Sample.error("Process scan returned too many process rows to parse safely.", totalTicks, cpuCount,
                    scanned, readable, restricted, malformed + localMalformed + 1,
                    cmdlineUnavailable, executableUnavailable, contextUnavailable);
            }
            try {
                int pid = positiveInt(fields[1]);
                int uid = integer(fields[2]);
                long rssKb = nonNegativeLong(fields[3]);
                long ticks = nonNegativeLong(fields[4]);
                int nice = integer(fields[5]);
                long startTicks = positiveLong(fields[6]);
                int parentPid = nonNegativeIntStrict(fields[7]);
                String comm = clean(fields[8]);
                if (comm.isEmpty()) comm = "pid-" + pid;
                String command = clean(fields[9]);
                if (command.isEmpty()) command = comm;
                rows.add(new Row(
                    pid,
                    uid,
                    rssKb,
                    ticks,
                    nice,
                    startTicks,
                    parentPid,
                    comm,
                    command,
                    clean(fields[10]),
                    clean(fields[11]),
                    flag(fields[12]),
                    flag(fields[13]),
                    flag(fields[14])));
            } catch (IllegalArgumentException ignored) {
                localMalformed++;
            }
        }

        malformed += localMalformed;
        if (!explicitError.isEmpty()) {
            return Sample.error(explicitError, totalTicks, cpuCount, scanned, readable, restricted, malformed,
                cmdlineUnavailable, executableUnavailable, contextUnavailable);
        }
        if (totalTicks < 0L) {
            return Sample.error("The process scan did not return a readable aggregate CPU line from /proc/stat.",
                totalTicks, cpuCount, scanned, readable, restricted, malformed,
                cmdlineUnavailable, executableUnavailable, contextUnavailable);
        }
        if (rows.isEmpty()) {
            String message;
            if (scanned > 0 && restricted >= scanned) {
                message = "Root was granted, but every visible /proc process entry was restricted. Check root-manager mount namespace and /proc access settings.";
            } else if (malformed > 0) {
                message = "Root returned process data, but no complete process row could be parsed. The kernel /proc layout may be restricted or unexpected.";
            } else {
                message = "Root was granted, but no readable processes were returned from /proc.";
            }
            return Sample.error(message, totalTicks, cpuCount, scanned, readable, restricted, malformed,
                cmdlineUnavailable, executableUnavailable, contextUnavailable);
        }
        return new Sample(totalTicks, cpuCount, scanned, Math.max(readable, rows.size()), restricted,
            malformed, cmdlineUnavailable, executableUnavailable, contextUnavailable, rows, "");
    }

    /**
     * Parse the aggregate cpu line and deliberately exclude guest/guest_nice.
     * Linux already includes those values in user/nice, so adding them again inflates the denominator.
     */
    static long parseCpuTotal(String cpuLine) {
        if (cpuLine == null) return -1L;
        String[] fields = cpuLine.trim().split("\\s+");
        if (fields.length < 5 || !"cpu".equals(fields[0])) return -1L;
        long total = 0L;
        int last = Math.min(8, fields.length - 1); // user through steal; never guest or guest_nice
        for (int i = 1; i <= last; i++) {
            try {
                long value = Long.parseLong(fields[i]);
                if (value < 0L) return -1L;
                total = Math.addExact(total, value);
            } catch (ArithmeticException | NumberFormatException ignored) {
                return -1L;
            }
        }
        return total;
    }

    static double cpuPercent(
            long currentTicks,
            long currentStartTicks,
            long previousTicks,
            long previousStartTicks,
            long totalDelta,
            int cpuCount) {
        if (currentStartTicks <= 0L || previousStartTicks <= 0L || currentStartTicks != previousStartTicks) return 0.0;
        if (currentTicks < previousTicks || totalDelta <= 0L) return 0.0;
        int cores = Math.max(1, cpuCount);
        double value = ((currentTicks - previousTicks) * cores * 100.0) / totalDelta;
        if (!Double.isFinite(value) || value < 0.0) return 0.0;
        return Math.min(cores * 100.0, value);
    }

    private static String clean(String value) {
        if (value == null) return "";
        String cleaned = value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim();
        return cleaned.length() > MAX_FIELD_CHARS ? cleaned.substring(0, MAX_FIELD_CHARS) : cleaned;
    }

    private static boolean hasOversizedField(String[] fields) {
        for (String field : fields) if (field != null && field.length() > MAX_FIELD_CHARS) return true;
        return false;
    }

    private static boolean flag(String value) { return "1".equals(value == null ? "" : value.trim()); }

    private static int nonNegativeInt(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed < 0 ? fallback : parsed;
        } catch (Exception ignored) { return fallback; }
    }

    private static int positiveInt(String value) {
        int parsed = integer(value);
        if (parsed <= 0) throw new IllegalArgumentException("pid");
        return parsed;
    }

    private static int nonNegativeIntStrict(String value) {
        int parsed = integer(value);
        if (parsed < 0) throw new IllegalArgumentException("negative");
        return parsed;
    }

    private static int integer(String value) {
        try { return Integer.parseInt(value.trim()); }
        catch (Exception ignored) { throw new IllegalArgumentException("integer"); }
    }

    private static long positiveLong(String value) {
        long parsed = nonNegativeLong(value);
        if (parsed <= 0L) throw new IllegalArgumentException("positive");
        return parsed;
    }

    private static long nonNegativeLong(String value) {
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed < 0L) throw new IllegalArgumentException("negative");
            return parsed;
        } catch (NumberFormatException ignored) { throw new IllegalArgumentException("long"); }
    }

    static final class Row {
        final int pid;
        final int uid;
        final long rssKb;
        final long ticks;
        final int niceValue;
        final long startTicks;
        final int parentPid;
        final String processName;
        final String command;
        final String executable;
        final String selinuxContext;
        final boolean commandLineObserved;
        final boolean executableObserved;
        final boolean contextObserved;

        Row(int pid, int uid, long rssKb, long ticks, int niceValue, long startTicks, int parentPid,
            String processName, String command, String executable, String selinuxContext,
            boolean commandLineObserved, boolean executableObserved, boolean contextObserved) {
            this.pid = pid;
            this.uid = uid;
            this.rssKb = rssKb;
            this.ticks = ticks;
            this.niceValue = niceValue;
            this.startTicks = startTicks;
            this.parentPid = parentPid;
            this.processName = processName;
            this.command = command;
            this.executable = executable;
            this.selinuxContext = selinuxContext;
            this.commandLineObserved = commandLineObserved;
            this.executableObserved = executableObserved;
            this.contextObserved = contextObserved;
        }

        String identityKey() { return pid + ":" + startTicks; }
    }

    static final class Sample {
        final long totalTicks;
        final int cpuCount;
        final int scanned;
        final int readable;
        final int restricted;
        final int malformed;
        final int cmdlineUnavailable;
        final int executableUnavailable;
        final int contextUnavailable;
        final List<Row> rows;
        final String error;

        Sample(long totalTicks, int cpuCount, int scanned, int readable, int restricted, int malformed,
               int cmdlineUnavailable, int executableUnavailable, int contextUnavailable,
               List<Row> rows, String error) {
            this.totalTicks = totalTicks;
            this.cpuCount = Math.max(1, cpuCount);
            this.scanned = Math.max(0, scanned);
            this.readable = Math.max(0, readable);
            this.restricted = Math.max(0, restricted);
            this.malformed = Math.max(0, malformed);
            this.cmdlineUnavailable = Math.max(0, cmdlineUnavailable);
            this.executableUnavailable = Math.max(0, executableUnavailable);
            this.contextUnavailable = Math.max(0, contextUnavailable);
            this.rows = Collections.unmodifiableList(new ArrayList<>(rows));
            this.error = error == null ? "" : error;
        }

        static Sample error(String error, long totalTicks, int cpuCount, int scanned, int readable,
                            int restricted, int malformed, int cmdlineUnavailable,
                            int executableUnavailable, int contextUnavailable) {
            return new Sample(totalTicks, cpuCount, scanned, readable, restricted, malformed,
                cmdlineUnavailable, executableUnavailable, contextUnavailable,
                Collections.emptyList(), error);
        }

        boolean successful() { return error.isEmpty(); }
    }
}
