package com.termux.terminal;

import android.os.ParcelFileDescriptor;

/** The client owns its descriptor; only the bridge can wait for or signal the remote child. */
public interface RemotePty {
    ParcelFileDescriptor descriptor();
    int pid();
    void resize(int columns, int rows, int cellWidth, int cellHeight);
    /** Called on a waiter thread, never on the UI thread. */
    int waitForExit();
    /** Idempotently detach this PTY, not the underlying persistent tmux session. */
    void close();
}
