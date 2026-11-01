package ua.pysanka.settings

import android.content.Context
import android.content.res.Configuration
import dev.patrickgold.florisboard.lib.FlorisLocale

/**
 * Pysanka: a context in the app language chosen in the settings ("auto" or nothing: the system's),
 * for Pysanka's own screens outside the settings (text from a photo, the AI editor).
 */
object AppLanguage {
    fun context(base: Context, tag: String?): Context {
        if (tag.isNullOrBlank() || tag == "auto") return base
        val config = Configuration(base.resources.configuration)
        config.setLocale(FlorisLocale.fromTag(tag).base)
        return base.createConfigurationContext(config)
    }
}
