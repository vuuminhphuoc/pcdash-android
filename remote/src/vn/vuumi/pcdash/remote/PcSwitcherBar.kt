package vn.vuumi.pcdash.remote

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.vuumi.pcdash.i18n.L10n

/**
 * Chips across the top when the home phone controls more than one PC: one per PC, with a dot that is
 * green (pinging) while that PC is on. Tapping a chip makes every tab show and act on that PC.
 */
class PcSwitcherBar(context: Context, private val listener: Listener) : AbstractComposeView(context) {

    fun interface Listener {
        fun onPcSelected(id: String)
    }

    private var pcs by mutableStateOf(emptyList<PcList.Pc>())
    private var selectedId by mutableStateOf<String?>(null)

    /** Shows these PCs, with this one chosen. */
    fun show(list: PcList, selectedId: String?) {
        pcs = list.pcs
        this.selectedId = selectedId
    }

    @Composable
    override fun Content() {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (pc in pcs) {
                key(pc.id) {
                    PcChip(pc, pc.id == selectedId) { listener.onPcSelected(pc.id) }
                }
            }
        }
    }
}

@Composable
private fun PcChip(pc: PcList.Pc, chosen: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val accent = Color(UiKit.ACCENT)
    val fill by animateColorAsState(if (chosen) accent.copy(alpha = 34 / 255f) else Color(UiKit.CARD), label = "chipFill")
    val stroke by animateColorAsState(if (chosen) accent.copy(alpha = 70 / 255f) else Color(UiKit.BORDER), label = "chipStroke")
    val textColor by animateColorAsState(if (chosen) Color.White else Color(UiKit.TEXT), label = "chipText")
    val description = pc.name + ", " + if (pc.online) L10n.t("on", "đang bật") else L10n.t("off or unreachable", "tắt hoặc mất kết nối")
    Row(
        Modifier
            .glowPress(accent, 16.dp, onClick = onClick)
            .background(fill, shape)
            .border(1.dp, stroke, shape)
            .semantics {
                contentDescription = description
                selected = chosen
            }
            .padding(start = 12.dp, top = 8.dp, end = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(Color(if (pc.online) UiKit.OK else UiKit.ERROR), if (pc.online) 2200 else 0, Modifier.size(12.dp))
        Spacer(Modifier.width(8.dp))
        BasicText(
            pc.name, Modifier.clearAndSetSemantics {},
            style = TextStyle(color = textColor, fontSize = 14.sp, fontWeight = FontWeight.Bold),
            overflow = TextOverflow.Ellipsis, maxLines = 1,
        )
    }
}
