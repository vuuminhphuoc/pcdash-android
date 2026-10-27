package vn.vuumi.pcdash.remote

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.pow
import kotlinx.coroutines.launch
import vn.vuumi.pcdash.ui.GlowDrawable
import vn.vuumi.pcdash.ui.Tap

// Compose pieces the Control tab and the PC bar share: the press glow and the status dot.

/** android.view.animation.DecelerateInterpolator as an [Easing]. */
internal fun decelerate(factor: Float = 1f) = Easing { t -> 1f - (1f - t).pow(2f * factor) }

/** android.view.animation.OvershootInterpolator as an [Easing]: goes past 1 and settles back. */
internal fun overshoot(tension: Float) = Easing { t ->
    val x = t - 1f
    x * x * ((tension + 1f) * x + tension) + 1f
}

/** Taps closer together than [Tap.REPEAT_GUARD_MS] count as one, as with [Tap.guarded]. */
private class TapGuard {
    private var lastMs = 0L
    private var tapped = false

    fun allow(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (tapped && now - lastMs < Tap.REPEAT_GUARD_MS) return false
        tapped = true
        lastMs = now
        return true
    }
}

/**
 * How a tap feels here, as [Tap] does for views: the control shrinks a little while pressed and lights
 * up where the finger is (a lighter shade of [color] spreads from the touch point and the rim
 * brightens, as [GlowDrawable] draws it), ticks, and ignores a second tap within
 * [Tap.REPEAT_GUARD_MS]. With [breathing] the rim glows slowly in and out while the action runs.
 */
@Composable
internal fun Modifier.glowPress(
    color: Color,
    corner: Dp,
    enabled: Boolean = true,
    breathing: Boolean = false,
    onClick: () -> Unit,
): Modifier {
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val guard = remember { TapGuard() }
    val pressed by interaction.collectIsPressedAsState()
    var hot by remember { mutableStateOf(Offset.Unspecified) }
    val spread = remember { Animatable(0f) }
    LaunchedEffect(interaction) {
        val scope = this
        interaction.interactions.collect { i ->
            if (i is PressInteraction.Press) {
                hot = i.pressPosition
                scope.launch {
                    spread.snapTo(0f)
                    spread.animateTo(1f, tween(520, easing = decelerate(1.8f)))
                }
            }
        }
    }
    val lit = pressed && enabled
    val strength = animateFloatAsState(if (lit) 1f else 0f, tween(if (lit) 120 else 480, easing = decelerate()), label = "glow")
    val scale = animateFloatAsState(if (lit) 0.97f else 1f, tween(if (lit) 70 else 160, easing = decelerate()), label = "press")
    val breath: State<Float>? = if (breathing) {
        rememberInfiniteTransition(label = "breath")
            .animateFloat(0.15f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "breath")
    } else {
        null
    }
    val light = remember(color) { Color(GlowDrawable.lightOf(color.toArgb())) }
    return this
        .graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
        .drawWithContent {
            drawContent()
            val st = strength.value
            val br = breath?.value ?: 0f
            if (st <= 0.01f && br <= 0.01f) return@drawWithContent
            val radiusPx = corner.toPx()
            val rimPx = 1.5.dp.toPx()
            val outline = Path().apply { addRoundRect(RoundRect(size.toRect(), CornerRadius(radiusPx))) }
            clipPath(outline) {
                if (st > 0.01f) {
                    val reach = max(size.width, size.height) * (0.25f + 0.85f * spread.value)
                    drawRect(
                        Brush.radialGradient(
                            0f to light.copy(alpha = 150 / 255f),
                            0.45f to light.copy(alpha = 60 / 255f),
                            1f to light.copy(alpha = 0f),
                            center = if (hot.isSpecified) hot else center,
                            radius = reach,
                        ),
                        alpha = st,
                    )
                }
                val rim = max(st * 0.9f, br * 0.75f)
                if (rim > 0.01f) {
                    val inset = rimPx / 2f
                    drawRoundRect(
                        light,
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - rimPx, size.height - rimPx),
                        cornerRadius = CornerRadius(max(0f, radiusPx - inset)),
                        style = Stroke(rimPx),
                        alpha = rim,
                    )
                }
            }
        }
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button) {
            if (guard.allow()) {
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            }
        }
}

/**
 * A small coloured dot that sends out a soft ring every [pingMs] while something is alive (the PC is
 * on, a connection is being made) and sits still when [pingMs] is 0.
 */
@Composable
internal fun StatusDot(color: Color, pingMs: Int, modifier: Modifier = Modifier) {
    val dotColor by animateColorAsState(color, tween(300), label = "dot")
    val ping: State<Float>? = if (pingMs > 0) {
        key(pingMs) {
            rememberInfiniteTransition(label = "ping")
                .animateFloat(0f, 1f, infiniteRepeatable(tween(pingMs, easing = decelerate(1.4f))), label = "ping")
        }
    } else {
        null
    }
    Canvas(modifier.clearAndSetSemantics {}) {
        val r = 5.dp.toPx()
        val p = ping?.value
        if (p != null) {
            drawCircle(dotColor.copy(alpha = 0.55f * (1f - p)), radius = r * (1f + 1.8f * p))
        }
        drawCircle(dotColor, radius = r)
    }
}
