package ua.pysanka.ocr

/**
 * Pysanka: text chosen on the "text from a photo" screen, waiting for the keyboard to come back to
 * the field it was opened from. Kept in memory only, for at most two minutes.
 */
object PendingInsert {
    private const val MAX_AGE_MS = 2 * 60 * 1000L

    @Volatile
    private var text: String? = null
    @Volatile
    private var at = 0L

    fun set(value: String) {
        text = value
        at = System.currentTimeMillis()
    }

    @Synchronized
    fun take(): String? {
        val t = text
        text = null
        return t?.takeIf { System.currentTimeMillis() - at < MAX_AGE_MS }
    }
}

/** Pysanka: opens "text from a photo / the camera" from the keyboard (the keyboard hides meanwhile). */
fun openTextFromImage(source: String) {
    dev.patrickgold.florisboard.FlorisImeService.pysankaLaunch { TextFromImageActivity.intent(it, source) }
}
