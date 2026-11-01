package ua.pysanka.screens

import android.app.Application
import android.content.ComponentName
import android.graphics.Bitmap
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.patrickgold.florisboard.R
import org.florisboard.lib.compose.ProvideLocalizedResources
import org.florisboard.lib.snygg.SnyggStylesheet
import org.florisboard.lib.snygg.ui.ProvideSnyggTheme
import org.florisboard.lib.snygg.ui.rememberSnyggTheme
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ua.pysanka.ai.AiAction
import ua.pysanka.ai.AiLanguage
import ua.pysanka.ai.AiPanelView
import ua.pysanka.ai.PanelActions
import ua.pysanka.ai.PanelStage
import java.io.File

/**
 * Draws the AI panel on the computer, with the keyboard's own day and night themes and made-up
 * texts, for review. A screen that crashes fails the build; the pictures go to build/screens.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// A plain application: Pysanka's own starts the encrypted storage, which is not needed here
@Config(sdk = [35], application = Application::class, qualifiers = "uk-w400dp-h760dp-xxhdpi")
class AiPanelScreensTest {
    private lateinit var app: Application

    private val none = object : PanelActions {
        override fun close() {}
        override fun back() {}
        override fun tile(action: AiAction) {}
        override fun toggleMore() {}
        override fun translate(language: AiLanguage) {}
        override fun again(action: AiAction) {}
        override fun expand(action: AiAction) {}
        override fun place(text: String, insert: Boolean) {}
        override fun copy(text: String) {}
        override fun change(text: String) {}
        override fun settings() {}
    }

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        // The empty activity that hosts each picture (known only to this test)
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
    }

    private fun shot(
        name: String,
        stage: PanelStage,
        dark: Boolean = false,
        note: Int = R.string.pysanka__ai__panel_selection,
        more: Boolean = false,
        message: String = MESSAGE,
    ) = ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
        val json = app.assets.open("extensions/org.florisboard.themes/stylesheets/${if (dark) "floris_night" else "floris_day"}.json")
            .bufferedReader().use { it.readText() }
        val stylesheet = SnyggStylesheet.fromJson(json).getOrThrow()
        scenario.onActivity { activity ->
            activity.setContent {
                ProvideLocalizedResources(activity, appName = R.string.app_name) {
                    CompositionLocalProvider(LocalTextStyle provides TextStyle.Default) {
                        ProvideSnyggTheme(rememberSnyggTheme(stylesheet)) {
                            Column(Modifier.fillMaxSize().background(if (dark) Color(0xFF121212) else Color(0xFFF4F4F4))) {
                                // A made-up chat above the keyboard, for scale
                                Text(
                                    message,
                                    color = if (dark) Color.White else Color.Black,
                                    modifier = Modifier.padding(16.dp)
                                        .background(if (dark) Color(0xFF2A2A2A) else Color.White, RoundedCornerShape(12.dp))
                                        .padding(12.dp),
                                )
                                Spacer(Modifier.weight(1f))
                                AiPanelView(
                                    stage = stage,
                                    note = note,
                                    loaded = true,
                                    more = more,
                                    target = AiLanguage.EN,
                                    intoEmptyField = false,
                                    canRun = { true },
                                    modifier = Modifier.fillMaxWidth().height(300.dp),
                                    headerHeight = 40.dp,
                                    actions = none,
                                )
                            }
                        }
                    }
                }
            }
        }
        repeat(8) { shadowOf(Looper.getMainLooper()).idle() }
        var bitmap: Bitmap? = null
        scenario.onActivity { activity ->
            val view = activity.window.decorView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                .also { view.draw(android.graphics.Canvas(it)) }
        }
        val dir = File("build/screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Unit
    }

    private fun done(action: AiAction, text: String, fromClip: Boolean = false) =
        PanelStage.Done(action, fromClip, text, removed = 0, unconfirmed = false, seconds = 3, backend = "GPU")

    @Test fun menu() = shot("ai-01-menu", PanelStage.Menu)
    @Test fun menuDark() = shot("ai-01-menu-dark", PanelStage.Menu, dark = true)
    @Test fun menuAll() = shot("ai-02-menu-all", PanelStage.Menu, more = true)
    @Test fun languages() = shot("ai-03-languages", PanelStage.Languages)
    @Test fun working() = shot("ai-04-working", PanelStage.Working(AiAction.SUMMARY, false, 2, 5))
    @Test fun result() = shot("ai-05-result", done(AiAction.FIX, FIXED))
    @Test fun resultDark() = shot("ai-05-result-dark", done(AiAction.FIX, FIXED), dark = true)
    @Test fun replies() = shot("ai-06-replies", done(AiAction.REPLIES, REPLIES, fromClip = true), message = RECEIVED)
    @Test fun tone() = shot("ai-07-tone", done(AiAction.TONE, TONE))
    @Test fun summaryChecked() = shot(
        "ai-08-summary",
        PanelStage.Done(AiAction.SUMMARY, false, SUMMARY, removed = 1, unconfirmed = false, seconds = 41, backend = "GPU"),
    )
    @Test fun tooLong() = shot("ai-09-too-long", PanelStage.TooLong(AiAction.FRIENDLY))
    @Test fun failed() = shot("ai-10-failed", PanelStage.Failed(AiAction.TABLE))
    @Test fun noModel() = shot("ai-11-no-model", PanelStage.NoModel)

    private companion object {
        const val MESSAGE = "привіт не зможу завтра бо маю лікаря давай в четвер після обіду якщо тобі зручно"
        const val RECEIVED = "Привіт! Чи зможеш завтра о 10 приїхати на зустріч щодо проєкту?"
        const val FIXED = "Привіт! Не зможу завтра, бо маю лікаря. Давай у четвер після обіду, якщо тобі зручно?"
        const val REPLIES = "Так: Так, буду о 10, дякую!\n\nНі: Завтра не вийде, можемо перенести на четвер?\n\n" +
            "Уточнити: А де саме зустріч і скільки вона триватиме?"
        const val TONE = "Звучить дружньо, але трохи поспіхом: бракує розділових знаків, тож читати важче. " +
            "Додайте кому перед «бо» і знак питання наприкінці."
        const val SUMMARY = "• Зустріч щодо проєкту переносять із середи на четвер.\n" +
            "• Початок о 14:00, у залі на другому поверсі.\n• Потрібно взяти ноутбук і план робіт."
    }
}
