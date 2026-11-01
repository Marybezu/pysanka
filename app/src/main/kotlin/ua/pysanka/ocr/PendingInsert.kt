package ua.pysanka.ocr

/**
 * Pysanka: text chosen on the "text from a photo" screen, waiting for the keyboard to come back to
 * the field it was opened from. Kept in memory only.
 *
 * It goes only into that same field (same app, same field id), never into a password field, and
 * only within a minute; anything else drops it. Opening the screen again clears what was waiting.
 */
object PendingInsert {
    private const val MAX_AGE_MS = 60 * 1000L

    private data class Target(val packageName: String?, val fieldId: Int)

    private var target: Target? = null
    private var text: String? = null
    private var at = 0L

    /** Called when the screen is opened from the keyboard: remembers the field, clears old text. */
    @Synchronized
    fun opened(packageName: String?, fieldId: Int) {
        target = Target(packageName, fieldId)
        text = null
        fromPrivateField = ua.pysanka.privacy.FieldPrivacy.isPrivate || ua.pysanka.privacy.FieldPrivacy.isPassword
    }

    /** Opened from a password, incognito or no-learning field: its text never goes to the AI. */
    @Volatile
    var fromPrivateField = false
        private set

    /** Whether the screen was opened from a field (so there is somewhere to insert). */
    @Synchronized
    fun hasTarget(): Boolean = target != null

    @Synchronized
    fun set(value: String) {
        if (target == null) return
        text = value
        at = System.currentTimeMillis()
    }

    /** The waiting text if [packageName]/[fieldId] is the field it was meant for; it is cleared either way. */
    @Synchronized
    fun takeFor(packageName: String?, fieldId: Int, isPassword: Boolean, isPrivate: Boolean = false): String? {
        val t = text ?: return null
        val wanted = target
        text = null
        target = null
        val fresh = System.currentTimeMillis() - at < MAX_AGE_MS
        return t.takeIf { fresh && !isPassword && !isPrivate && wanted == Target(packageName, fieldId) }
    }
}

/** Pysanka: opens "text from a photo / the camera" from the keyboard (the keyboard hides meanwhile). */
fun openTextFromImage(source: String) {
    dev.patrickgold.florisboard.FlorisImeService.pysankaLaunch { TextFromImageActivity.intent(it, source) }
}
