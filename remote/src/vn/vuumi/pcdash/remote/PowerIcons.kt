package vn.vuumi.pcdash.remote

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** The Control tab's icons, drawn with one colour so they stay sharp and match the label. */
enum class PowerIcon { WAKE, SLEEP, HIBERNATE, SHUTDOWN, RESTART, LOCK, LOGOFF, ABORT, REFRESH }

/** Draws [icon] in the square of side [s] at [topLeft]. */
internal fun DrawScope.drawPowerIcon(icon: PowerIcon, color: Color, topLeft: Offset, s: Float) {
    if (s <= 0f) return
    val l = topLeft.x
    val t = topLeft.y
    val width = s * 0.1f
    val stroke = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun at(x: Float, y: Float) = Offset(l + s * x, t + s * y)
    fun polyline(vararg points: Float) = Path().apply {
        moveTo(l + s * points[0], t + s * points[1])
        for (i in 2 until points.size step 2) lineTo(l + s * points[i], t + s * points[i + 1])
    }
    when (icon) {
        PowerIcon.WAKE -> {
            val bolt = polyline(0.58f, 0.05f, 0.2f, 0.56f, 0.48f, 0.56f, 0.4f, 0.95f, 0.8f, 0.42f, 0.52f, 0.42f)
            bolt.close()
            drawPath(bolt, color)
        }
        PowerIcon.SLEEP -> {
            drawPath(polyline(0.12f, 0.32f, 0.62f, 0.32f, 0.12f, 0.88f, 0.62f, 0.88f), color, style = stroke)
            drawPath(
                polyline(0.68f, 0.1f, 0.92f, 0.1f, 0.68f, 0.38f, 0.92f, 0.38f), color,
                style = Stroke(width * 0.8f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
        PowerIcon.HIBERNATE -> {
            val disc = Path().apply { addOval(Rect(at(0.5f, 0.5f), s * 0.42f)) }
            val cut = Path().apply { addOval(Rect(at(0.68f, 0.36f), s * 0.36f)) }
            val moon = Path()
            moon.op(disc, cut, PathOperation.Difference)
            drawPath(moon, color)
        }
        PowerIcon.SHUTDOWN -> {
            drawArc(color, -60f, 300f, false, topLeft = at(0.14f, 0.16f), size = Size(s * 0.72f, s * 0.72f), style = stroke)
            drawLine(color, at(0.5f, 0.06f), at(0.5f, 0.5f), width, cap = StrokeCap.Round)
        }
        PowerIcon.RESTART -> drawPowerIcon(PowerIcon.REFRESH, color, topLeft, s)
        PowerIcon.LOCK -> {
            // A padlock: a solid body with a shackle above it.
            drawArc(color, 180f, 180f, false, topLeft = at(0.3f, 0.1f), size = Size(s * 0.4f, s * 0.5f), style = stroke)
            val body = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(at(0.18f, 0.44f), at(0.82f, 0.92f)), androidx.compose.ui.geometry.CornerRadius(s * 0.08f))) }
            drawPath(body, color)
        }
        PowerIcon.LOGOFF -> {
            // A door frame open on the right, with an arrow leaving it.
            drawPath(polyline(0.46f, 0.12f, 0.14f, 0.12f, 0.14f, 0.88f, 0.46f, 0.88f), color, style = stroke)
            drawLine(color, at(0.36f, 0.5f), at(0.88f, 0.5f), width, cap = StrokeCap.Round)
            drawPath(polyline(0.7f, 0.3f, 0.9f, 0.5f, 0.7f, 0.7f), color, style = stroke)
        }
        PowerIcon.ABORT -> {
            drawOval(color, topLeft = at(0.08f, 0.08f), size = Size(s * 0.84f, s * 0.84f), style = stroke)
            drawLine(color, at(0.34f, 0.34f), at(0.66f, 0.66f), width, cap = StrokeCap.Round)
            drawLine(color, at(0.66f, 0.34f), at(0.34f, 0.66f), width, cap = StrokeCap.Round)
        }
        PowerIcon.REFRESH -> {
            // A circular arrow, turning clockwise.
            val c = at(0.5f, 0.5f)
            val r = s * 0.34f
            val start = 20f
            val sweep = 280f
            drawArc(color, start, sweep, false, topLeft = Offset(c.x - r, c.y - r), size = Size(2 * r, 2 * r), style = stroke)
            val end = Math.toRadians((start + sweep).toDouble())
            val tip = Offset(c.x + r * cos(end).toFloat(), c.y + r * sin(end).toFloat())
            // Direction of travel and the normal at the end of the arc.
            val along = Offset(-sin(end).toFloat(), cos(end).toFloat())
            val across = Offset(cos(end).toFloat(), sin(end).toFloat())
            val head = s * 0.16f
            val arrow = Path().apply {
                moveTo(tip.x + along.x * head, tip.y + along.y * head)
                lineTo(tip.x + across.x * head, tip.y + across.y * head)
                lineTo(tip.x - across.x * head, tip.y - across.y * head)
                close()
            }
            drawPath(arrow, color)
        }
    }
}

/** What a power tile shows about its command (see [ControlTabView]). */
enum class TilePhase { IDLE, BUSY, OK, FAILED }

/**
 * A power tile's icon that changes shape with its command: the icon shrinks away into a spinning arc
 * while the command runs, the arc closes and a tick draws itself in (with a ring that spreads out) or
 * a cross draws in with a short shake, and then the icon springs back. It lives with the tile, not
 * the composition, so leaving the tab and coming back does not replay a finished change.
 */
class MorphIcon {
    private var iconScale by mutableFloatStateOf(1f)
    private var iconAlpha by mutableFloatStateOf(1f)
    private var spinnerAlpha by mutableFloatStateOf(0f)
    private var spin by mutableFloatStateOf(0f) // 0..1 through one spin cycle
    private var markProgress by mutableFloatStateOf(0f)
    private var markAlpha by mutableFloatStateOf(0f)
    private var markIsCheck by mutableStateOf(true)
    private var ring by mutableFloatStateOf(-1f) // < 0: no ring
    private var shake by mutableFloatStateOf(0f)
    // The last change played to the end.
    private var shownSeq = 0

    /** Plays the change to [phase] (unless change [seq] was already shown) and spins while busy. */
    suspend fun follow(phase: TilePhase, seq: Int) {
        coroutineScope {
            if (phase == TilePhase.BUSY) launch { spinForever() }
            if (seq != shownSeq) {
                when (phase) {
                    TilePhase.BUSY -> toBusy()
                    TilePhase.OK -> toResult(true)
                    TilePhase.FAILED -> toResult(false)
                    TilePhase.IDLE -> toIdle()
                }
                shownSeq = seq
            }
        }
    }

    private suspend fun toBusy() {
        val fromScale = iconScale
        val fromIcon = iconAlpha
        val fromMark = markAlpha
        val fromSpinner = spinnerAlpha
        transition(260, decelerate()) { p ->
            iconScale = lerp(fromScale, 0.3f, p)
            iconAlpha = lerp(fromIcon, 0f, p)
            markAlpha = lerp(fromMark, 0f, p)
            spinnerAlpha = lerp(fromSpinner, 1f, p)
        }
    }

    private suspend fun toResult(ok: Boolean) {
        coroutineScope {
            markIsCheck = ok
            val spinning = launch { spinForever() }
            launch { if (ok) spreadRing() else shakeOnce() }
            val fromSpinner = spinnerAlpha
            val fromIcon = iconAlpha
            transition(420, overshoot(1.4f)) { p ->
                val q = p.coerceIn(0f, 1f)
                spinnerAlpha = lerp(fromSpinner, 0f, min(1f, q * 2.5f))
                iconAlpha = lerp(fromIcon, 0f, q)
                markAlpha = 1f
                markProgress = q
                iconScale = 0.3f
            }
            spinning.cancel()
        }
    }

    private suspend fun toIdle() {
        val fromMark = markAlpha
        val fromSpinner = spinnerAlpha
        val fromScale = iconScale
        val fromIcon = iconAlpha
        transition(380, overshoot(2f)) { p ->
            val q = p.coerceIn(0f, 1f)
            markAlpha = lerp(fromMark, 0f, min(1f, q * 2f))
            spinnerAlpha = lerp(fromSpinner, 0f, q)
            iconAlpha = lerp(fromIcon, 1f, q)
            iconScale = lerp(fromScale, 1f, p) // may overshoot a little: the spring
        }
    }

    // Through the animation clock, so the animator duration scale (and "Remove animations") applies.
    private suspend fun transition(ms: Int, easing: Easing, step: (Float) -> Unit) {
        animate(0f, 1f, animationSpec = tween(ms, easing = easing)) { value, _ -> step(value) }
    }

    // Turns and breathes: the arc grows and shrinks while it goes round; picks up where it stopped.
    private suspend fun spinForever() {
        val from = spin
        animate(from, from + 1f, animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing))) { value, _ ->
            spin = value - floor(value)
        }
    }

    private suspend fun spreadRing() {
        try {
            animate(0f, 1f, animationSpec = tween(650, easing = decelerate(1.5f))) { value, _ -> ring = value }
        } finally {
            ring = -1f
        }
    }

    private suspend fun shakeOnce() {
        try {
            animate(0f, 1f, animationSpec = tween(420, easing = FastOutSlowInEasing)) { p, _ ->
                shake = sin(p * 6 * PI).toFloat() * (1f - p)
            }
        } finally {
            shake = 0f
        }
    }

    /** Draws the icon as it is now, filling the draw area. */
    fun DrawScope.drawMorph(icon: PowerIcon, color: Color) {
        val s = min(size.width, size.height)
        if (s <= 0f) return
        val cx = center.x + shake * s * 0.12f
        val cy = center.y
        val width = s * 0.11f

        if (ring >= 0f) {
            val a = 200f / 255f * (1f - ring)
            if (a > 0f) {
                drawCircle(
                    color.copy(alpha = color.alpha * a), radius = s * (0.45f + 0.55f * ring), center = Offset(cx, cy),
                    style = Stroke(s * 0.07f * (1f - ring) + 1f),
                )
            }
        }
        if (iconAlpha > 0.01f) {
            translate(left = cx - center.x) {
                scale(iconScale, pivot = center) {
                    val topLeft = Offset((size.width - s) / 2f, (size.height - s) / 2f)
                    drawPowerIcon(icon, color.copy(alpha = color.alpha * min(1f, iconAlpha)), topLeft, s)
                }
            }
        }
        if (spinnerAlpha > 0.01f) {
            val r = s * 0.38f
            drawArc(
                color.copy(alpha = color.alpha * min(1f, spinnerAlpha)),
                startAngle = (spin * 720f) % 360f, // two turns per cycle
                sweepAngle = 40f + 230f * (0.5f - 0.5f * cos(2 * PI * spin).toFloat()),
                useCenter = false,
                topLeft = Offset(cx - r, cy - r),
                size = Size(2 * r, 2 * r),
                style = Stroke(width, cap = StrokeCap.Round),
            )
        }
        if (markAlpha > 0.01f && markProgress > 0f) {
            val strokes = if (markIsCheck) CHECK else CROSS
            val markColor = color.copy(alpha = color.alpha * min(1f, markAlpha))
            val origin = Offset(cx - s / 2f, cy - s / 2f)
            // Each stroke of the mark gets an equal share of the time, one after the other.
            val fraction = min(1f, markProgress)
            strokes.forEachIndexed { i, points ->
                val local = ((fraction - i.toFloat() / strokes.size) * strokes.size).coerceIn(0f, 1f)
                if (local > 0f) {
                    drawPath(
                        partial(points, origin, s, local), markColor,
                        style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
            }
        }
    }

    private companion object {
        // In a unit box.
        val CHECK = listOf(listOf(Offset(0.2f, 0.53f), Offset(0.42f, 0.74f), Offset(0.8f, 0.3f)))
        val CROSS = listOf(
            listOf(Offset(0.28f, 0.28f), Offset(0.72f, 0.72f)),
            listOf(Offset(0.72f, 0.28f), Offset(0.28f, 0.72f)),
        )

        fun lerp(a: Float, b: Float, p: Float) = a + (b - a) * p

        // The first part of a polyline, up to [fraction] of its length.
        fun partial(points: List<Offset>, origin: Offset, s: Float, fraction: Float): Path {
            val abs = points.map { origin + it * s }
            var left = abs.zipWithNext { a, b -> (b - a).getDistance() }.sum() * fraction
            val path = Path()
            path.moveTo(abs[0].x, abs[0].y)
            for (k in 1 until abs.size) {
                val seg = abs[k] - abs[k - 1]
                val len = seg.getDistance()
                if (left >= len) {
                    path.lineTo(abs[k].x, abs[k].y)
                    left -= len
                } else {
                    val end = abs[k - 1] + seg * (left / len)
                    path.lineTo(end.x, end.y)
                    break
                }
            }
            return path
        }
    }
}
