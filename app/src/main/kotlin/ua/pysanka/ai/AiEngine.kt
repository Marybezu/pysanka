package ua.pysanka.ai

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
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

    val isLoaded: Boolean get() = engine != null

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
        return created
    }

    private fun create(context: Context, path: String, gpu: Boolean): Engine {
        val config = EngineConfig(
            modelPath = path,
            backend = if (gpu) Backend.GPU() else Backend.CPU(),
            maxNumTokens = 8192,
            cacheDir = context.cacheDir.absolutePath,
        )
        return Engine(config).also { it.initialize() }
    }

    /** Loads the model now (the first time takes a while), so the first action does not wait for it. */
    suspend fun warmUp(context: Context) = mutex.withLock {
        withContext(Dispatchers.Default) { engineLocked(context.applicationContext) }
        Unit
    }

    /** Runs [action] on [text]; [target] is the language for translation. Returns only the new text. */
    suspend fun run(context: Context, action: AiAction, text: String, target: AiLanguage): String = mutex.withLock {
        withContext(Dispatchers.Default) {
            val e = engineLocked(context.applicationContext)
            e.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(AiPrompts.system(action, target)),
                    samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = action.temperature),
                    // No hidden "thinking": it would use up the output before the text is written
                    thinkingConfig = ThinkingConfig(enableThinking = false),
                    maxOutputToken = 2048,
                ),
            ).use { conv -> AiPrompts.clean(conv.sendMessage(AiPrompts.user(action, text, target)).toString()) }
        }
    }

    /** Frees the model's memory; waits for a running action to finish first. */
    suspend fun release() = mutex.withLock {
        withContext(Dispatchers.Default) {
            engine?.close()
            engine = null
            loadedPath = null
        }
    }
}
