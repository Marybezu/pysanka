package ua.pysanka.ai

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import dev.patrickgold.florisboard.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Pysanka: copying the model in (2.6 GB, a minute or two) runs on its own, not with the settings
 * screen, so turning the phone or leaving the screen does not break it off.
 */
object ModelImport {
    data class Status(
        /** 0..1 while copying; null when not copying. */
        val progress: Float? = null,
        @StringRes val error: Int? = null,
        /** The picked file could not be deleted afterwards (the storage did not allow it). */
        val originalKept: Boolean = false,
        /** Bumped when a copy ends, so the screen reads the model's state again. */
        val finished: Int = 0,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val status: StateFlow<Status>
        field = MutableStateFlow(Status())

    @Synchronized
    fun start(context: Context, uri: Uri, deleteOriginal: Boolean) {
        if (status.value.progress != null) return
        status.value = status.value.copy(progress = 0f, error = null, originalKept = false)
        val store = ModelStore(context.applicationContext)
        scope.launch {
            var error: Int? = null
            var kept = false
            try {
                kept = store.import(uri, deleteOriginal) { p -> status.value = status.value.copy(progress = p) }.originalDeleted == false
            } catch (e: CancellationException) {
                throw e
            } catch (e: ModelStore.WrongFile) {
                error = R.string.pysanka__ai__wrong_file
            } catch (e: ModelStore.NoSpace) {
                error = R.string.pysanka__ai__no_space
            } catch (e: Throwable) {
                error = R.string.pysanka__ai__import_failed
            } finally {
                AiEntry.sync(context.applicationContext)
                status.value = Status(progress = null, error = error, originalKept = kept, finished = status.value.finished + 1)
            }
        }
    }

    /** The "could not delete the downloaded file" note was shown. */
    fun noteShown() {
        status.value = status.value.copy(originalKept = false)
    }

    /** Whether a copy is running now (then leftovers must not be cleaned up). */
    val running: Boolean get() = status.value.progress != null
}
