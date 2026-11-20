package com.forgemind.app.setup

import android.content.Context
import android.content.Intent
import android.util.Base64
import com.forgemind.app.bridge.TermuxDaemonApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.security.MessageDigest

object TermuxHelperSetup {
    const val VERSION = "1.0.1"
    const val ROOT = "/data/data/com.termux/files/home/.local/share/forgemind-helper"

    suspend fun upgradeIfOutdated(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!TermuxDaemonApi.configured(context)) return@withContext false
        if (context.checkSelfPermission("com.termux.permission.RUN_COMMAND") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return@withContext false
        }
        val config = TermuxDaemonApi.config(context)
        val api = TermuxDaemonApi(config, readTimeoutMs = 1500)
        val runningVersion = runCatching { api.info().getString("version") }.getOrNull()
        if (runningVersion == VERSION) return@withContext false
        prepare(context)
        true
    }

    suspend fun prepare(context: Context) = withContext(Dispatchers.IO) {
        val config = TermuxDaemonApi.config(context)
        val api = TermuxDaemonApi(config, readTimeoutMs = 2000)
        if (runCatching { api.info().getString("version") == VERSION }.getOrDefault(false)) return@withContext
        val payload = context.assets.open("termux/forgemind_helper.py").use { it.readBytes() }
        val sha = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        val encoded = Base64.encodeToString(payload, Base64.NO_WRAP)
        val script = """
            set -eu
            export HOME=/data/data/com.termux/files/home
            export PREFIX=/data/data/com.termux/files/usr
            export PATH=${'$'}PREFIX/bin:/system/bin
            export LC_ALL=C
            unset BASH_ENV ENV LD_PRELOAD
            cd "${'$'}HOME"
            missing=''
            command -v python >/dev/null 2>&1 || missing="${'$'}missing python"
            command -v tmux >/dev/null 2>&1 || missing="${'$'}missing tmux"
            if [ -n "${'$'}missing" ]; then
                apt-get update
                plan=${'$'}(apt-get -s --no-upgrade --no-remove install ${'$'}missing)
                if printf '%s\n' "${'$'}plan" | grep -Eq '^Inst [^ ]+ \[|^Remv '; then
                    printf 'Existing packages need changes. Review them in Termux first.\n' >&2; exit 22
                fi
                DEBIAN_FRONTEND=noninteractive apt-get -y --no-upgrade --no-remove install ${'$'}missing
            fi
            umask 077
            mkdir -p '$ROOT/versions/$VERSION'
            if [ -f '$ROOT/token' ] && [ "${'$'}(cat '$ROOT/token')" != '${config.token}' ]; then
                printf 'Existing helper belongs to a different installation. Preserve it and reconnect that installation.\n' >&2; exit 23
            fi
            printf '%s' '${config.token}' > '$ROOT/token'
            printf '%s' '$encoded' | base64 -d > '$ROOT/versions/$VERSION/helper.py.pending'
            printf '%s  %s\n' '$sha' '$ROOT/versions/$VERSION/helper.py.pending' | sha256sum -c - >/dev/null
            python -m py_compile '$ROOT/versions/$VERSION/helper.py.pending'
            mv '$ROOT/versions/$VERSION/helper.py.pending' '$ROOT/versions/$VERSION/helper.py'
            python - <<'PYSTOP'
            import json, os, pathlib, signal, time
            root = pathlib.Path('$ROOT')
            meta = root / 'running.json'
            if meta.is_file():
                try:
                    info = json.loads(meta.read_text(encoding='utf-8'))
                    pid = int(info.get('pid', 0))
                    if pid > 1 and pid != os.getpid():
                        cmd_path = pathlib.Path(f'/proc/{pid}/cmdline')
                        if cmd_path.is_file():
                            cmd = cmd_path.read_bytes()
                            if b'forgemind-helper/versions/' in cmd and b'helper.py' in cmd:
                                os.kill(pid, signal.SIGTERM)
                                for _ in range(40):
                                    if not cmd_path.exists():
                                        break
                                    time.sleep(0.1)
                except Exception:
                    pass
            PYSTOP
            printf 'FORGEMIND_HELPER_INSTALLED\n'
        """.trimIndent()
        val result = TermuxSetupRunner.prepare(context, script)
        check(result.exitCode == 0 && !result.timedOut && result.error == null && result.stdout.contains("FORGEMIND_HELPER_INSTALLED")) {
            if (result.timedOut) "Installation outcome unknown. Inspect Termux before retrying."
            else listOfNotNull(result.error, result.stderr, result.stdout).joinToString("\n").takeLast(1800)
        }
        // A separate Termux app-shell task starts immediately. Hidden terminal sessions
        // are initialized lazily by Termux and may never start until selected in its UI.
        // Never attach to, close, rename, or send input to a user's existing session.
        context.startForegroundService(Intent("com.termux.RUN_COMMAND").apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/python")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("$ROOT/versions/$VERSION/helper.py", "--root", ROOT, "--port", config.port.toString()))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "ForgeMind helper")
        })
        repeat(30) {
            if (runCatching { api.info().getString("version") == VERSION }.getOrDefault(false)) return@withContext
            delay(500)
        }
        error("Helper startup not confirmed. Inspect the ForgeMind helper task in Termux before retrying.")
    }
}
