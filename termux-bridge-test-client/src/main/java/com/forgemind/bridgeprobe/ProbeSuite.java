package com.forgemind.bridgeprobe;

import android.os.*;
import com.forgemind.termuxbridge.api.ITermuxBridge;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Runs only from the synthetic emulator client, never shipped with ForgeMind. */
final class ProbeSuite {
    final ITermuxBridge bridge;
    final JSONObject checks = new JSONObject();
    final String root = "/data/data/com.termux/files/home/bridge-probe-" + UUID.randomUUID();
    ProbeSuite(ITermuxBridge b) { bridge = b; }
    static Bundle request(String action) { Bundle b = new Bundle(); b.putString("action",action); return b; }
    static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    void record(String name, Object result) throws Exception { checks.put(name,result); }
    Bundle files(String action, String path) throws Exception {
        Bundle b=request(action); b.putString("path",path); return bridge.files(b);
    }
    void denied(String name, RunnableCall call) throws Exception {
        try { call.run(); throw new AssertionError(name + " was allowed"); }
        catch (SecurityException | IllegalArgumentException | IllegalStateException expected) { record(name,true); }
    }
    interface RunnableCall { void run() throws Exception; }
    Bundle task(String id, String action) throws Exception {
        Bundle r=request(action); r.putString("id",id); return bridge.tasks(r);
    }
    Bundle start(String id, String command) throws Exception {
        Bundle r=request("start"); r.putString("id",id); r.putString("command",command); r.putString("cwd",root);
        return bridge.tasks(r);
    }
    Bundle done(String id) throws Exception {
        for(int i=0;i<120;i++) {
            Bundle r=task(id,"status");
            if (!"RUNNING".equals(r.getString("state"))) return r;
            Thread.sleep(100);
        }
        throw new AssertionError("Task did not finish: " + id);
    }
    Bundle exec(String command) throws Exception {
        Bundle r=new Bundle(); r.putString("command",command); r.putString("cwd",root); r.putLong("timeoutMs",10000);
        Bundle result=bridge.exec(r); require(result.getInt("exitCode",-1)==0, "exec: "+result.getString("stderr")); return result;
    }
    void waitText(StringBuffer output, String marker) throws Exception {
        for(int i=0;i<100;i++) { if(output.toString().contains(marker)) return; Thread.sleep(50); }
        throw new AssertionError("PTY marker missing " + marker + ": "+output.toString());
    }
    JSONObject run() throws Exception {
        files("mkdir", root);
        String filename=root+"/file.txt";
        try {
            try (OutputStream out=new ParcelFileDescriptor.AutoCloseOutputStream(bridge.openFile(filename,"wt"))) {
                out.write("bridge-file-✓".getBytes(StandardCharsets.UTF_8));
            }
            try (InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(bridge.openFile(filename,"r"))) {
                ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] b=new byte[100]; int n;
                while((n=in.read(b))>=0) out.write(b,0,n);
                require("bridge-file-✓".equals(out.toString("UTF-8")), "file bytes changed");
            }
            record("file_roundtrip", true);
            require(files("list",root).getParcelableArrayList("entries").size()==1,"list");
            require(files("stat",filename).getLong("size")>0,"stat");
            denied("delete_requires_confirmation", () -> files("delete",filename));
            for (String dir:new String[]{"bin","lib","libexec","etc"}) {
                denied("protected_"+dir, () -> { try(ParcelFileDescriptor fd=bridge.openFile("/data/data/com.termux/files/usr/"+dir+"/probe","wt")) {} });
            }
            for(String alias:new String[]{"/data/data/","/data/user/0/"}) {
                denied("bridge_private_denied_"+alias, () -> bridge.openFile(alias+"com.forgemind.termuxbridge/files/api-denied-probe","wt"));
                denied("protected_prefix_alias_"+alias, () -> bridge.openFile(alias+"com.termux/files/usr/bin/api-denied-probe","wt"));
            }
            denied("missing_file_reports_error", () -> bridge.openFile(root+"/missing","r"));
            Bundle move=request("move"); move.putString("path",filename); move.putString("destination",root+"/moved.txt");
            bridge.files(move); require(files("stat",root+"/moved.txt").getBoolean("exists"),"move");
            record("file_list_stat_move", true);
            for(int i=0;i<10;i++) exec("true");
            record("short_command_completion_race",true);
            exec("ln -s /data/data/com.termux/files/usr/bin '"+root+"/protected-link'");
            denied("symlink_cannot_bypass_protection", () -> bridge.openFile(root+"/protected-link/probe","wt"));
            exec("rm '"+root+"/protected-link'");
            String id=UUID.randomUUID().toString();
            String command="printf 'one\\n' >> counter; printf 'TASK_OK\\n'; printf 'ERR_OK\\n' >&2; exit 7";
            start(id,command);
            Bundle result=done(id);
            require(result.getInt("exitCode",-1)==7 && result.getString("stdout","").contains("TASK_OK")
                && result.getString("stderr","").contains("ERR_OK"),"task result: "+result);
            start(id,command); // same ID is a read of the original task
            require(exec("wc -l < counter").getString("stdout","").trim().equals("1"),"task executed twice");
            record("task_output_exit_idempotency", true);
            denied("request_id_cannot_change_command", () -> start(id,"echo second"));
            String longId=UUID.randomUUID().toString(); start(longId,"sleep 60");
            require("CANCELLED".equals(task(longId,"cancel").getString("state")),"cancel");
            record("task_cancel",true);
            Bundle homeRequest=new Bundle(); homeRequest.putString("command","pwd"); homeRequest.putString("cwd","~");
            Bundle homeResult=bridge.exec(homeRequest);
            require(homeResult.getInt("exitCode",-1)==0 && homeResult.getString("stdout","").trim().equals("/data/data/com.termux/files/home"),"HOME cwd");
            record("command_tilde_cwd",true);
            String changedId=UUID.randomUUID().toString(); start(changedId,"sleep 60");
            String agent=bridge.sessions(request("agent")).getString("name");
            String target="'="+agent+":task-"+changedId+"'";
            try {
                exec("tmux set-option -p -t "+target+" @forgemind-task changed");
                denied("cancel_does_not_claim_success_after_ownership_change", () -> task(changedId,"cancel"));
            } finally {
                exec("tmux set-option -p -t "+target+" @forgemind-task "+changedId);
                task(changedId,"cancel");
            }
            try (java.net.ServerSocket server=new java.net.ServerSocket(0,4,java.net.InetAddress.getByName("127.0.0.1"))) {
                Bundle service=new Bundle(); service.putInt("port",server.getLocalPort());
                service.putString("command","printf unexpected > '"+root+"/unexpected-service-start'");
                Bundle ensure=new Bundle(); ArrayList<Bundle> list=new ArrayList<>(); list.add(service); ensure.putParcelableArrayList("services",list);
                Bundle health=bridge.ensureServices(ensure).<Bundle>getParcelableArrayList("services").get(0);
                require(health.getBoolean("ready") && !health.getBoolean("started"),"Existing service was restarted");
                require(!files("stat",root+"/unexpected-service-start").getBoolean("exists"),"Service command unexpectedly ran");
                record("ensure_services_preserves_running_service",true);
            }
            Bundle first=bridge.sessions(request("create"));
            require(first.getInt("number")>=2, "session starts at 1");
            first.putString("action","keep"); first.putBoolean("keep",true); bridge.sessions(first);
            first.putString("action","replace"); Bundle second=bridge.sessions(first);
            ArrayList<Bundle> sessions=bridge.sessions(request("list")).getParcelableArrayList("sessions");
            require(sessions.stream().anyMatch(b -> first.getString("name").equals(b.getString("name"))),"kept session disappeared");
            Bundle forged=new Bundle(first); forged.putString("action","close"); forged.putString("name","foreign-server");
            denied("foreign_session_rejected", () -> bridge.sessions(forged));
            record("managed_keep_replace_ownership",true);
            for(Bundle s:new Bundle[]{first,second}) { s.putString("action","close"); bridge.sessions(s); }
            Bundle fresh=bridge.sessions(request("create"));
            // Other fixture/user sessions may exist; exact numbering reset is verified by pure policy tests.
            record("managed_fresh_number",fresh.getInt("number"));
            fresh.putString("action","close"); bridge.sessions(fresh);
            Bundle pty=new Bundle();
            pty.putStringArray("argv",new String[]{"/data/data/com.termux/files/usr/bin/bash","--noprofile","--norc","-i"});
            pty.putString("cwd",root); pty.putInt("rows",24); pty.putInt("columns",80);
            Binder lease=new Binder(); pty.putBinder("lease",lease);
            Bundle opened=bridge.openPty(pty);
            String handle=opened.getString("id");
            ParcelFileDescriptor master=opened.getParcelable("descriptor");
            StringBuffer output=new StringBuffer();
            InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(master.getFileDescriptor()));
            Thread reader=new Thread(() -> {
                byte[] bytes=new byte[4096];
                try { int n; while((n=in.read(bytes))>0) output.append(new String(bytes,0,n,StandardCharsets.UTF_8)); }
                catch(IOException ignored) {}
            }); reader.setDaemon(true); reader.start();
            try (OutputStream out=new ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(master.getFileDescriptor()))) {
                out.write("stty -echo; printf 'PTY_%s\\n' READY\n".getBytes(StandardCharsets.UTF_8)); out.flush();
                waitText(output,"PTY_READY");
                bridge.resizePty(handle,42,111);
                out.write("stty size\n".getBytes(StandardCharsets.UTF_8)); out.flush(); waitText(output,"42 111");
                record("pty_input_resize",true);
                out.write("sleep 60\n".getBytes(StandardCharsets.UTF_8)); out.flush(); Thread.sleep(300);
                out.write(3); out.write("printf 'CTRL_%s\\n' OK\n".getBytes(StandardCharsets.UTF_8)); out.flush();
                waitText(output,"CTRL_OK"); record("pty_ctrl_c",true);
                out.write("sleep 60\n".getBytes(StandardCharsets.UTF_8)); out.flush(); Thread.sleep(300);
                bridge.signalPty(handle,2);
                out.write("printf 'SIGNAL_%s\\n' OK\n".getBytes(StandardCharsets.UTF_8)); out.flush();
                waitText(output,"SIGNAL_OK"); record("pty_signal_foreground_job",true);
                out.write("exit 9\n".getBytes(StandardCharsets.UTF_8)); out.flush();
                Bundle exit=bridge.waitForPty(handle,5000);
                require(!exit.getBoolean("running") && exit.getInt("exitCode",-1)==9,"PTY exit");
                record("pty_wait",true);
            } finally { master.close(); in.close(); bridge.closePty(handle); }
            record("complete",true);
            return checks;
        } finally {
            Bundle remove=request("delete"); remove.putString("path",root); remove.putBoolean("userConfirmed",true);
            try { bridge.files(remove); } catch(Exception ignored) {}
        }
    }
}
