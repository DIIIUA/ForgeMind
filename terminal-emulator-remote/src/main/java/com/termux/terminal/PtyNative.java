package com.termux.terminal;

/** Narrow public entry to unchanged upstream JNI. Used only in the bridge process. */
public final class PtyNative {
    private PtyNative() { }
    public static int create(String executable, String cwd, String[] argv, String[] environment, int[] pid, int rows, int columns) {
        return JNI.createSubprocess(executable, cwd, argv, environment, pid, rows, columns, 0, 0);
    }
    public static void resize(int descriptor, int rows, int columns) { JNI.setPtyWindowSize(descriptor, rows, columns, 0, 0); }
    public static int waitFor(int pid) { return JNI.waitFor(pid); }
}
