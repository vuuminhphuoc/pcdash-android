package vn.vuumi.pcdash.remote

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import vn.vuumi.pcdash.i18n.L10n

/**
 * The Tailscale device list as this phone's tailnet node sees it (PcnetBridge.peersJson()): every
 * other device of the tailnet and whether the coordination server has it online. Reading it sends
 * nothing to the devices themselves, so it answers "is the home phone online" without waiting on
 * a request that may never come back.
 */
class TailnetPeers private constructor(
    @JvmField val backendState: String,
    @JvmField val peers: List<Peer>,
) {
    class Peer(
        @JvmField val hostName: String,
        @JvmField val ips: List<String>,
        @JvmField val online: Boolean,
        /** When the device was last connected, if it is offline; -1 when unknown. */
        @JvmField val lastSeenMs: Long,
        /** The home phone this remote is paired with. */
        @JvmField val target: Boolean,
    )

    /** The paired home phone, or null when it is not in the list (yet). */
    fun host(): Peer? = peers.firstOrNull { it.target }

    companion object {
        /** Null for missing or malformed JSON. */
        @JvmStatic
        fun parse(json: String?): TailnetPeers? {
            val root = MetricsParser.parseMap(json) ?: return null
            val list = root["peers"] as? List<*> ?: return null
            val peers = list.filterIsInstance<Map<*, *>>().map { m ->
                Peer(
                    m["hostName"] as? String ?: "",
                    (m["ips"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                    m["online"] == true,
                    parseTime(m["lastSeen"]),
                    m["target"] == true,
                )
            }
            return TailnetPeers(root["backendState"] as? String ?: "", peers)
        }

        /** "5 min ago" style text for a last-seen time; empty when unknown. */
        @JvmStatic
        fun ago(thenMs: Long, nowMs: Long): String {
            if (thenMs < 0) return ""
            val min = maxOf(0L, nowMs - thenMs) / 60_000L
            if (min < 1) return L10n.t("just now", "vừa xong")
            if (min < 60) return L10n.t("$min min ago", "$min phút trước")
            val hours = min / 60
            if (hours < 48) return L10n.t("$hours h ago", "$hours giờ trước")
            val days = hours / 24
            return L10n.t("$days days ago", "$days ngày trước")
        }

        // RFC 3339 in UTC, as the Go side writes it ("2026-10-27T13:05:00Z"). java.time needs API 26.
        private fun parseTime(value: Any?): Long {
            if (value !is String || value.isEmpty()) return -1L
            val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            f.timeZone = TimeZone.getTimeZone("UTC")
            return try {
                f.parse(value)?.time ?: -1L
            } catch (e: ParseException) {
                -1L
            }
        }
    }
}
