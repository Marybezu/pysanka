package ua.pysanka.ai

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.BusinessCenter
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.SentimentSatisfied
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.TheaterComedy
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.clipboardManager
import dev.patrickgold.florisboard.ime.ImeUiMode
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import org.florisboard.lib.android.AndroidKeyguardManager
import org.florisboard.lib.android.systemService
import org.florisboard.lib.compose.florisVerticalScroll
import org.florisboard.lib.compose.stringRes
import org.florisboard.lib.snygg.SnyggSelector
import org.florisboard.lib.snygg.ui.SnyggBox
import org.florisboard.lib.snygg.ui.SnyggChip
import org.florisboard.lib.snygg.ui.SnyggColumn
import org.florisboard.lib.snygg.ui.SnyggIcon
import org.florisboard.lib.snygg.ui.SnyggIconButton
import org.florisboard.lib.snygg.ui.SnyggRow
import org.florisboard.lib.snygg.ui.SnyggText

/**
 * Pysanka: the AI panel in place of the keys (✦ on the bar above the keyboard). The actions as
 * tiles; the answer right in the panel, to put in the field, copy, do again or work on further;
 * "Expand" opens the full editor. The model works in [AiService] (the ":ai" process) while the
 * panel is open.
 *
 * The text is read once when ✦ is pressed (see [AiBridge.openPanel]) and kept only while the panel
 * is open. Possible replies are written to the message on the clipboard, if there is one (never a
 * sensitive one, and not while the phone is locked).
 */
@Composable
fun AiPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val panel = remember { AiBridge.takePanel() }
    // «Моїм стилем» only once the person added samples (the file is not read here)
    val hasStyle = remember { StyleSamples.has(context) }

    fun closePanel() {
        keyboardManager.activeState.imeUiMode = ImeUiMode.TEXT
    }

    if (panel == null) {
        // Opened again without ✦ (the keyboard was rebuilt): nothing to work on
        LaunchedEffect(Unit) { closePanel() }
        return
    }
    val installed = remember { ModelStore(context).modelFile() != null }
    val clipboardManager by context.clipboardManager()
    val clip = remember {
        val keyguard = context.systemService(AndroidKeyguardManager::class)
        clipboardManager.primaryClip
            ?.takeIf { it.type == ItemType.TEXT && !it.isSensitive && !keyguard.isDeviceLocked && !keyguard.isKeyguardLocked }
            ?.text?.takeIf { it.isNotBlank() }?.take(AiBridge.MAX_CHARS)
    }

    var stage by remember { mutableStateOf<PanelStage>(if (installed) PanelStage.Menu else PanelStage.NoModel) }
    var source by remember { mutableStateOf(panel.taken.text) }
    var target by remember { mutableStateOf(AiLanguage.EN) }
    var more by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var token by remember { mutableLongStateOf(0L) }

    val link = remember {
        if (!installed) {
            null
        } else {
            AiLink(
                context,
                onMessage = { what, data ->
                    if (data.getBoolean(AiService.KEY_LOADED)) loaded = true
                    val s = stage
                    if (what != AiService.LOADED && s is PanelStage.Working && data.getLong(AiService.KEY_TOKEN) == token) {
                        stage = when (what) {
                            AiService.PART -> s.copy(part = data.getInt(AiService.KEY_PART), parts = data.getInt(AiService.KEY_PARTS))
                            AiService.DONE -> PanelStage.Done(
                                s.action, s.fromClip, data.getString(AiService.KEY_TEXT).orEmpty(),
                                data.getInt(AiService.KEY_REMOVED), data.getBoolean(AiService.KEY_UNCONFIRMED),
                                data.getInt(AiService.KEY_SECONDS), data.getString(AiService.KEY_BACKEND).orEmpty(),
                            )
                            AiService.FAIL -> PanelStage.Failed(s.action)
                            else -> s
                        }
                    }
                },
                // The AI's process ended (out of memory, say): what was being written is lost
                onLost = {
                    loaded = false
                    (stage as? PanelStage.Working)?.let { stage = PanelStage.Failed(it.action) }
                },
            )
        }
    }
    DisposableEffect(link) { onDispose { link?.close() } }

    /** Possible replies go to the message on the clipboard, unless a text is selected or already worked on. */
    fun fromClip(action: AiAction) =
        action == AiAction.REPLIES && clip != null && panel.taken.mode != AiBridge.Mode.SELECTION && source == panel.taken.text

    fun input(action: AiAction) = if (fromClip(action)) clip!! else source

    fun runAction(action: AiAction) {
        val text = input(action)
        if (text.isBlank() || link == null) return
        if (text.length > AiJob.limit(context, action)) {
            stage = PanelStage.TooLong(action)
            return
        }
        token++
        stage = PanelStage.Working(action, fromClip(action), 1, 1)
        link.send(AiService.RUN, Bundle().apply {
            putLong(AiService.KEY_TOKEN, token)
            putString(AiService.KEY_ACTION, action.name)
            putString(AiService.KEY_TARGET, target.name)
            putString(AiService.KEY_TEXT, text)
        })
    }

    fun goBack() {
        if (stage is PanelStage.Working) link?.send(AiService.STOP)
        stage = PanelStage.Menu
    }

    /** Into the field: in place of the text it came from, or (possible replies, an empty field) at the cursor. */
    fun placeText(text: String, insert: Boolean) {
        val t = panel.taken
        val result = AiBridge.Result(if (insert) AiBridge.Mode.INSERT else t.mode, t.text, text, t.start, t.end)
        if (!ua.pysanka.ime.FieldText.applyAi(panel.info, result)) AiBridge.couldNotPlace(text)
        closePanel()
    }

    fun openFull(action: AiAction) {
        val t = panel.taken
        // Replies are never put in place of the text
        val taken = if (action == AiAction.REPLIES) t.copy(mode = AiBridge.Mode.INSERT) else t
        // An answer already written goes along, so the work is not lost
        val done = (stage as? PanelStage.Done)?.takeIf { it.action == action }
            ?.let { AiJob.Output(it.text, it.removed, it.unconfirmed, it.seconds, it.backend) }
        AiBridge.openEditor(panel.info, taken, input(action), action, done)
    }

    AiPanelView(
        stage = stage,
        note = when {
            source != panel.taken.text -> R.string.pysanka__ai__panel_previous
            source.isBlank() -> R.string.pysanka__ai__panel_empty
            panel.taken.mode == AiBridge.Mode.SELECTION -> R.string.pysanka__ai__panel_selection
            else -> R.string.pysanka__ai__panel_all
        },
        loaded = loaded,
        more = more,
        target = target,
        intoEmptyField = panel.taken.mode == AiBridge.Mode.INSERT,
        canExpand = panel.canExpand,
        canRun = { input(it).isNotBlank() },
        available = { it != AiAction.MY_STYLE || hasStyle },
        modifier = modifier.fillMaxWidth().height(FlorisImeSizing.imeUiHeight()),
        headerHeight = FlorisImeSizing.smartbarHeight,
        actions = object : PanelActions {
            override fun close() = closePanel()
            override fun back() = goBack()
            override fun tile(action: AiAction) {
                if (action == AiAction.TRANSLATE) stage = PanelStage.Languages else runAction(action)
            }
            override fun toggleMore() {
                more = !more
            }
            override fun translate(language: AiLanguage) {
                target = language
                runAction(AiAction.TRANSLATE)
            }
            override fun again(action: AiAction) = runAction(action)
            override fun expand(action: AiAction) = openFull(action)
            override fun place(text: String, insert: Boolean) = placeText(text, insert)
            override fun copy(text: String) = ua.pysanka.privacy.Clip.copy(context, text)
            override fun change(text: String) {
                source = text
                stage = PanelStage.Menu
            }
            override fun settings() {
                closePanel()
                openAiSettings(context)
            }
        },
    )
}

/** What the panel's buttons do (see [AiPanel]; the screenshots draw it with none). */
internal interface PanelActions {
    fun close()
    fun back()
    fun tile(action: AiAction)
    fun toggleMore()
    fun translate(language: AiLanguage)
    fun again(action: AiAction)
    fun expand(action: AiAction)
    fun place(text: String, insert: Boolean)
    fun copy(text: String)
    /** Another action on this answer (it still goes in place of the field's text). */
    fun change(text: String)
    fun settings()
}

/** The panel as it looks: [note] says what it works on; [canRun] whether an action has a text. */
@Composable
internal fun AiPanelView(
    stage: PanelStage,
    note: Int,
    loaded: Boolean,
    more: Boolean,
    target: AiLanguage,
    intoEmptyField: Boolean,
    canExpand: Boolean,
    canRun: (AiAction) -> Boolean,
    /** Whether an action is shown at all («Моїм стилем» only with samples). */
    available: (AiAction) -> Boolean = { true },
    modifier: Modifier,
    headerHeight: Dp,
    actions: PanelActions,
) {
    SnyggColumn(modifier = modifier) {
        SnyggRow(
            FlorisImeUi.ClipboardHeader.elementName,
            modifier = Modifier.fillMaxWidth().height(headerHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val size = Modifier.sizeIn(maxHeight = headerHeight).aspectRatio(1f)
            // Back to the tiles; on the tiles themselves only ✕ (back to the keys) is needed
            if (stage is PanelStage.Menu || stage is PanelStage.NoModel) {
                SnyggIcon(imageVector = ua.pysanka.ui.AiMark, modifier = size.padding(10.dp))
            } else {
                SnyggIconButton(FlorisImeUi.ClipboardHeaderButton.elementName, onClick = { actions.back() }, modifier = size) {
                    SnyggIcon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringRes(R.string.pysanka__ai__back))
                }
            }
            SnyggText(
                FlorisImeUi.ClipboardHeaderText.elementName,
                modifier = Modifier.weight(1f),
                text = stringRes(stage.action?.label ?: R.string.pysanka__ai__panel_title),
            )
            SnyggIconButton(FlorisImeUi.ClipboardHeaderButton.elementName, onClick = { actions.close() }, modifier = size) {
                SnyggIcon(imageVector = Icons.Default.Close, contentDescription = stringRes(R.string.pysanka__ocr__close))
            }
        }
        SnyggBox(FlorisImeUi.ClipboardContent.elementName, modifier = Modifier.fillMaxWidth().weight(1f)) {
            when (val s = stage) {
                PanelStage.NoModel -> Notice(stringRes(R.string.pysanka__ai__no_model)) {
                    Chip(stringRes(R.string.pysanka__ai__open_settings), primary = true) { actions.settings() }
                }
                PanelStage.Menu -> Column(Modifier.fillMaxSize()) {
                    Note(stringRes(note))
                    val shown = (if (more) AiAction.entries else MAIN).filter(available)
                    // At least 112 dp a tile (three across on a phone): narrower, longer words broke in the
                    // middle («Граматик-а»), above all with large text
                    LazyVerticalGrid(columns = GridCells.Adaptive(112.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
                        items(shown) { a ->
                            Tile(icon(a), stringRes(a.label), enabled = canRun(a)) { actions.tile(a) }
                        }
                        item {
                            Tile(
                                if (more) Icons.Default.ExpandLess else Icons.Default.MoreHoriz,
                                stringRes(if (more) R.string.pysanka__ai__less else R.string.pysanka__ai__more),
                            ) { actions.toggleMore() }
                        }
                    }
                }
                PanelStage.Languages -> Notice(stringRes(R.string.pysanka__ai__translate_into)) {
                    for (l in AiLanguage.entries) {
                        Chip(stringRes(l.label), primary = l == target) { actions.translate(l) }
                    }
                }
                is PanelStage.TooLong -> Notice(stringRes(R.string.pysanka__ai__panel_too_long)) {
                    if (canExpand) Chip(stringRes(R.string.pysanka__ai__expand), primary = true) { actions.expand(s.action) }
                }
                is PanelStage.Working -> Working(
                    when {
                        !loaded -> stringRes(R.string.pysanka__ai__loading)
                        s.parts > 1 -> stringRes(R.string.pysanka__ai__working_part, "k" to s.part.toString(), "n" to s.parts.toString())
                        else -> stringRes(R.string.pysanka__ai__working)
                    },
                ) { actions.back() }
                is PanelStage.Failed -> Notice(stringRes(R.string.pysanka__ai__failed)) {
                    Chip(stringRes(R.string.pysanka__ai__again), primary = true) { actions.again(s.action) }
                    if (canExpand) Chip(stringRes(R.string.pysanka__ai__expand)) { actions.expand(s.action) }
                }
                is PanelStage.Done -> Done(
                    s,
                    replaceLabel = when {
                        !s.action.replaces -> null
                        intoEmptyField || s.action == AiAction.REPLIES -> R.string.pysanka__ai__insert
                        else -> R.string.pysanka__ai__replace
                    },
                    onPlace = actions::place,
                    onCopy = { actions.copy(s.text) },
                    onAgain = { actions.again(s.action) },
                    onChange = if (s.action.kind == AiAction.Kind.ADVICE || s.action == AiAction.REPLIES) null else {
                        { actions.change(s.text) }
                    },
                    onExpand = if (canExpand) { { actions.expand(s.action) } } else null,
                )
            }
        }
    }
}

/** The actions shown first; "More" shows all. */
private val MAIN = listOf(
    AiAction.FIX, AiAction.MY_STYLE, AiAction.FRIENDLY, AiAction.FORMAL, AiAction.SHORTER,
    AiAction.REPLIES, AiAction.TONE, AiAction.TRANSLATE, AiAction.RUSSISMS,
    AiAction.SUMMARY, AiAction.LIST, AiAction.TABLE,
)

private fun icon(action: AiAction): ImageVector = when (action) {
    AiAction.FIX -> Icons.Default.Spellcheck
    AiAction.FIX_OCR -> Icons.Default.DocumentScanner
    AiAction.RUSSISMS -> Icons.Default.Flag
    AiAction.SUMMARY -> Icons.Default.Summarize
    AiAction.TRANSLATE -> Icons.Default.Translate
    AiAction.FRIENDLY -> Icons.Default.SentimentSatisfied
    AiAction.FORMAL -> Icons.Default.BusinessCenter
    AiAction.SHORTER -> Icons.Default.ContentCut
    AiAction.LIST -> Icons.AutoMirrored.Filled.FormatListBulleted
    AiAction.TABLE -> Icons.Default.TableChart
    AiAction.NOTES -> Icons.Default.EditNote
    AiAction.LETTER -> Icons.Default.Email
    AiAction.REPLIES -> Icons.Default.QuestionAnswer
    AiAction.TONE -> Icons.Default.TheaterComedy
    AiAction.REDACT -> Icons.Default.VisibilityOff
    AiAction.MY_STYLE -> Icons.Default.Face
}

internal sealed interface PanelStage {
    /** The action this stage is about (its name is the panel's title). */
    val action: AiAction? get() = null

    data object NoModel : PanelStage
    data object Menu : PanelStage
    data object Languages : PanelStage {
        override val action: AiAction get() = AiAction.TRANSLATE
    }
    data class TooLong(override val action: AiAction) : PanelStage
    /** [fromClip]: written to the message on the clipboard. */
    data class Working(override val action: AiAction, val fromClip: Boolean, val part: Int, val parts: Int) : PanelStage
    data class Done(
        override val action: AiAction,
        val fromClip: Boolean,
        val text: String,
        val removed: Int,
        val unconfirmed: Boolean,
        val seconds: Int,
        val backend: String,
    ) : PanelStage
    data class Failed(override val action: AiAction) : PanelStage
}

@Composable
private fun Note(text: String) {
    SnyggText(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), text = text)
}

/** A tile as the keyboard's own action tiles look (the theme decides). */
@Composable
private fun Tile(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    SnyggBox(
        FlorisImeUi.SmartbarActionTile.elementName,
        selector = if (enabled) null else SnyggSelector.DISABLED,
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
        clickAndSemanticsModifier = Modifier.clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            SnyggIcon(imageVector = icon)
            SnyggText(FlorisImeUi.SmartbarActionTileText.elementName, text = label)
        }
    }
}

/** A quiet action as an icon (its name is read out, and shown on a long press by the system). */
@Composable
private fun ActionIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    SnyggIconButton(FlorisImeUi.ClipboardHeaderButton.elementName, onClick = onClick, modifier = Modifier.size(44.dp)) {
        SnyggIcon(imageVector = icon, contentDescription = label, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun Chip(text: String, primary: Boolean = false, onClick: () -> Unit) {
    SnyggChip(
        FlorisImeUi.ClipboardFilterChip.elementName,
        attributes = mapOf("state" to if (primary) "active" else "inactive"),
        onClick = onClick,
        text = text,
    )
}

/** A short message with its buttons. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Notice(text: String, buttons: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().florisVerticalScroll().padding(8.dp)) {
        SnyggText(modifier = Modifier.fillMaxWidth().padding(4.dp), text = text)
        FlowRow { buttons() }
    }
}

@Composable
private fun Working(text: String, onStop: () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "ai").animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "ai",
    )
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        SnyggIcon(imageVector = ua.pysanka.ui.AiMark, modifier = Modifier.size(32.dp).alpha(pulse))
        SnyggText(text = text)
        Chip(stringRes(R.string.pysanka__ai__stop), onClick = onStop)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Done(
    s: PanelStage.Done,
    replaceLabel: Int?,
    onPlace: (String, Boolean) -> Unit,
    onCopy: () -> Unit,
    onAgain: () -> Unit,
    onChange: (() -> Unit)?,
    onExpand: (() -> Unit)?,
) {
    val replies = remember(s) { if (s.action == AiAction.REPLIES) Replies.parse(s.text).takeIf { it.size >= 2 } else null }
    Column(Modifier.fillMaxSize()) {
        if (replies != null) {
            Note(stringRes(if (s.fromClip) R.string.pysanka__ai__panel_clip else R.string.pysanka__ai__panel_tap_reply))
            Column(Modifier.fillMaxWidth().weight(1f).florisVerticalScroll()) {
                for (r in replies) {
                    SnyggBox(
                        FlorisImeUi.ClipboardItem.elementName,
                        modifier = Modifier.fillMaxWidth(),
                        clickAndSemanticsModifier = Modifier.clickable { onPlace(r.text, true) },
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            r.label?.let { SnyggText(FlorisImeUi.ClipboardItemDescription.elementName, text = it) }
                            SnyggText(text = r.text)
                        }
                    }
                }
            }
        } else {
            SnyggBox(FlorisImeUi.ClipboardItem.elementName, modifier = Modifier.fillMaxWidth().weight(1f)) {
                SnyggText(modifier = Modifier.fillMaxWidth().florisVerticalScroll().padding(12.dp), text = s.text)
            }
        }
        Note(
            buildString {
                append(stringRes(R.string.pysanka__ai__done_in, "s" to s.seconds.toString(), "where" to s.backend))
                if (s.unconfirmed) {
                    append(" · ").append(stringRes(R.string.pysanka__ai__fact_unconfirmed))
                } else if (s.removed > 0) {
                    append(" · ").append(stringRes(R.string.pysanka__ai__fact_removed, "n" to s.removed.toString()))
                }
            },
        )
        // One tidy row: the quieter actions as icons on the left, the main one on the right
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val primary = replaceLabel != null && replies == null
            if (primary) ActionIcon(Icons.Default.ContentCopy, stringRes(R.string.pysanka__ocr__copy), onCopy)
            ActionIcon(Icons.Default.Refresh, stringRes(R.string.pysanka__ai__again), onAgain)
            if (onChange != null) ActionIcon(Icons.Default.EditNote, stringRes(R.string.pysanka__ai__change), onChange)
            if (onExpand != null) ActionIcon(Icons.Default.OpenInFull, stringRes(R.string.pysanka__ai__expand), onExpand)
            Spacer(Modifier.weight(1f))
            // Replies are put in by tapping one; advice is only copied
            if (primary) {
                Chip(stringRes(replaceLabel!!), primary = true) { onPlace(s.text, s.action == AiAction.REPLIES) }
            } else {
                Chip(stringRes(R.string.pysanka__ocr__copy), primary = true, onClick = onCopy)
            }
        }
    }
}

private const val WARM_DELAY_MS = 1500L

/** The panel's connection to [AiService], from opening to closing the panel. */
private class AiLink(
    context: Context,
    private val onMessage: (Int, Bundle) -> Unit,
    private val onLost: () -> Unit,
) : ServiceConnection {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val incoming = Messenger(Handler(Looper.getMainLooper()) { msg -> onMessage(msg.what, msg.data); true })
    private val warm = Runnable { send(AiService.WARM) }
    private var service: Messenger? = null

    /** A run asked for before the service was connected. */
    private var waiting: Message? = null
    private val bound = runCatching { app.bindService(Intent(app, AiService::class.java), this, Context.BIND_AUTO_CREATE) }
        .getOrDefault(false)

    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        service = binder?.let(::Messenger)
        // The model starts loading only if the panel stays open a moment (✦ then ✕ costs nothing)
        main.postDelayed(warm, WARM_DELAY_MS)
        waiting?.let(::deliver)
        waiting = null
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        service = null
        onLost()
    }

    fun send(what: Int, data: Bundle = Bundle()) {
        val msg = Message.obtain(null, what).apply {
            this.data = data
            replyTo = incoming
        }
        // A run loads the model itself: no warm-up after it
        if (what == AiService.RUN) main.removeCallbacks(warm)
        when {
            !bound -> onLost()
            service == null -> waiting = msg.takeIf { what == AiService.RUN }
            else -> deliver(msg)
        }
    }

    private fun deliver(msg: Message) {
        if (runCatching { service?.send(msg) }.isFailure) onLost()
    }

    fun close() {
        main.removeCallbacks(warm)
        waiting = null
        if (bound) runCatching { app.unbindService(this) }
    }
}
