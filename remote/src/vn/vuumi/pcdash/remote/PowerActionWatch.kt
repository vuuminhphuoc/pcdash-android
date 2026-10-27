package vn.vuumi.pcdash.remote

/**
 * After the home phone accepted a power command: waits until the PC really is on (Wake) or off (Sleep,
 * Hibernate, Shut down), as seen in the status the app polls, or gives up after a while. A command
 * with nothing to wait for (Cancel shutdown) has no watch.
 */
class PowerActionWatch private constructor(
    @JvmField val action: String,
    @JvmField val expectOnline: Boolean,
    private val deadlineMs: Long,
) {
    enum class Progress {
        WAITING,
        REACHED,
        TIMED_OUT,
    }

    /**
     * @param pcOnline the PC's state from the latest status, or null when the home phone did not answer
     */
    fun check(pcOnline: Boolean?, nowMs: Long): Progress = when {
        pcOnline == expectOnline -> Progress.REACHED
        nowMs >= deadlineMs -> Progress.TIMED_OUT
        else -> Progress.WAITING
    }

    companion object {
        const val WAKE_TIMEOUT_MS = 120_000L
        const val OFF_TIMEOUT_MS = 90_000L

        /** A watch for the command, or null when there is no PC state to wait for. */
        @JvmStatic
        fun start(action: String?, nowMs: Long): PowerActionWatch? = when (action) {
            "wake" -> PowerActionWatch(action, true, nowMs + WAKE_TIMEOUT_MS)
            "sleep", "hibernate", "shutdown" -> PowerActionWatch(action, false, nowMs + OFF_TIMEOUT_MS)
            else -> null
        }
    }
}
