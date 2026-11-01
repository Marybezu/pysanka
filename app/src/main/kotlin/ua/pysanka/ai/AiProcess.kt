package ua.pysanka.ai

import android.app.Application
import android.os.Build

/** Pysanka: tells the AI editor's own process (":ai") apart from the keyboard's. */
object AiProcess {
    const val SUFFIX = ":ai"

    fun isAiProcess(): Boolean = name()?.endsWith(SUFFIX) == true

    private fun name(): String? {
        if (Build.VERSION.SDK_INT >= 28) return Application.getProcessName()
        return runCatching {
            java.io.File("/proc/self/cmdline").readText().trimEnd(Char(0)).substringBefore(Char(0))
        }.getOrNull()
    }
}
