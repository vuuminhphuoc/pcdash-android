package vn.vuumi.pcdash.remote

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import java.util.Locale
import vn.vuumi.pcdash.i18n.L10n
import vn.vuumi.pcdash.net.PcnetState
import vn.vuumi.pcdash.ui.Tap

/**
 * The Control tab: the PC's state with a refresh button, the update card and the power tiles. The
 * screen is drawn with Compose ([ControlScreen]); the activity drives it through these calls.
 */
class ControlTabView(context: Context, private val callback: Callback?) : TabContent {

    interface Callback {
        fun onActionClicked(action: String)

        /** The refresh button: fetch the status now (and reconnect if it was failing). */
        fun onRefresh()
        fun onStartUpdate()
        fun onCancelUpdate()
    }

    private val state = ControlState()
    private val handler = Handler(Looper.getMainLooper())
    private val hideActionLine = Runnable { state.actionLine = null }
    // Each tile goes back to normal a moment after showing how its command went.
    private val restore = state.tiles.associateWith { tile -> Runnable { idle(tile) } }
    private val composeView = ComposeView(context).apply {
        setContent { ControlScreen(state, callback) }
    }

    /** The command is on its way to the home phone: spinner on its tile, the other tiles wait. */
    fun showSending(action: String) {
        for (tile in state.tiles) {
            if (tile.action == action) {
                busy(tile, L10n.t("Sending...", "Đang gửi..."))
            } else {
                // A new command replaces whatever the last one was still showing.
                idle(tile)
                tile.available = false
            }
        }
        setActionLine(null, 0, false)
    }

    /** The home phone took the command; the tile keeps spinning while the PC changes state. */
    fun showWaiting(action: String, line: String?) {
        for (tile in state.tiles) {
            if (tile.action == action) {
                busy(tile, L10n.t("Waiting...", "Đang chờ..."))
            } else if (tile.phase == TilePhase.IDLE) {
                tile.available = true
            }
        }
        setActionLine(line, UiKit.ACCENT, false)
    }

    /** How the command went: a tick or a cross on its tile for a moment, and a line under the status. */
    fun showOutcome(action: String, ok: Boolean, tileText: String, line: String?, lineColor: Int) {
        for (tile in state.tiles) {
            if (tile.action == action) {
                result(tile, ok, tileText)
            } else if (tile.phase == TilePhase.IDLE) {
                tile.available = true
            }
        }
        setActionLine(line, lineColor, true)
    }

    /** Back to plain tiles (the command was dropped when the app left the screen). */
    fun resetActions() {
        state.tiles.forEach(::idle)
        setActionLine(null, 0, false)
    }

    // Command in flight or PC changing state: spinner, short status, taps blocked.
    private fun busy(tile: PowerTile, status: String) {
        handler.removeCallbacks(restore.getValue(tile))
        tile.phase = TilePhase.BUSY
        tile.seq++
        tile.text = status
        tile.available = true
        composeView.announceForAccessibility(status)
    }

    // How it went, for a moment; then the tile is back to normal.
    private fun result(tile: PowerTile, ok: Boolean, status: String) {
        handler.removeCallbacks(restore.getValue(tile))
        tile.phase = if (ok) TilePhase.OK else TilePhase.FAILED
        tile.seq++
        tile.text = status
        tile.available = true
        Tap.outcome(composeView, ok)
        composeView.announceForAccessibility(status)
        handler.postDelayed(restore.getValue(tile), RESULT_HOLD_MS)
    }

    private fun idle(tile: PowerTile) {
        handler.removeCallbacks(restore.getValue(tile))
        if (tile.phase != TilePhase.IDLE || tile.text != tile.label) {
            tile.phase = TilePhase.IDLE
            tile.seq++
        }
        tile.text = tile.label
        tile.available = true
    }

    private fun setActionLine(text: String?, color: Int, autoHide: Boolean) {
        handler.removeCallbacks(hideActionLine)
        if (text.isNullOrEmpty()) {
            state.actionLine = null
            return
        }
        state.actionLine = ActionLine(text, Color(color))
        if (autoHide) {
            handler.postDelayed(hideActionLine, 10_000L)
        }
    }

    override fun view(): View = composeView

    override fun onShow() {}

    override fun onHide() {}

    // Status line and dot: green pings slowly while the PC is on, amber quickly while connecting.
    private fun setStatus(text: String, color: Int) {
        state.status = text
        state.statusColor = Color(color)
        state.statusPingMs = when (color) {
            UiKit.OK -> 1800
            UiKit.WARN -> 900
            else -> 0
        }
    }

    /** Spins the refresh button while a fresh status is on its way. */
    fun setRefreshing(refreshing: Boolean) {
        state.refreshing = refreshing
    }

    /**
     * This phone's tailnet is not up yet, so there is no status to ask for.
     *
     * @param tailnet its state, or null while it is being restarted
     */
    fun showConnecting(tailnet: PcnetState?) {
        when {
            tailnet == null -> {
                setStatus(L10n.t("Reconnecting...", "Đang kết nối lại..."), UiKit.WARN)
                state.detail = L10n.t("Restarting the Tailscale connection", "Đang khởi động lại kết nối Tailscale")
            }
            tailnet.isNeedsLogin -> {
                setStatus(L10n.t("Tailscale sign-in needed", "Cần đăng nhập lại Tailscale"), UiKit.ERROR)
                state.detail = L10n.t("Open More > Sign in to Tailscale again", "Vào Thêm > Đăng nhập lại Tailscale")
                setRefreshing(false)
                return
            }
            tailnet.isError -> {
                setStatus(L10n.t("Tailscale error", "Lỗi Tailscale"), UiKit.ERROR)
                state.detail = tailnet.error.takeUnless { it.isNullOrEmpty() } ?: L10n.t("Tap refresh to try again", "Bấm làm mới để thử lại")
                setRefreshing(false)
                return
            }
            else -> {
                setStatus(L10n.t("Connecting to Tailscale...", "Đang kết nối Tailscale..."), UiKit.WARN)
                state.detail = L10n.t("Usually a few seconds", "Thường chỉ mất vài giây")
            }
        }
        setRefreshing(true)
    }

    /**
     * @param hostPeer the home phone in the Tailscale device list, when it did not answer (may be null)
     */
    fun updateStatus(status: RemoteApiClient.RemoteStatus?, error: Exception?, hostPeer: TailnetPeers.Peer?) {
        if (status != null) {
            if (status.online) {
                setStatus(L10n.t("PC is ON", "PC đang BẬT"), UiKit.OK)
                state.detail = L10n.t("Port: ", "Cổng: ") + (status.port ?: 8799) +
                    L10n.t("  ·  Latency: ", "  ·  Độ trễ: ") + status.rttMs + " ms"
            } else {
                setStatus(L10n.t("PC is OFF / not responding", "PC đang TẮT / không phản hồi"), UiKit.ERROR)
                state.detail = L10n.t("Cannot reach the PC", "Không thể kết nối đến PC")
            }
        } else if (error != null) {
            val shortMsg = error.message.takeUnless { it.isNullOrEmpty() } ?: L10n.t("Timed out", "Hết thời gian chờ (timeout)")
            if (hostPeer != null && !hostPeer.online) {
                // The device list says why: the phone itself is off the network.
                setStatus(L10n.t("Home phone is offline", "Máy ở nhà đang offline"), UiKit.ERROR)
                val ago = TailnetPeers.ago(hostPeer.lastSeenMs, System.currentTimeMillis())
                state.detail = L10n.t("Tailscale shows it offline", "Tailscale báo máy này offline") +
                    if (ago.isEmpty()) "" else L10n.t(" · last seen ", " · lần cuối online ") + ago
            } else if (hostPeer != null) {
                setStatus(L10n.t("PCDash host is not answering", "App PCDash ở nhà không phản hồi"), UiKit.ERROR)
                state.detail = L10n.t("The home phone is online in Tailscale. ", "Máy ở nhà vẫn online trên Tailscale. ") + shortMsg
            } else {
                setStatus(L10n.t("Cannot reach the home phone", "Không kết nối được tới máy ở nhà"), UiKit.ERROR)
                state.detail = shortMsg
            }
        }
    }

    fun updateUpdateCardUI(
        updateState: RemoteMainActivity.UpdateState, availableUpdate: UpdateInfo?,
        currentDownloadProgress: Int, currentDownloadBytes: Long, updateErrorMessage: String?,
    ) {
        val text = Color(230, 237, 243)
        val muted = Color(UiKit.MUTED)
        val error = Color(UiKit.ERROR)
        state.update = when (updateState) {
            RemoteMainActivity.UpdateState.IDLE -> null
            RemoteMainActivity.UpdateState.AVAILABLE -> availableUpdate?.let {
                val sizeMb = String.format(Locale.US, "%.1f", it.size / (1024.0 * 1024.0))
                UpdateCard(
                    Color(13, 28, 19), Color(27, 102, 42),
                    L10n.t("Update available: v", "Có bản cập nhật v") + it.versionName + " (" + sizeMb + " MB)", text,
                    null, muted, false, null, UpdateButton.UPDATE,
                )
            }
            RemoteMainActivity.UpdateState.DOWNLOADING -> {
                val readMb = currentDownloadBytes / (1024.0 * 1024.0)
                val totalMb = if (availableUpdate != null && availableUpdate.size > 0) availableUpdate.size / (1024.0 * 1024.0) else 0.0
                UpdateCard(
                    Color(13, 22, 36), Color(26, 84, 176),
                    L10n.t("Downloading update v", "Đang tải bản cập nhật v") + (availableUpdate?.versionName ?: "") + "...", text,
                    String.format(Locale.US, L10n.t("Downloading: %.1f / %.1f MB (%d%%)", "Đang tải: %.1f / %.1f MB (%d%%)"), readMb, totalMb, currentDownloadProgress),
                    muted, false, currentDownloadProgress, UpdateButton.CANCEL,
                )
            }
            RemoteMainActivity.UpdateState.INSTALL_PERMISSION -> UpdateCard(
                Color(32, 23, 12), Color(120, 70, 30),
                L10n.t("Install permission needed", "Yêu cầu quyền cài đặt"), Color(240, 136, 62),
                null, muted, true, null, UpdateButton.UPDATE,
            )
            RemoteMainActivity.UpdateState.ERROR -> UpdateCard(
                Color(34, 20, 22), Color(140, 50, 46),
                L10n.t("Update failed", "Cập nhật không thành công"), error,
                updateErrorMessage ?: L10n.t("Something went wrong", "Đã xảy ra lỗi"), error,
                false, null, UpdateButton.RETRY,
            )
        }
    }

    companion object {
        /** How long a tile shows how its command went. */
        const val RESULT_HOLD_MS = 2500L
    }
}
