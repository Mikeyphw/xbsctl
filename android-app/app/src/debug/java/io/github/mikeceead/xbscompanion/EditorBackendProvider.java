package io.github.mikeceead.xbscompanion;

/** Instrumentation runs in the debug target process. No intent, exported IPC or release hook. */
final class EditorBackendProvider {
    private static volatile EditorBackend backend = EditorBackend.ROOT;
    static EditorBackend get() { return backend; }
    static void installForInstrumentation(EditorBackend fixture) {
        backend = fixture == null ? EditorBackend.ROOT : fixture;
    }
}
