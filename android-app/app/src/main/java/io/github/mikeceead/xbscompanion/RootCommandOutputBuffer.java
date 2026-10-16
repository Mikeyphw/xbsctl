package io.github.mikeceead.xbscompanion;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

final class RootCommandOutputBuffer {
    private final StringBuilder buffer = new StringBuilder();
    private final int maxChars;

    RootCommandOutputBuffer(int maxChars) {
        this.maxChars = Math.max(1024, maxChars);
    }

    Thread drain(InputStream input, String name) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) append(line);
            } catch (Exception ignored) {
                // Output collection must never crash command completion.
            }
        }, name);
        thread.start();
        return thread;
    }

    private synchronized void append(String line) {
        buffer.append(line).append('\n');
        if (buffer.length() > maxChars) buffer.delete(0, buffer.length() - maxChars);
    }

    synchronized String text() {
        return buffer.toString();
    }
}
