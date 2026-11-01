package ua.pysanka.ai

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Pysanka: the AI for the panel inside the keyboard. Runs in the editor's process (":ai"), so the
 * model never weighs on the keyboard; the keyboard binds to it while the panel is open and unbinds
 * when it closes, and then the model is freed (unless an editor screen still uses it).
 *
 * Not exported: only Pysanka itself can bind. The text comes in a message and the answer goes back
 * in one; nothing is written anywhere (as in [AiEditorActivity]).
 */
class AiService : Service() {
    companion object {
        // From the keyboard
        const val WARM = 1
        const val RUN = 2
        const val STOP = 3

        // To the keyboard
        const val LOADED = 10
        const val PART = 11
        const val DONE = 12
        const val FAIL = 13

        const val KEY_TOKEN = "token"
        const val KEY_ACTION = "action"
        const val KEY_TARGET = "target"
        const val KEY_TEXT = "text"
        const val KEY_PART = "part"
        const val KEY_PARTS = "parts"
        const val KEY_REMOVED = "removed"
        const val KEY_UNCONFIRMED = "unconfirmed"
        const val KEY_SECONDS = "seconds"
        const val KEY_BACKEND = "backend"
        const val KEY_REASON = "reason"
        /** In every message: whether the model is in memory ("working" rather than "loading"). */
        const val KEY_LOADED = "loaded"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var run = 0L
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { handle(it); true })

    override fun onCreate() {
        super.onCreate()
        AiEngine.screenOpened()
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    private fun handle(msg: Message) {
        val reply = msg.replyTo ?: return
        when (msg.what) {
            // Loading the model while the person chooses what to do
            WARM -> scope.launch {
                if (runCatching { AiEngine.warmUp(this@AiService) }.isSuccess) send(reply, LOADED, 0)
            }
            RUN -> start(reply, msg.data)
            STOP -> stop()
        }
    }

    private fun start(reply: Messenger, data: Bundle) {
        stop()
        val token = data.getLong(KEY_TOKEN)
        val action = AiAction.entries.firstOrNull { it.name == data.getString(KEY_ACTION) } ?: return
        val target = AiLanguage.entries.firstOrNull { it.name == data.getString(KEY_TARGET) } ?: AiLanguage.EN
        val text = data.getString(KEY_TEXT).orEmpty()
        // The keyboard checks this too; a text over the limit is never started here
        if (text.isBlank() || text.length > AiJob.limit(this, action)) {
            send(reply, FAIL, token) { putString(KEY_REASON, "length") }
            return
        }
        val thisRun = AiEngine.newRun()
        run = thisRun
        job = scope.launch {
            try {
                val out = AiJob.run(this@AiService, thisRun, action, text, target) { part, parts ->
                    send(reply, PART, token) {
                        putInt(KEY_PART, part)
                        putInt(KEY_PARTS, parts)
                    }
                }
                if (out.text.isBlank()) {
                    send(reply, FAIL, token) { putString(KEY_REASON, "empty") }
                } else {
                    send(reply, DONE, token) {
                        putString(KEY_TEXT, out.text)
                        putInt(KEY_REMOVED, out.removed)
                        putBoolean(KEY_UNCONFIRMED, out.unconfirmed)
                        putInt(KEY_SECONDS, out.seconds)
                        putString(KEY_BACKEND, out.backend)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Only the kind of error, so it can be reported
                send(reply, FAIL, token) { putString(KEY_REASON, e.javaClass.simpleName) }
            } finally {
                AiEngine.endRun(thisRun)
            }
        }
    }

    private fun stop() {
        if (run != 0L) AiEngine.cancel(run)
        job?.cancel()
        job = null
        run = 0L
    }

    private fun send(to: Messenger, what: Int, token: Long, fill: Bundle.() -> Unit = {}) {
        val msg = Message.obtain(null, what)
        msg.data = Bundle().apply {
            putLong(KEY_TOKEN, token)
            putBoolean(KEY_LOADED, AiEngine.loaded)
            fill()
        }
        // The keyboard is gone (its process ended): nothing to tell
        runCatching { to.send(msg) }
    }

    override fun onDestroy() {
        stop()
        scope.cancel()
        // The last user of the model gone: free its memory
        if (AiEngine.screenClosed()) {
            CoroutineScope(Dispatchers.Default).launch { if (AiEngine.screenClosedStill()) AiEngine.release() }
        }
        super.onDestroy()
    }
}
