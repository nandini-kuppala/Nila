package com.nila.wearlink

/**
 * The phone-to-watch message format.
 *
 * Kept in a file with no Android dependencies and duplicated verbatim into the
 * wear module, so both sides parse the same bytes and the format is testable on
 * the JVM. A hand-rolled pipe-delimited line rather than JSON: the payload is
 * five fields, it must survive a 100 KB Data Layer limit with room to spare, and
 * a parser this small can be read in one sitting.
 */
object WearProtocol {

    const val PATH_STATE = "/nila/state"
    const val PATH_ALERT = "/nila/alert"
    const val PATH_ASK = "/nila/ask"

    const val VERSION = 1

    /**
     * Vibration patterns, per severity.
     *
     * Distinct enough to identify without looking, which is the entire reason
     * alerts go to a wrist rather than a speaker: the phone must stay silent
     * next to a sleeping baby.
     */
    val PATTERN_NOTE = longArrayOf(0, 120)
    val PATTERN_ATTENTION = longArrayOf(0, 200, 120, 200)
    val PATTERN_URGENT = longArrayOf(0, 400, 150, 400, 150, 400)
    val PATTERN_CRITICAL = longArrayOf(0, 700, 120, 700, 120, 700, 120, 700)

    fun patternFor(severity: Int): LongArray = when {
        severity >= 4 -> PATTERN_CRITICAL
        severity == 3 -> PATTERN_URGENT
        severity == 2 -> PATTERN_ATTENTION
        else -> PATTERN_NOTE
    }

    data class State(
        val monitoring: Boolean,
        val headline: String,
        val cryingSeconds: Int,
        val trend: String,
        val severity: Int,
    ) {
        fun encode(): ByteArray = listOf(
            VERSION,
            if (monitoring) 1 else 0,
            cryingSeconds,
            severity,
            trend.replace('|', '/'),
            headline.replace('|', '/'),
        ).joinToString("|").toByteArray()

        companion object {
            fun decode(bytes: ByteArray): State? {
                val parts = String(bytes).split("|")
                if (parts.size < 6) return null
                if (parts[0].toIntOrNull() != VERSION) return null
                return State(
                    monitoring = parts[1] == "1",
                    cryingSeconds = parts[2].toIntOrNull() ?: 0,
                    severity = parts[3].toIntOrNull() ?: 0,
                    trend = parts[4],
                    // Rejoin: a headline may legitimately contain a separator we
                    // replaced, and losing the tail would truncate the alert.
                    headline = parts.drop(5).joinToString("|"),
                )
            }
        }
    }
}
