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
    const val ROOT = "/data/data/com.termux/files/home/.local/share/forgemind-helper"
    suspend fun prepare(context: Context) = withContext(Dispatchers.IO) {
        val config = TermuxDaemonApi.config(context)
        val api = TermuxDaemonApi(config, readTimeoutMs = 2000)
        if (runCatching { api.info().getString("version") == "1.0.0" }.getOrDefault(false)) return@withContext
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
            mkdir -p '$ROOT/versions/1.0.0'
            if [ -f '$ROOT/token' ] && [ "${'$'}(cat '$ROOT/token')" != '${config.token}' ]; then
                printf 'Existing helper belongs to a different installation. Preserve it and reconnect that installation.\n' >&2; exit 23
            fi
            printf '%s' '${config.token}' > '$ROOT/token'
            printf '%s' '$encoded' | base64 -d > '$ROOT/versions/1.0.0/helper.py.pending'
            printf '%s  %s\n' '$sha' '$ROOT/versions/1.0.0/helper.py.pending' | sha256sum -c - >/dev/null
            python -m py_compile '$ROOT/versions/1.0.0/helper.py.pending'
            mv '$ROOT/versions/1.0.0/helper.py.pending' '$ROOT/versions/1.0.0/helper.py'
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
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("$ROOT/versions/1.0.0/helper.py", "--root", ROOT, "--port", config.port.toString()))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "ForgeMind helper")
        })
        repeat(30) {
            if (runCatching { api.info().getString("version") == "1.0.0" }.getOrDefault(false)) return@withContext
            delay(500)
        }
        error("Helper startup not confirmed. Inspect the ForgeMind helper task in Termux before retrying.")
    }
}
