package ua.pysanka.ai

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/**
 * Pysanka: one action of the AI editor on a text of any allowed length.
 *
 * The small model works best on about [PART] characters at a time, so a long text is done in parts:
 * a rewrite part by part, put back in order with the original breaks between them; a summary as
 * notes on each part, then one summary of the notes (and its numbers and names checked against the
 * text, see [FactCheck]).
 *
 * How long a text may be is set by time, not by a fixed number: the phone's own speed is measured
 * on every run (only that number is kept, in a small file; never any text), and the limit is what
 * fits in [BUDGET_SECONDS]. Before the first runs a careful guess is used.
 */
object AiJob {
    /** Characters per part (about 1500–2000 tokens: room for the answer in the model's 8192). */
    const val PART = 3500

    /** The most a [AiAction.Kind.WHOLE] or [AiAction.Kind.ADVICE] action takes at once. */
    const val WHOLE_MAX = 6000

    /** The longest text the editor accepts at all (from the keyboard, sharing or the selection menu). */
    const val HARD_MAX = 60_000

    /** A summary or rewrite should take at most this long (the owner's wish: not more than 2 minutes). */
    const val BUDGET_SECONDS = 110

    data class Output(
        val text: String,
        /** Summary lines removed because their numbers or names are not in the text. */
        val removed: Int,
        /** The summary could not be confirmed against the text at all. */
        val unconfirmed: Boolean,
        val seconds: Int,
        val backend: String,
    )

    /** How long a text [action] may take now (characters), from the speed measured on this phone. */
    fun limit(context: Context, action: AiAction): Int = when (action.kind) {
        AiAction.Kind.WHOLE, AiAction.Kind.ADVICE -> WHOLE_MAX
        AiAction.Kind.SUMMARY -> (Speed.read(context).summary * BUDGET_SECONDS).toInt().coerceIn(PART, HARD_MAX)
        AiAction.Kind.REWRITE -> (Speed.read(context).rewrite * BUDGET_SECONDS).toInt().coerceIn(PART, HARD_MAX)
    }

    /** Seconds [action] will probably take on [chars] characters. */
    fun estimate(context: Context, action: AiAction, chars: Int): Int {
        val s = Speed.read(context)
        val cps = if (action.kind == AiAction.Kind.SUMMARY) s.summary else s.rewrite
        return (chars / cps).toInt().coerceAtLeast(1)
    }

    /**
     * Runs [action] on [text] (already within [limit]). [onPart] tells which part is being done
     * (1-based) of how many. Cancelling the calling coroutine stops between parts; the running
     * part is stopped by [AiEngine.cancel] with the same [run].
     */
    suspend fun run(
        context: Context,
        run: Long,
        action: AiAction,
        text: String,
        target: AiLanguage,
        onPart: (Int, Int) -> Unit,
    ): Output {
        val started = System.currentTimeMillis()
        // The speed is learnt only when the model was already loaded (loading would skew it)
        val warm = AiEngine.loaded
        var removed = 0
        var unconfirmed = false
        val result = when (action.kind) {
            AiAction.Kind.WHOLE, AiAction.Kind.ADVICE -> {
                onPart(1, 1)
                ask(context, run, AiPrompts.system(action, target), text, action.temperature, text)
            }
            AiAction.Kind.REWRITE -> {
                val parts = TextChunks.split(text, PART)
                buildString {
                    for ((i, p) in parts.withIndex()) {
                        onPart(i + 1, parts.size)
                        // Whitespace alone (a part between two breaks) is kept as it is
                        if (p.text.isBlank()) append(p.text) else append(ask(context, run, AiPrompts.system(action, target), p.text, action.temperature, p.text))
                        append(p.after)
                    }
                }
            }
            AiAction.Kind.SUMMARY -> {
                val raw = summarise(context, run, text, onPart)
                val checked = FactCheck.check(text, raw)
                removed = checked.removed
                unconfirmed = checked.allRemoved
                checked.text
            }
        }.let { if (action == AiAction.REDACT) Redact.apply(it) else it }
        val seconds = ((System.currentTimeMillis() - started) / 1000).toInt()
        if (warm) Speed.learn(context, action, text.length, seconds)
        return Output(result.trim(), removed, unconfirmed, seconds, AiEngine.backend)
    }

    /** A summary of a text of any allowed length: notes per part, then the notes summed up. */
    private suspend fun summarise(context: Context, run: Long, text: String, onPart: (Int, Int) -> Unit): String {
        if (text.length <= PART) {
            onPart(1, 1)
            return ask(context, run, AiPrompts.system(AiAction.SUMMARY, AiLanguage.UK), text, 0.2, text)
        }
        val parts = TextChunks.split(text, PART).filter { it.text.isNotBlank() }
        val total = parts.size + 1
        var notes = parts.mapIndexed { i, p ->
            onPart(i + 1, total)
            ask(context, run, AiPrompts.summaryPart(i + 1, parts.size), p.text, 0.2, p.text)
        }.joinToString("\n")
        // Notes on very many parts may themselves be too long for one go: shorten them in parts
        // (at most twice; whatever is left over is cut at a sentence, never looped on)
        var rounds = 0
        while (notes.length > PART * 2 && rounds < 2) {
            val shorter = StringBuilder()
            for (p in TextChunks.split(notes, PART)) {
                shorter.append(ask(context, run, AiPrompts.summaryJoin(), p.text, 0.2, p.text)).append('\n')
            }
            notes = shorter.toString()
            rounds++
        }
        notes = TextChunks.prefix(notes, PART * 2)
        onPart(total, total)
        return ask(context, run, AiPrompts.summaryJoin(), notes, 0.2, notes)
    }

    private suspend fun ask(context: Context, run: Long, system: String, text: String, temperature: Double, original: String): String {
        currentCoroutineContext().ensureActive()
        val answer = AiEngine.generate(context, run, system, AiPrompts.user(text), temperature, maxOutput = 4096)
        // Stopped while this part was being written: what came back is not an answer
        currentCoroutineContext().ensureActive()
        return AiPrompts.clean(answer, original)
    }

    /**
     * The measured speed, characters of the original text per second, for summaries and rewrites.
     * Kept in a tiny file in Pysanka's private storage: two numbers, nothing else.
     */
    internal object Speed {
        data class Value(val summary: Double, val rewrite: Double)

        /** Careful first guesses: about 20 000 characters summed up, 8 000 rewritten in the budget. */
        private val DEFAULT = Value(summary = 180.0, rewrite = 70.0)

        private fun file(context: Context) = File(context.noBackupFilesDir, "ai_speed")

        fun read(context: Context): Value = runCatching {
            val (a, b) = file(context).readText().trim().split(' ').map { it.toDouble() }
            Value(a.coerceIn(10.0, 2000.0), b.coerceIn(5.0, 1000.0))
        }.getOrDefault(DEFAULT)

        fun learn(context: Context, action: AiAction, chars: Int, seconds: Int) {
            // Too short to tell (the model's loading time would weigh too much)
            if (seconds < 5 || chars < 500) return
            if (action.kind != AiAction.Kind.SUMMARY && action.kind != AiAction.Kind.REWRITE) return
            val now = chars.toDouble() / seconds
            val old = read(context)
            // Leaning on the new measure, but not jumping on one odd run
            val v = if (action.kind == AiAction.Kind.SUMMARY) {
                old.copy(summary = old.summary * 0.4 + now * 0.6)
            } else {
                old.copy(rewrite = old.rewrite * 0.4 + now * 0.6)
            }
            runCatching { file(context).writeText("${v.summary} ${v.rewrite}") }
        }
    }
}
