package ua.pysanka.clipboard

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.jetpref.datastore.model.collectAsState
import kotlinx.coroutines.launch
import org.florisboard.lib.compose.stringRes

/**
 * Pysanka: the "Screenshots into the history" switch. Turning it on asks for access to photos
 * first; it is on only while both the setting and the permission are there.
 */
@Composable
fun ScreenshotsSetting() {
    val prefs by FlorisPreferenceStore
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val enabled by prefs.pysankaScreenshots.enabled.collectAsState()
    val historyOn by prefs.clipboard.historyEnabled.collectAsState()
    var granted by remember { mutableStateOf(Screenshots.hasPermission(context)) }
    // The owner may grant or take back the permission in the system settings meanwhile
    DisposableEffect(lifecycle) {
        val watcher = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = Screenshots.hasPermission(context)
                Screenshots.refresh()
            }
        }
        lifecycle.addObserver(watcher)
        onDispose { lifecycle.removeObserver(watcher) }
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (ok) {
            scope.launch { Screenshots.turnOn() }
        } else {
            Toast.makeText(context, R.string.pysanka__screenshots__denied, Toast.LENGTH_LONG).show()
        }
    }
    val on = enabled && granted
    val toggle = {
        if (on) {
            scope.launch { Screenshots.turnOff() }
        } else if (granted) {
            scope.launch { Screenshots.turnOn() }
        } else {
            ask.launch(Screenshots.permission)
        }
    }
    ListItem(
        modifier = Modifier
            .alpha(if (historyOn) 1f else 0.38f)
            .clickable(enabled = historyOn, role = Role.Switch, onClick = { toggle() }),
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        headlineContent = { Text(stringRes(R.string.pysanka__screenshots__label)) },
        supportingContent = {
            Text(
                if (enabled && !granted) stringRes(R.string.pysanka__screenshots__no_access)
                else stringRes(R.string.pysanka__screenshots__summary),
            )
        },
        trailingContent = { Switch(checked = on, onCheckedChange = null, enabled = historyOn) },
    )
}
