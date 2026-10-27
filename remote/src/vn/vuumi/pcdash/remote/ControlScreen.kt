package vn.vuumi.pcdash.remote

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import vn.vuumi.pcdash.i18n.L10n

/** One power command's tile: how it looks, and what its command is doing now. */
class PowerTile(
    val action: String,
    val icon: PowerIcon,
    val label: String,
    val fill: Color,
    /** Outline colour, or null for a filled tile. */
    val stroke: Color?,
    val content: Color,
    val height: Dp,
) {
    var phase by mutableStateOf(TilePhase.IDLE)
    var text by mutableStateOf(label)
    /** False: dimmed and not tappable while another command is being sent. */
    var available by mutableStateOf(true)
    /** Counts changes of [phase], so the icon plays each one once. */
    var seq by mutableIntStateOf(0)
    val morph = MorphIcon()
}

/** A line under the PC's state about the last command. */
class ActionLine(val text: String, val color: Color)

enum class UpdateButton { UPDATE, CANCEL, RETRY }

/** The update card as it should look now. */
class UpdateCard(
    val fill: Color,
    val stroke: Color,
    val title: String,
    val titleColor: Color,
    val sub: String?,
    val subColor: Color,
    val permissionNotice: Boolean,
    /** Download progress 0..100, or null for no bar. */
    val progress: Int?,
    val button: UpdateButton,
)

/** Everything the Control tab shows. [ControlTabView] changes it; [ControlScreen] draws it. */
class ControlState {
    var status by mutableStateOf(L10n.t("Checking the PC...", "Đang kiểm tra trạng thái PC..."))
    var statusColor by mutableStateOf(Color(UiKit.WARN))
    /** How often the status dot pings; 0 for a still dot. */
    var statusPingMs by mutableIntStateOf(900)
    var detail by mutableStateOf(L10n.t("Loading...", "Đang tải dữ liệu..."))
    var refreshing by mutableStateOf(false)
    var actionLine by mutableStateOf<ActionLine?>(null)
    var update by mutableStateOf<UpdateCard?>(null)

    val tiles = listOf(
        PowerTile("wake", PowerIcon.WAKE, L10n.t("Turn on / Wake PC", "Bật / Đánh thức PC"), Color(27, 102, 42), null, Color.White, 60.dp),
        PowerTile("sleep", PowerIcon.SLEEP, "Sleep", Color(26, 84, 176), null, Color.White, 56.dp),
        PowerTile("hibernate", PowerIcon.HIBERNATE, "Hibernate", Color(100, 64, 168), null, Color.White, 56.dp),
        PowerTile("shutdown", PowerIcon.SHUTDOWN, L10n.t("Shut down", "Tắt máy (Shutdown)"), Color(34, 20, 22), Color(UiKit.ERROR), Color(UiKit.ERROR), 56.dp),
        PowerTile("abort", PowerIcon.ABORT, L10n.t("Cancel shutdown", "Hủy lệnh tắt máy"), Color(UiKit.CARD), Color(UiKit.BORDER), Color(201, 209, 217), 48.dp),
    )

    fun tile(action: String) = tiles.first { it.action == action }
}

private val MUTED = Color(UiKit.MUTED)
private val CARD_SHAPE = RoundedCornerShape(12.dp)

/** Fades the old text out, then the new one in, so it does not jump. */
private fun <S> textSwap(): AnimatedContentTransitionScope<S>.() -> ContentTransform = {
    fadeIn(tween(150, delayMillis = 90)) togetherWith fadeOut(tween(90)) using SizeTransform(clip = false)
}

@Composable
fun ControlScreen(state: ControlState, callback: ControlTabView.Callback?) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 32.dp),
    ) {
        StatusCard(state) { callback?.onRefresh() }
        Spacer(Modifier.height(18.dp))

        // Kept while the card slides away, so it does not go blank first.
        val card = state.update
        val lastCard = remember { arrayOfNulls<UpdateCard>(1) }
        if (card != null) lastCard[0] = card
        AnimatedVisibility(card != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            lastCard[0]?.let {
                Column {
                    UpdateCardView(it, callback)
                    Spacer(Modifier.height(18.dp))
                }
            }
        }

        val onAction = { action: String -> callback?.onActionClicked(action); Unit }
        SectionLabel(L10n.t("POWER", "NGUỒN"))
        PowerTileView(state.tile("wake"), onAction)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PowerTileView(state.tile("sleep"), onAction, Modifier.weight(1f))
            PowerTileView(state.tile("hibernate"), onAction, Modifier.weight(1f))
        }
        Spacer(Modifier.height(18.dp))
        SectionLabel(L10n.t("DANGER ZONE", "VÙNG NGUY HIỂM"))
        PowerTileView(state.tile("shutdown"), onAction)
        Spacer(Modifier.height(10.dp))
        PowerTileView(state.tile("abort"), onAction)
    }
}

@Composable
private fun StatusCard(state: ControlState, onRefresh: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(UiKit.CARD), CARD_SHAPE)
            .border(1.dp, Color(UiKit.BORDER), CARD_SHAPE)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(state.statusColor, state.statusPingMs, Modifier.size(14.dp))
            Spacer(Modifier.width(10.dp))
            val color by animateColorAsState(state.statusColor, tween(300), label = "status")
            AnimatedContent(
                state.status, Modifier.weight(1f), transitionSpec = textSwap(),
                contentAlignment = Alignment.CenterStart, label = "status",
            ) { text ->
                BasicText(text, style = TextStyle(color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold))
            }
            Spacer(Modifier.width(8.dp))
            RefreshButton(state.refreshing, onRefresh)
        }
        BasicText(state.detail, style = TextStyle(color = MUTED, fontSize = 12.sp))

        val line = state.actionLine
        val lastLine = remember { arrayOfNulls<ActionLine>(1) }
        if (line != null) lastLine[0] = line
        AnimatedVisibility(line != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            lastLine[0]?.let {
                BasicText(
                    it.text,
                    Modifier
                        .padding(top = 8.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    style = TextStyle(color = it.color, fontSize = 13.sp),
                )
            }
        }
    }
}

/** Spins while a fresh status is on its way, then finishes its turn instead of snapping back. */
@Composable
private fun RefreshButton(refreshing: Boolean, onClick: () -> Unit) {
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(refreshing) {
        val from = rotation.value % 360f
        if (refreshing) {
            rotation.snapTo(from)
            rotation.animateTo(from + 360f, infiniteRepeatable(tween(900, easing = LinearEasing)))
        } else if (from != 0f) {
            rotation.snapTo(from)
            rotation.animateTo(360f, tween(((360f - from) * 1.5f).toInt(), easing = decelerate()))
            rotation.snapTo(0f)
        }
    }
    val fill = Color(33, 38, 45)
    val description = L10n.t("Refresh", "Làm mới")
    Box(
        Modifier
            .size(44.dp)
            .glowPress(fill, 22.dp) { onClick() }
            .background(fill, CircleShape)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(24.dp).graphicsLayer { rotationZ = rotation.value }) {
            drawPowerIcon(PowerIcon.REFRESH, Color(201, 209, 217), Offset.Zero, size.minDimension)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    BasicText(
        text,
        Modifier
            .padding(start = 4.dp, top = 6.dp, bottom = 8.dp)
            .semantics { heading() },
        style = TextStyle(color = MUTED, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.04.em),
    )
}

/**
 * Icon + label tile for a power command that shows, on itself, what its command is doing: the icon
 * folds into a spinner with a short status while the command is sent and the PC changes state, then
 * turns into a tick or a cross for a moment (see [MorphIcon]). It lights up where it is touched and its
 * rim breathes while the command runs.
 */
@Composable
private fun PowerTileView(tile: PowerTile, onAction: (String) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    val dim by animateFloatAsState(if (tile.available) 1f else 0.45f, tween(160), label = "dim")
    LaunchedEffect(tile.seq) { tile.morph.follow(tile.phase, tile.seq) }
    val description = if (tile.phase == TilePhase.IDLE) tile.label else tile.label + ": " + tile.text
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = tile.height)
            .graphicsLayer { alpha = dim }
            .glowPress(
                tile.stroke?.let { tile.content } ?: tile.fill, 12.dp,
                enabled = tile.available && tile.phase == TilePhase.IDLE,
                breathing = tile.phase == TilePhase.BUSY,
            ) { onAction(tile.action) }
            .background(tile.fill, shape)
            .then(if (tile.stroke != null) Modifier.border(1.dp, tile.stroke, shape) else Modifier)
            .semantics { contentDescription = description }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(24.dp)) {
            with(tile.morph) { drawMorph(tile.icon, tile.content) }
        }
        Spacer(Modifier.width(10.dp))
        AnimatedContent(tile.text, transitionSpec = textSwap(), label = "tile") { text ->
            BasicText(
                text, Modifier.clearAndSetSemantics {},
                style = TextStyle(color = tile.content, fontSize = 15.sp, fontWeight = FontWeight.Bold),
            )
        }
    }
}

@Composable
private fun UpdateCardView(card: UpdateCard, callback: ControlTabView.Callback?) {
    val fill by animateColorAsState(card.fill, tween(250), label = "updateFill")
    val stroke by animateColorAsState(card.stroke, tween(250), label = "updateStroke")
    Column(
        Modifier
            .fillMaxWidth()
            .background(fill, CARD_SHAPE)
            .border(1.dp, stroke, CARD_SHAPE)
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        BasicText(card.title, style = TextStyle(color = card.titleColor, fontSize = 15.sp, fontWeight = FontWeight.Bold))
        if (card.sub != null) {
            BasicText(card.sub, Modifier.padding(top = 4.dp), style = TextStyle(color = card.subColor, fontSize = 12.sp))
        }
        if (card.permissionNotice) {
            BasicText(
                L10n.t("Turn on Allow from this source, then come back and tap Update", "Bật Cho phép từ nguồn này rồi quay lại bấm Cập nhật"),
                Modifier.padding(top = 4.dp),
                style = TextStyle(color = Color(240, 136, 62), fontSize = 13.sp),
            )
        }
        if (card.progress != null) {
            ProgressBar(card.progress / 100f)
        }
        Row(Modifier.padding(top = 8.dp)) {
            when (card.button) {
                UpdateButton.UPDATE -> CardButton(L10n.t("Update", "Cập nhật"), Color(27, 102, 42)) { callback?.onStartUpdate() }
                UpdateButton.CANCEL -> CardButton(L10n.t("Cancel", "Hủy"), Color(110, 118, 129)) { callback?.onCancelUpdate() }
                UpdateButton.RETRY -> CardButton(L10n.t("Retry", "Thử lại"), Color(26, 84, 176)) { callback?.onStartUpdate() }
            }
        }
    }
}

@Composable
private fun ProgressBar(fraction: Float) {
    val shown by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(250), label = "progress")
    Box(
        Modifier
            .padding(vertical = 8.dp)
            .fillMaxWidth()
            .height(4.dp)
            .background(Color(48, 54, 61), RoundedCornerShape(2.dp))
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) },
    ) {
        Box(
            Modifier
                .fillMaxWidth(shown)
                .fillMaxHeight()
                .background(Color(UiKit.ACCENT), RoundedCornerShape(2.dp)),
        )
    }
}

@Composable
private fun CardButton(text: String, color: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .glowPress(color, 8.dp, onClick = onClick)
            .background(color, shape)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        BasicText(text, style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold))
    }
}
