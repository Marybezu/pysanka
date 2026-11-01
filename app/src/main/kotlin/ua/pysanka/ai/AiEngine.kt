package ua.pysanka.ai

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Pysanka: Gemma 4 on the phone through LiteRT-LM (as in Tarilka). Runs only in the editor's own
 * process (":ai"), so the 2.6 GB model never weighs on the keyboard. Nothing leaves the phone and
 * nothing is stored: the text lives only in this process while the editor is open.
 */
object AiEngine {
    private var engine: Engine? = null
    private var loadedPath: String? = null

    /** One user of the native engine at a time: loading, writing and releasing never overlap. */
    private val mutex = Mutex()

    /** "GPU" or "CPU": where the model ended up. */
    @Volatile
    var backend: String = "?"
        private set

    /** Whether the model is in memory (shown as "working" rather than "loading"). */
    var loaded by mutableStateOf(false)
        private set

    /** The conversation writing now, so it can be stopped. */
    @Volatile
    private var running: com.google.ai.edge.litertlm.Conversation? = null

    private val runningLock = Any()

    /** Editor screens open now; the model is stopped and freed only when the last one closes. */
    private val screens = java.util.concurrent.atomic.AtomicInteger(0)

    fun screenOpened() {
        screens.incrementAndGet()
    }

    /** Returns true if no editor screen is left (then stop and free the model). */
    fun screenClosed(): Boolean = screens.updateAndGet { maxOf(0, it - 1) } == 0

    /** Still no editor screen (one may have opened while a text was being stopped). */
    fun screenClosedStill(): Boolean = screens.get() == 0

    /** Counts "Stop" presses: an answer asked for before the last one is not started (or is dropped). */
    private val stops = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * Stops the text being written now, if any (the native call does not stop by itself), and any
     * answer already asked for but still waiting for the model to load.
     */
    fun cancel() {
        stops.incrementAndGet()
        synchronized(runningLock) { runCatching { running?.cancelProcess() } }
    }

    /** The model's own cache: not the app cache, which the keyboard empties when it starts. */
    fun cacheDir(context: Context) = java.io.File(context.noBackupFilesDir, "ai_cache").apply { mkdirs() }

    private fun engineLocked(context: Context): Engine {
        val path = ModelStore(context).modelFile()?.absolutePath ?: error("Model not installed")
        engine?.takeIf { loadedPath == path }?.let { return it }
        engine?.close()
        engine = null
        // The graphics processor is much faster; if it cannot run the model, the main processor can
        val created = runCatching { create(context, path, gpu = true).also { backend = "GPU" } }
            .getOrElse { create(context, path, gpu = false).also { backend = "CPU" } }
        engine = created
        loadedPath = path
        loaded = true
        return created
    }

    private fun create(context: Context, path: String, gpu: Boolean): Engine {
        val config = EngineConfig(
            modelPath = path,
            backend = if (gpu) Backend.GPU() else Backend.CPU(),
            maxNumTokens = 8192,
            cacheDir = cacheDir(context).absolutePath,
        )
        return Engine(config).also { it.initialize() }
    }

    /** Loads the model now (the first time takes a while), so the first action does not wait for it. */
    suspend fun warmUp(context: Context) = mutex.withLock {
        withContext(Dispatchers.Default) { engineLocked(context.applicationContext) }
        Unit
    }

    /**
     * One answer of the model: [system] instructions, [user] message. Returns the raw answer (the
     * caller cleans it). Stopped by [cancel]; the caller checks for that.
     */
    suspend fun generate(context: Context, system: String, user: String, temperature: Double, maxOutput: Int): String {
        val asked = stops.get()
        return mutex.withLock { withContext(Dispatchers.Default) {
            // Stopped while waiting for the model (or for another answer): do not start
            if (stops.get() != asked) throw kotlinx.coroutines.CancellationException("stopped")
            val e = engineLocked(context.applicationContext)
            if (stops.get() != asked) throw kotlinx.coroutines.CancellationException("stopped")
            e.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(system),
                    samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = temperature),
                    // No hidden "thinking": it would use up the output before the text is written
                    thinkingConfig = ThinkingConfig(enableThinking = false),
                    maxOutputToken = maxOutput,
                ),
            ).use { conv ->
                synchronized(runningLock) { running = conv }
                try {
                    if (stops.get() != asked) throw kotlinx.coroutines.CancellationException("stopped")
                    conv.sendMessage(user).toString()
                } finally {
                    // Before the conversation is closed, so cancel() never reaches a closed one
                    synchronized(runningLock) { running = null }
                }
            }
        } }
    }

    /** Frees the model's memory; waits for a running action to finish first. */
    suspend fun release() = mutex.withLock {
        // A new editor screen opened meanwhile: it keeps the model
        if (screens.get() > 0) return@withLock
        withContext(Dispatchers.Default) {
            engine?.close()
            engine = null
            loadedPath = null
            loaded = false
        }
    }
}
