package com.forgemind.bridgeprobe;

import android.app.Activity;
import android.content.*;
import android.os.*;
import com.forgemind.termuxbridge.api.ITermuxBridge;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Synthetic emulator client. This test-only APK is never a release artifact. */
public class ProbeActivity extends Activity {
    private boolean bound;
    private final Binder ptyLifetime = new Binder();
    private ParcelFileDescriptor heldPty;
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder service) {
            new Thread(() -> {
                JSONObject out = new JSONObject();
                try {
                    ITermuxBridge bridge = ITermuxBridge.Stub.asInterface(service);
                    String action = getIntent().getStringExtra("action");
                    if ("holdPty".equals(action)) {
                        Bundle pty = new Bundle();
                        pty.putStringArray("argv",new String[]{"/data/data/com.termux/files/usr/bin/bash","--noprofile","--norc","-i"});
                        pty.putBinder("lease",ptyLifetime);
                        Bundle opened=bridge.openPty(pty);
                        heldPty=opened.getParcelable("descriptor");
                        save(new JSONObject().put("ok",true).put("pid",opened.getInt("pid")).put("id",opened.getString("id")));
                        return; // Keep the bound client and foreground PTY alive for the removal test.
                    }
                    if ("suite".equals(action)) {
                        ProbeSuite suite = new ProbeSuite(bridge);
                        try { out = suite.run(); out.put("ok",true); }
                        catch(Throwable failure) { out=suite.checks; out.put("ok",false); out.put("error",failure.toString()); }
                        save(out);
                        runOnUiThread(() -> { if (bound) { unbindService(connection); bound=false; } finish(); });
                        return;
                    }
                    Bundle request = new Bundle();
                    request.putString("command", getIntent().getStringExtra("command"));
                    request.putLong("timeoutMs", 10000);
                    if (action != null && action.startsWith("task:")) {
                        request.putString("action", action.substring(5));
                        request.putString("id", getIntent().getStringExtra("id"));
                    }
                    Bundle result = action != null && action.startsWith("task:") ? bridge.tasks(request) :
                        "exec".equals(action) ? bridge.exec(request) :
                        "startService".equals(action) ? bridge.startService(request) : bridge.info();
                    for (String key : result.keySet()) out.put(key, result.get(key));
                    out.put("ok", true);
                } catch (Exception e) {
                    try { out.put("ok", false); out.put("error", e.getClass().getSimpleName() + ": " + e.getMessage()); } catch (Exception ignored) { }
                }
                save(out);
                runOnUiThread(() -> { if (bound) { unbindService(connection); bound = false; } finish(); });
            }, "bridge-probe").start();
        }
        public void onServiceDisconnected(ComponentName name) { }
    };
    private void save(JSONObject result) {
        try (FileOutputStream stream = new FileOutputStream(new File(getFilesDir(), "result.json"))) {
            stream.write(result.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { android.util.Log.e("BridgeProbe", "Cannot write result", e); }
    }
    public void onCreate(Bundle state) {
        super.onCreate(state);
        new File(getFilesDir(), "result.json").delete();
        try {
            if (!"ranchu".equals(Build.HARDWARE) && !"goldfish".equals(Build.HARDWARE))
                throw new IllegalStateException("This test client runs only on an emulator");
            bound = bindService(new Intent().setClassName("com.forgemind.termuxbridge", "com.forgemind.termuxbridge.TermuxBridgeService"), connection, BIND_AUTO_CREATE);
            if (!bound) throw new IllegalStateException("Bridge not bound");
        } catch (Exception e) {
            try { save(new JSONObject().put("ok", false).put("error", e.toString())); } catch (Exception ignored) { }
            finish();
        }
    }
    @Override protected void onDestroy() {
        if (heldPty != null) try { heldPty.close(); } catch(Exception ignored) {}
        if (bound) { unbindService(connection); bound=false; }
        super.onDestroy();
    }
}
