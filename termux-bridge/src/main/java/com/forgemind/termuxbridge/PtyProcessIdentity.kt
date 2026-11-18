package com.forgemind.termuxbridge

data class PtyProcessIdentity(val session: Int, val tty: Int, val foregroundGroup: Int, val start: String) {
    fun signalTarget(pid: Int, expectedStart: String): Int? {
        if (start != expectedStart || session != pid || tty == 0) return null
        return if (foregroundGroup > 0) -foregroundGroup else pid
    }
    companion object {
        fun parse(stat: String): PtyProcessIdentity {
            // comm may contain spaces and parentheses; fields start after its final ')'.
            val fields = stat.substringAfterLast(')').trim().split(Regex("\\s+"))
            require(fields.size >= 20)
            return PtyProcessIdentity(fields[3].toInt(), fields[4].toInt(), fields[5].toInt(), fields[19])
        }
    }
}
