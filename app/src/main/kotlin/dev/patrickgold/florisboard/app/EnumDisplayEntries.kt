/*
 * Copyright (C) 2025 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.app

import androidx.compose.runtime.Composable
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.ime.keyboard.IncognitoMode
import dev.patrickgold.florisboard.ime.media.emoji.EmojiSkinTone
import dev.patrickgold.florisboard.ime.text.gestures.SwipeAction
import dev.patrickgold.florisboard.ime.theme.ThemeMode
import dev.patrickgold.jetpref.datastore.ui.ListPreferenceEntry
import dev.patrickgold.jetpref.datastore.ui.listPrefEntries
import org.florisboard.lib.compose.stringRes
import kotlin.reflect.KClass

private const val DEFAULT = ""

// Pysanka: only the choices the settings still offer
private val ENUM_DISPLAY_ENTRIES = mapOf<Pair<KClass<*>, String>, @Composable () -> List<ListPreferenceEntry<*>>>(
    EmojiSkinTone::class to DEFAULT to {
        listPrefEntries {
            entry(
                key = EmojiSkinTone.DEFAULT,
                label = stringRes(
                    R.string.enum__emoji_skin_tone__default,
                    "emoji" to "\uD83D\uDC4B" // 👋
                ),
            )
            entry(
                key = EmojiSkinTone.LIGHT_SKIN_TONE,
                label = stringRes(
                    R.string.enum__emoji_skin_tone__light_skin_tone,
                    "emoji" to "\uD83D\uDC4B\uD83C\uDFFB" // 👋🏻
                ),
            )
            entry(
                key = EmojiSkinTone.MEDIUM_LIGHT_SKIN_TONE,
                label = stringRes(
                    R.string.enum__emoji_skin_tone__medium_light_skin_tone,
                    "emoji" to "\uD83D\uDC4B\uD83C\uDFFC" // 👋🏼
                ),
            )
            entry(
                key = EmojiSkinTone.MEDIUM_SKIN_TONE,
                label = stringRes(
                    R.string.enum__emoji_skin_tone__medium_skin_tone,
                    "emoji" to "\uD83D\uDC4B\uD83C\uDFFD" // 👋🏽
                ),
            )
            entry(
                key = EmojiSkinTone.MEDIUM_DARK_SKIN_TONE,
                label = stringRes(
                    R.string.enum__emoji_skin_tone__medium_dark_skin_tone,
                    "emoji" to "\uD83D\uDC4B\uD83C\uDFFE" // 👋🏾
                ),
            )
            entry(
                key = EmojiSkinTone.DARK_SKIN_TONE,
                label = stringRes(
                    R.string.enum__emoji_skin_tone__dark_skin_tone,
                    "emoji" to "\uD83D\uDC4B\uD83C\uDFFF" // 👋🏿
                ),
            )
        }
    },
    IncognitoMode::class to DEFAULT to {
        listPrefEntries {
            entry(
                key = IncognitoMode.FORCE_OFF,
                label = stringRes(R.string.enum__incognito_mode__force_off),
                description = stringRes(R.string.enum__incognito_mode__force_off__description),
                showDescriptionOnlyIfSelected = true,
            )
            entry(
                key = IncognitoMode.DYNAMIC_ON_OFF,
                label = stringRes(R.string.enum__incognito_mode__dynamic_on_off),
                description = stringRes(R.string.enum__incognito_mode__dynamic_on_off__description),
                showDescriptionOnlyIfSelected = true,
            )
            entry(
                key = IncognitoMode.FORCE_ON,
                label = stringRes(R.string.enum__incognito_mode__force_on),
                description = stringRes(R.string.enum__incognito_mode__force_on__description),
                showDescriptionOnlyIfSelected = true,
            )
        }
    },
    SwipeAction::class to "general" to {
        listPrefEntries {
            entry(
                key = SwipeAction.NO_ACTION,
                label = stringRes(R.string.enum__swipe_action__no_action),
            )
            entry(
                key = SwipeAction.CYCLE_TO_PREVIOUS_KEYBOARD_MODE,
                label = stringRes(R.string.enum__swipe_action__cycle_to_previous_keyboard_mode),
            )
            entry(
                key = SwipeAction.CYCLE_TO_NEXT_KEYBOARD_MODE,
                label = stringRes(R.string.enum__swipe_action__cycle_to_next_keyboard_mode),
            )
            entry(
                key = SwipeAction.DELETE_WORD,
                label = stringRes(R.string.enum__swipe_action__delete_word),
            )
            entry(
                key = SwipeAction.HIDE_KEYBOARD,
                label = stringRes(R.string.enum__swipe_action__hide_keyboard),
            )
            entry(
                key = SwipeAction.INSERT_SPACE,
                label = stringRes(R.string.enum__swipe_action__insert_space),
            )
            entry(
                key = SwipeAction.MOVE_CURSOR_UP,
                label = stringRes(R.string.enum__swipe_action__move_cursor_up),
            )
            entry(
                key = SwipeAction.MOVE_CURSOR_DOWN,
                label = stringRes(R.string.enum__swipe_action__move_cursor_down),
            )
            entry(
                key = SwipeAction.MOVE_CURSOR_LEFT,
                label = stringRes(R.string.enum__swipe_action__move_cursor_left),
            )
            entry(
                key = SwipeAction.MOVE_CURSOR_RIGHT,
                label = stringRes(R.string.enum__swipe_action__move_cursor_right),
            )
            entry(
                key = SwipeAction.MOVE_CURSOR_START_OF_LINE,
                label = stringRes(R.string.enum__swipe_action__move_cursor_start_of_line),
            )
            entry(
                key = SwipeAction.MOVE_CURSOR_END_OF_LINE,
                label = stringRes(R.string.enum__swipe_action__move_cursor_end_of_line),
            )
            entry(
                key = SwipeAction.MOVE_CURSOR_START_OF_PAGE,
                label = stringRes(R.string.enum__swipe_action__move_cursor_start_of_page),
            )
            entry(
                key = SwipeAction.MOVE_CURSOR_END_OF_PAGE,
                label = stringRes(R.string.enum__swipe_action__move_cursor_end_of_page),
            )
            entry(
                key = SwipeAction.SHIFT,
                label = stringRes(R.string.enum__swipe_action__shift),
            )
            entry(
                key = SwipeAction.REDO,
                label = stringRes(R.string.enum__swipe_action__redo),
            )
            entry(
                key = SwipeAction.UNDO,
                label = stringRes(R.string.enum__swipe_action__undo),
            )
            entry(
                key = SwipeAction.SWITCH_TO_CLIPBOARD_CONTEXT,
                label = stringRes(R.string.enum__swipe_action__switch_to_clipboard_context),
            )
            entry(
                key = SwipeAction.SWITCH_TO_MEDIA_CONTEXT,
                label = stringRes(R.string.enum__swipe_action__switch_to_media_context)
            )
            entry(
                key = SwipeAction.SHOW_INPUT_METHOD_PICKER,
                label = stringRes(R.string.enum__swipe_action__show_input_method_picker),
            )
            entry(
                key = SwipeAction.SHOW_SUBTYPE_PICKER,
                label = "Show subtype picker"
            )
            entry(
                key = SwipeAction.SWITCH_TO_PREV_SUBTYPE,
                label = stringRes(R.string.enum__swipe_action__switch_to_prev_subtype),
            )
            entry(
                key = SwipeAction.SWITCH_TO_NEXT_SUBTYPE,
                label = stringRes(R.string.enum__swipe_action__switch_to_next_subtype),
            )
            entry(
                key = SwipeAction.SWITCH_TO_PREV_KEYBOARD,
                label = stringRes(R.string.enum__swipe_action__switch_to_prev_keyboard),
            )
            entry(
                key = SwipeAction.TOGGLE_SMARTBAR_VISIBILITY,
                label = stringRes(R.string.enum__swipe_action__toggle_smartbar_visibility),
            )
        }
    },
    SwipeAction::class to "deleteSwipe" to {
        listPrefEntries {
            entry(
                key = SwipeAction.NO_ACTION,
                label = stringRes(R.string.enum__swipe_action__no_action),
            )
            entry(
                key = SwipeAction.DELETE_CHARACTERS_PRECISELY,
                label = stringRes(R.string.enum__swipe_action__delete_characters_precisely),
            )
            entry(
                key = SwipeAction.DELETE_WORD,
                label = stringRes(R.string.enum__swipe_action__delete_word),
            )
            entry(
                key = SwipeAction.DELETE_WORDS_PRECISELY,
                label = stringRes(R.string.enum__swipe_action__delete_words_precisely),
            )
            entry(
                key = SwipeAction.SELECT_CHARACTERS_PRECISELY,
                label = stringRes(R.string.enum__swipe_action__select_characters_precisely),
            )
            entry(
                key = SwipeAction.SELECT_WORDS_PRECISELY,
                label = stringRes(R.string.enum__swipe_action__select_words_precisely),
            )
        }
    },
    ThemeMode::class to DEFAULT to {
        listPrefEntries {
            entry(
                key = ThemeMode.ALWAYS_DAY,
                label = stringRes(R.string.enum__theme_mode__always_day),
            )
            entry(
                key = ThemeMode.ALWAYS_NIGHT,
                label = stringRes(R.string.enum__theme_mode__always_night),
            )
            entry(
                key = ThemeMode.FOLLOW_SYSTEM,
                label = stringRes(R.string.enum__theme_mode__follow_system),
            )
            entry(
                key = ThemeMode.FOLLOW_TIME,
                label = stringRes(R.string.enum__theme_mode__follow_time),
            )
        }
    },
)

@Composable
fun <V : Any> enumDisplayEntriesOf(
    enumClass: KClass<V>,
    variant: String = DEFAULT,
): List<ListPreferenceEntry<V>> {
    @Suppress("UNCHECKED_CAST")
    return ENUM_DISPLAY_ENTRIES[enumClass to variant]?.invoke()
        as List<ListPreferenceEntry<V>>
}
