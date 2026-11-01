/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.nlp.latin

import android.content.Context
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.appContext
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.nlp.SpellingProvider
import dev.patrickgold.florisboard.ime.nlp.SpellingResult
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionProvider
import dev.patrickgold.florisboard.ime.nlp.WordSuggestionCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.florisboard.lib.android.readText
import org.florisboard.lib.kotlin.guardedByLock
import ua.pysanka.typing.KeyNeighbours
import ua.pysanka.typing.WordSuggester

class LatinLanguageProvider(context: Context) : SpellingProvider, SuggestionProvider {
    companion object {
        // Default user ID used for all subtypes, unless otherwise specified.
        // See `ime/core/Subtype.kt` Line 210 and 211 for the default usage
        const val ProviderId = "org.florisboard.nlp.providers.latin"
    }

    private val appContext by context.appContext()

    private val prefs by FlorisPreferenceStore

    // Pysanka: a word list per language (assets/ime/dict/<language>.json), loaded when a language
    // is first used; nothing is learnt or written
    private val suggesters = guardedByLock { mutableMapOf<String, WordSuggester?>() }
    private val wordDataSerializer = MapSerializer(String.serializer(), Int.serializer())

    private suspend fun suggester(subtype: Subtype): WordSuggester? {
        val lang = subtype.primaryLocale.language
        return suggesters.withLock { map ->
            map.getOrPut(lang) {
                runCatching {
                    val raw = appContext.assets.readText("ime/dict/$lang.json")
                    WordSuggester(Json.decodeFromString(wordDataSerializer, raw), KeyNeighbours.forLanguage(lang))
                }.getOrNull()
            }
        }
    }

    override val providerId = ProviderId

    override suspend fun create() {
        // Here we initialize our provider, set up all things which are not language dependent.
    }

    override suspend fun preload(subtype: Subtype) = withContext(Dispatchers.IO) {
        suggester(subtype)
        Unit
    }

    override suspend fun spell(
        subtype: Subtype,
        word: String,
        precedingWords: List<String>,
        followingWords: List<String>,
        maxSuggestionCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): SpellingResult {
        val dict = suggester(subtype) ?: return SpellingResult.unspecified()
        if (word.isBlank() || dict.contains(word)) return SpellingResult.validWord()
        val options = dict.suggest(word, maxSuggestionCount).map { it.word }.filter { !it.equals(word, ignoreCase = true) }
        return if (options.isEmpty()) SpellingResult.unspecified() else SpellingResult.typo(options.toTypedArray())
    }

    override suspend fun suggest(
        subtype: Subtype,
        content: EditorContent,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): List<SuggestionCandidate> {
        val word = content.composingText
        if (word.isEmpty()) return emptyList()
        val dict = suggester(subtype) ?: return emptyList()
        val autoCorrect = prefs.correction.pysankaAutoCorrect.get()
        return dict.suggest(word, maxCandidateCount).map {
            WordSuggestionCandidate(
                text = it.word,
                confidence = if (it.autoCorrect) 0.9 else 0.5,
                isEligibleForAutoCommit = autoCorrect && it.autoCorrect,
                sourceProvider = this@LatinLanguageProvider,
            )
        }
    }

    override suspend fun notifySuggestionAccepted(subtype: Subtype, candidate: SuggestionCandidate) {
        // Pysanka: nothing is learnt or logged from what is typed
    }

    override suspend fun notifySuggestionReverted(subtype: Subtype, candidate: SuggestionCandidate) {
    }

    override suspend fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean {
        return false
    }

    override suspend fun getListOfWords(subtype: Subtype): List<String> {
        return suggester(subtype)?.words() ?: emptyList()
    }

    override suspend fun getFrequencyForWord(subtype: Subtype, word: String): Double {
        return (suggester(subtype)?.frequency(word) ?: 0) / 255.0
    }

    override suspend fun destroy() {
        // Here we have the chance to de-allocate memory and finish our work. However this might never be called if
        // the app process is killed (which will most likely always be the case).
    }
}
