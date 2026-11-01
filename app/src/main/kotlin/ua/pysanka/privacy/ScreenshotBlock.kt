package ua.pysanka.privacy

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import org.florisboard.lib.kotlin.collectIn

/**
 * Pysanka: when the owner turns on "Block screenshots" (Privacy; off by default), this screen
 * cannot be captured or shared, and shows blank in the recent apps. Follows the switch at once.
 */
fun ComponentActivity.followScreenshotBlock() {
    val prefs by FlorisPreferenceStore
    prefs.pysankaOcr.blockScreenshots.asFlow().collectIn(lifecycleScope) { block ->
        if (block) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
