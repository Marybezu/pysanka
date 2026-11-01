/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
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

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideIn
import androidx.compose.animation.slideOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navDeepLink
import androidx.navigation.toRoute
import dev.patrickgold.florisboard.app.ext.CheckUpdatesScreen
import dev.patrickgold.florisboard.app.ext.ExtensionEditScreen
import dev.patrickgold.florisboard.app.ext.ExtensionExportScreen
import dev.patrickgold.florisboard.app.ext.ExtensionHomeScreen
import dev.patrickgold.florisboard.app.ext.ExtensionImportScreen
import dev.patrickgold.florisboard.app.ext.ExtensionImportScreenType
import dev.patrickgold.florisboard.app.ext.ExtensionListScreen
import dev.patrickgold.florisboard.app.ext.ExtensionListScreenType
import dev.patrickgold.florisboard.app.ext.ExtensionViewScreen
import dev.patrickgold.florisboard.app.settings.HomeScreen
import dev.patrickgold.florisboard.app.settings.about.AboutScreen
import dev.patrickgold.florisboard.app.settings.about.ProjectLicenseScreen
import dev.patrickgold.florisboard.app.settings.about.ThirdPartyLicensesScreen
import dev.patrickgold.florisboard.app.settings.advanced.OtherScreen
import dev.patrickgold.florisboard.app.settings.advanced.PhysicalKeyboardScreen
import dev.patrickgold.florisboard.app.settings.clipboard.ClipboardScreen
import dev.patrickgold.florisboard.app.settings.dictionary.DictionaryScreen
import dev.patrickgold.florisboard.app.settings.dictionary.UserDictionaryScreen
import dev.patrickgold.florisboard.app.settings.dictionary.UserDictionaryType
import dev.patrickgold.florisboard.app.settings.gestures.GesturesScreen
import dev.patrickgold.florisboard.app.settings.keyboard.FnKeyScreen
import dev.patrickgold.florisboard.app.settings.keyboard.KeyboardScreen
import dev.patrickgold.florisboard.app.settings.localization.LanguagePackManagerScreen
import dev.patrickgold.florisboard.app.settings.localization.LanguagePackManagerScreenAction
import dev.patrickgold.florisboard.app.settings.localization.LocalizationScreen
import dev.patrickgold.florisboard.app.settings.localization.SelectLocaleScreen
import dev.patrickgold.florisboard.app.settings.localization.SubtypeEditorScreen
import dev.patrickgold.florisboard.app.settings.media.MediaScreen
import dev.patrickgold.florisboard.app.settings.smartbar.SmartbarScreen
import dev.patrickgold.florisboard.app.settings.theme.ThemeManagerScreen
import dev.patrickgold.florisboard.app.settings.theme.ThemeManagerScreenAction
import dev.patrickgold.florisboard.app.settings.theme.ThemeScreen
import dev.patrickgold.florisboard.app.settings.typing.TypingScreen
import dev.patrickgold.florisboard.app.setup.SetupScreen
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Deeplink(val path: String)

inline fun <reified T : Any> NavGraphBuilder.composableWithDeepLink(
    kClass: KClass<T>,
    noinline content: @Composable (AnimatedContentScope.(NavBackStackEntry) -> Unit),
) {
    val deeplink = requireNotNull(kClass.annotations.firstOrNull { it is Deeplink } as? Deeplink) {
        "faulty class: $kClass with annotations ${kClass.annotations}"
    }
    composable<T>(
        deepLinks = listOf(navDeepLink<T>(basePath = "ui://florisboard/${deeplink.path}")),
        content = content,
    )
}

object Routes {
    object Setup {
        @Serializable
        object Screen
    }

    object Settings {
        @Serializable
        @Deeplink("settings/home")
        object Home

        @Serializable
        @Deeplink("settings/localization")
        object Localization

        @Serializable
        @Deeplink("settings/localization/select-locale")
        object SelectLocale

        @Serializable
        @Deeplink("settings/localization/language-pack-manage")
        data class LanguagePackManager(val action: LanguagePackManagerScreenAction)

        @Serializable
        @Deeplink("settings/localization/subtype/add")
        object SubtypeAdd

        @Serializable
        @Deeplink("settings/localization/subtype/edit")
        data class SubtypeEdit(val id: Long)

        @Serializable
        @Deeplink("settings/theme")
        object Theme

        @Serializable
        @Deeplink("settings/theme/manage")
        data class ThemeManager(val action: ThemeManagerScreenAction)

        @Serializable
        @Deeplink("settings/keyboard")
        object Keyboard {
            @Serializable
            @Deeplink("settings/keyboard/fn-key")
            object FnKey
        }

        @Serializable
        @Deeplink("settings/smartbar")
        object Smartbar

        @Serializable
        @Deeplink("settings/typing")
        object Typing

        @Serializable
        @Deeplink("settings/dictionary")
        object Dictionary

        @Serializable
        @Deeplink("settings/dictionary/user-dictionary")
        data class UserDictionary(val type: UserDictionaryType)

        @Serializable
        @Deeplink("settings/gestures")
        object Gestures

        @Serializable
        @Deeplink("settings/clipboard")
        object Clipboard

        // Pysanka: export and import of the clipboard history
        @Serializable
        @Deeplink("settings/clipboard/copy")
        object ClipboardCopy

        // Pysanka: the short settings screens
        @Serializable
        @Deeplink("settings/pysanka/typing")
        object PysankaTyping

        @Serializable
        @Deeplink("settings/pysanka/look")
        object PysankaLook

        @Serializable
        @Deeplink("settings/pysanka/sound")
        object PysankaSound

        @Serializable
        @Deeplink("settings/pysanka/privacy")
        object PysankaPrivacy

        @Serializable
        @Deeplink("settings/pysanka/more")
        object PysankaMore

        // Pysanka: AI on the phone (the model)
        @Serializable
        @Deeplink("settings/pysanka/ai")
        object PysankaAi

        // Pysanka: the owner's clipboard tags
        @Serializable
        @Deeplink("settings/clipboard/tags")
        object ClipboardTags

        @Serializable
        @Deeplink("settings/media")
        object Media

        @Serializable
        @Deeplink("settings/other")
        object Other

        @Serializable
        @Deeplink("settings/other/physical-keyboard")
        object PhysicalKeyboard

        @Serializable
        @Deeplink("settings/other/backup")
        object Backup

        @Serializable
        @Deeplink("settings/other/restore")
        object Restore

        @Serializable
        @Deeplink("settings/about")
        object About

        @Serializable
        @Deeplink("settings/about/project-license")
        object ProjectLicense

        @Serializable
        @Deeplink("settings/about/third-party-licenses")
        object ThirdPartyLicenses
    }

    object Ext {
        @Serializable
        @Deeplink("ext")
        object Home

        @Serializable
        @Deeplink("ext/list")
        data class List(val type: ExtensionListScreenType, val showUpdate: Boolean? = null)

        @Serializable
        @Deeplink("ext/edit")
        data class Edit(val id: String, @SerialName("create") val serialType: String? = null)

        @Serializable
        @Deeplink("ext/export")
        data class Export(val id: String)

        @Serializable
        @Deeplink("ext/import")
        data class Import(val type: ExtensionImportScreenType, val uuid: String? = null)

        @Serializable
        @Deeplink("ext/view")
        data class View(val id: String)

        @Serializable
        @Deeplink("ext/check-updates")
        object CheckUpdates
    }

    @Composable
    fun AppNavHost(
        modifier: Modifier,
        navController: NavHostController,
        startDestination: KClass<*>,
    ) {
        NavHost(
            modifier = modifier,
            navController = navController,
            startDestination = startDestination,
            enterTransition = {
                slideIn { IntOffset(it.width, 0) } + fadeIn()
            },
            exitTransition = {
                slideOut { IntOffset(-it.width, 0) } + fadeOut()
            },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = {
                scaleOut(
                    targetScale = 0.85F,
                    transformOrigin = TransformOrigin(pivotFractionX = 0.8f, pivotFractionY = 0.5f)
                ) + fadeOut(spring(stiffness = Spring.StiffnessMedium))
            },
        ) {
            composable<Setup.Screen> { SetupScreen() }

            composableWithDeepLink(Settings.Home::class) { HomeScreen() }

            composableWithDeepLink(Settings.Localization::class) { LocalizationScreen() }
            composableWithDeepLink(Settings.SelectLocale::class) { SelectLocaleScreen() }
            composableWithDeepLink(Settings.LanguagePackManager::class) { navBackStack ->
                val payload = navBackStack.toRoute<Settings.LanguagePackManager>()
                LanguagePackManagerScreen(payload.action)
            }
            composableWithDeepLink(Settings.SubtypeAdd::class) { SubtypeEditorScreen(null) }
            composableWithDeepLink(Settings.SubtypeEdit::class) { navBackStack ->
                val payload = navBackStack.toRoute<Settings.SubtypeEdit>()
                SubtypeEditorScreen(payload.id)
            }

            composableWithDeepLink(Settings.Theme::class) { ThemeScreen() }
            composableWithDeepLink(Settings.ThemeManager::class) { navBackStack ->
                val payload = navBackStack.toRoute<Settings.ThemeManager>()
                ThemeManagerScreen(payload.action)
            }

            composableWithDeepLink(Settings.Keyboard::class) { KeyboardScreen() }
            composableWithDeepLink(Settings.Keyboard.FnKey::class) { FnKeyScreen() }

            composableWithDeepLink(Settings.Smartbar::class) { SmartbarScreen() }

            composableWithDeepLink(Settings.Typing::class) { TypingScreen() }

            composableWithDeepLink(Settings.Dictionary::class) { DictionaryScreen() }
            composableWithDeepLink(Settings.UserDictionary::class) { navBackStack ->
                val payload = navBackStack.toRoute<Settings.UserDictionary>()
                UserDictionaryScreen(payload.type)
            }

            composableWithDeepLink(Settings.Gestures::class) { GesturesScreen() }

            composableWithDeepLink(Settings.Clipboard::class) { ClipboardScreen() }
            composableWithDeepLink(Settings.ClipboardCopy::class) { ua.pysanka.clipboard.ClipboardBackupScreen() }
            composableWithDeepLink(Settings.ClipboardTags::class) { ua.pysanka.clipboard.ClipboardTagsScreen() }
            composableWithDeepLink(Settings.PysankaTyping::class) { ua.pysanka.settings.PysankaTypingScreen() }
            composableWithDeepLink(Settings.PysankaLook::class) { ua.pysanka.settings.PysankaLookScreen() }
            composableWithDeepLink(Settings.PysankaSound::class) { ua.pysanka.settings.PysankaSoundScreen() }
            composableWithDeepLink(Settings.PysankaPrivacy::class) { ua.pysanka.settings.PysankaPrivacyScreen() }
            composableWithDeepLink(Settings.PysankaMore::class) { ua.pysanka.settings.PysankaMoreScreen() }
            composableWithDeepLink(Settings.PysankaAi::class) { ua.pysanka.ai.AiSettingsScreen() }

            composableWithDeepLink(Settings.Media::class) { MediaScreen() }

            composableWithDeepLink(Settings.Other::class) { OtherScreen() }
            composableWithDeepLink(Settings.PhysicalKeyboard::class) { PhysicalKeyboardScreen() }
            // Pysanka: FlorisBoard 0.6's own backup screens are switched off (empty); show the clipboard copy
            composableWithDeepLink(Settings.Backup::class) { ua.pysanka.clipboard.ClipboardBackupScreen() }
            composableWithDeepLink(Settings.Restore::class) { ua.pysanka.clipboard.ClipboardBackupScreen() }

            composableWithDeepLink(Settings.About::class) { AboutScreen() }
            composableWithDeepLink(Settings.ProjectLicense::class) { ProjectLicenseScreen() }
            composableWithDeepLink(Settings.ThirdPartyLicenses::class) { ThirdPartyLicensesScreen() }

            composableWithDeepLink(Ext.Home::class) { ExtensionHomeScreen() }
            composableWithDeepLink(Ext.List::class) { navBackStack ->
                val payload = navBackStack.toRoute<Ext.List>()
                val showUpdate = payload.showUpdate != null && payload.showUpdate
                ExtensionListScreen(payload.type, showUpdate)
            }
            composableWithDeepLink(Ext.Edit::class) { navBackStack ->
                val payload = navBackStack.toRoute<Ext.Edit>()
                val extensionId = payload.id
                val serialType = payload.serialType
                ExtensionEditScreen(
                    id = extensionId,
                    createSerialType = serialType.takeIf { !it.isNullOrBlank() },
                )
            }
            composableWithDeepLink(Ext.Export::class) { navBackStack ->
                val payload = navBackStack.toRoute<Ext.Export>()
                val extensionId = payload.id
                ExtensionExportScreen(id = extensionId)
            }
            composableWithDeepLink(Ext.Import::class) { navBackStack ->
                val payload = navBackStack.toRoute<Ext.Import>()
                val uuid = payload.uuid
                ExtensionImportScreen(payload.type, uuid)
            }
            composableWithDeepLink(Ext.View::class) { navBackStack ->
                val payload = navBackStack.toRoute<Ext.View>()
                val extensionId = payload.id
                ExtensionViewScreen(id = extensionId)
            }
            composableWithDeepLink(Ext.CheckUpdates::class) {
                CheckUpdatesScreen()
            }
        }
    }
}
