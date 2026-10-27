package vn.vuumi.pcdash.remote

/** The host's PCs from GET /v1/pcs: id, name, and the state of each. */
class PcList private constructor(@JvmField val pcs: List<Pc>) {

    data class Pc(
        @JvmField val id: String,
        @JvmField val name: String,
        @JvmField val online: Boolean,
        @JvmField val port: Int?,
        @JvmField val rttMs: Long,
    ) {
        /** The same answer /v1/status would give for this PC. */
        fun toStatus() = RemoteApiClient.RemoteStatus(online, port, rttMs, null)
    }

    /** The PC with this id, else the first; null when the list is empty. */
    fun pick(id: String?): Pc? = pcs.firstOrNull { it.id == id } ?: pcs.firstOrNull()

    companion object {
        /** Null for missing or malformed JSON. */
        @JvmStatic
        fun parse(json: String?): PcList? {
            val list = MetricsParser.parseMap(json)?.get("pcs") as? List<*> ?: return null
            val pcs = list.mapNotNull { o ->
                val m = o as? Map<*, *> ?: return@mapNotNull null
                val id = (m["id"] as? String)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                Pc(
                    id,
                    (m["name"] as? String)?.takeIf { it.isNotEmpty() } ?: "PC",
                    m["online"] == true,
                    (m["port"] as? Number)?.toInt(),
                    (m["rtt_ms"] as? Number)?.toLong() ?: 0L,
                )
            }
            return PcList(pcs)
        }
    }
}
