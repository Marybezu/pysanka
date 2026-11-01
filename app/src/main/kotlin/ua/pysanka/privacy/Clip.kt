package ua.pysanka.privacy

import android.content.ClipData
import android.content.Context

/** Pysanka: puts text in the system clipboard (one place for Pysanka's own "Copy" buttons). */
object Clip {
    fun copy(context: Context, text: String) {
        context.getSystemService(android.content.ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("Pysanka", text))
    }
}
