package com.forgemind.termuxbridge.api;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

/** Versioned IPC. Callers must not retry a submitted operation on another route. */
interface ITermuxBridge {
    Bundle info();
    Bundle exec(in Bundle request);
    Bundle startService(in Bundle request);
    ParcelFileDescriptor openFile(String path, String mode);
    Bundle files(in Bundle request);
    Bundle openPty(in Bundle request);
    void resizePty(String id, int rows, int columns);
    void signalPty(String id, int signal);
    Bundle waitForPty(String id, long timeoutMs);
    void closePty(String id);
    Bundle tasks(in Bundle request);
    Bundle sessions(in Bundle request);
    Bundle ensureServices(in Bundle request);
}
