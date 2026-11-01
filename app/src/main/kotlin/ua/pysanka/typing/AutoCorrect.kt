package ua.pysanka.typing

import java.util.concurrent.ConcurrentHashMap

/**
 * Pysanka: what auto-correction must remember for a moment, in memory only and only while the
 * field is open: the words whose correction was taken back with Backspace (they are left alone
 * after that), and the word the current suggestions were made for (a correction made for an
 * earlier keystroke is never applied).
 */
object AutoCorrect {
    val rejected: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    var suggestedFor: String? = null

    fun reject(word: String) {
        rejected.add(word.lowercase().replace('’', '\'').replace('ʼ', '\''))
    }

    fun clear() {
        rejected.clear()
        suggestedFor = null
    }
}
