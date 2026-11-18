package com.forgemind.app.setup

/** Fixed local installer: no caller-provided shell, login startup files or server restarts. */
object TermuxSetupCommand {
    val script = """
        set -eu
        export LC_ALL=C
        export PATH=/data/data/com.termux/files/usr/bin:/system/bin
        export PREFIX=/data/data/com.termux/files/usr
        export HOME=/data/data/com.termux/files/home
        cd "${'$'}HOME"
        missing=''
        command -v tmux >/dev/null 2>&1 || missing="${'$'}missing tmux"
        command -v sshd >/dev/null 2>&1 || missing="${'$'}missing openssh"
        if [ -n "${'$'}missing" ]; then
            printf 'Installing missing terminal tools:%s\n' "${'$'}missing"
            apt-get update
            plan=${'$'}(apt-get -s --no-upgrade --no-remove install ${'$'}missing) || exit 21
            if printf '%s\n' "${'$'}plan" | grep -Eq '^Inst [^ ]+ \[|^Remv '; then
                printf 'Existing packages would change. Review dependencies in Termux before continuing.\n' >&2
                exit 22
            fi
            DEBIAN_FRONTEND=noninteractive apt-get -y --no-upgrade --no-remove install ${'$'}missing
        fi
        command -v tmux
        command -v sshd
        printf 'FORGEMIND_TOOLS_READY\n'
    """.trimIndent()
}
