package com.nila.phonelink

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The guardian-to-parent wire format.
 *
 * Deliberately the same shape as [com.nila.wearlink.WearProtocol]: a
 * pipe-delimited line, no Android imports, parseable on the JVM. That file has
 * earned it -- the format survived a headline containing a delimiter because a
 * test caught it, and the same test can be written here.
 *
 * Two things are added over the watch format, both because this link crosses a
 * Wi-Fi network rather than a bonded Bluetooth pair:
 *
 *  - **A MAC.** Every frame carries an HMAC-SHA256 tag keyed by the pairing
 *    secret. An unauthenticated frame is not an alert, it is a stranger on the
 *    same Wi-Fi able to tell a parent their baby has stopped breathing.
 *  - **A sequence number.** Monotonic per connection, so a replayed frame is
 *    dropped rather than rendered as a live alert.
 *
 * What is *not* here is encryption. The payload is a severity, a duration and a
 * sentence already displayed on both phones' lock screens; hiding it from
 * someone who is already inside the home Wi-Fi buys nothing worth the key
 * exchange. Authenticity is the property that matters, and it is the one
 * provided.
 */
object LinkProtocol {

    const val VERSION = 1

    /**
     * The service the guardian advertises and the parent looks for.
     *
     * NSD requires the `_tcp` suffix and a name under fifteen characters
     * before it; longer registrations fail on some OEM stacks with an opaque
     * error rather than a message.
     */
    const val SERVICE_TYPE = "_nila._tcp"
    const val SERVICE_NAME = "Nila Guardian"

    /**
     * Not 8787. [com.nila.bridge.DeskBridge] owns that one and the two can run
     * at the same time -- a laptop watching the dashboard while a phone holds
     * the alert link is the normal case, not an unusual one.
     */
    const val PORT = 8788

    /** How often the guardian speaks, whether or not anything has changed. */
    const val HEARTBEAT_MS = 3_000L

    /**
     * How long the parent waits in silence before treating the link as dead.
     *
     * Three missed heartbeats. Shorter and a phone that garbage-collects at the
     * wrong moment cries wolf; longer and a monitor can be dead for a quarter of
     * a minute while the receiving phone still looks calm. The build spec asks
     * for ten seconds and ten seconds is defensible: it is under the time it
     * takes to walk to the cot and check.
     */
    const val LINK_TIMEOUT_MS = 10_000L

    /** Frames older than this are refused outright, before the sequence check. */
    const val MAX_FRAME_BYTES = 4_096

    enum class Kind { HELLO, STATE, ALERT, BYE }

    /**
     * What the receiving phone should actually *do*, given a severity.
     *
     * This is the whole question the feature turns on, so it lives in a pure
     * function with a test rather than being scattered across the service as a
     * chain of ifs. The mapping is deliberately steep: most of what the guardian
     * says is not worth waking anybody for, and a monitor that treats every
     * event as an emergency gets silenced within a week -- which is a worse
     * failure than not having sent the alert at all.
     */
    enum class Tier {
        /** Recorded on the parent phone, nothing shown. Fussing is not news. */
        IGNORE,

        /** Heads-up notification, short vibration. The phone does not wake. */
        NOTIFY,

        /**
         * Notification plus a tone on the *alarm* stream, which a silent phone
         * still plays. A parent's phone at 2am is on silent; an alert that
         * rides the notification stream is an alert nobody hears.
         */
        ALARM,

        /**
         * All of the above, plus a full-screen activity over the lock screen.
         * Reserved for level 4, and for nothing else -- see [tierFor].
         */
        FULL_SCREEN,
    }

    /**
     * Severity to behaviour.
     *
     * Mirrors [com.nila.data.Severity]: 1 NOTE, 2 ATTENTION, 3 URGENT,
     * 4 CRITICAL. Taken as an Int rather than the enum so this file stays free
     * of Android imports and can be tested on the JVM.
     *
     * An unknown level falls *down* to IGNORE rather than up. A future guardian
     * sending a severity this parent does not understand should go quiet, not
     * start sounding an alarm for something it cannot describe.
     */
    fun tierFor(severity: Int): Tier = when (severity) {
        4 -> Tier.FULL_SCREEN
        3 -> Tier.ALARM
        2 -> Tier.NOTIFY
        else -> Tier.IGNORE
    }

    /**
     * One message on the link.
     *
     * [sentAtMs] is carried for display and latency only and is never used to
     * validate a frame. Two phones' clocks can differ by minutes -- neither is
     * obliged to be on network time -- and a freshness check against a skewed
     * clock rejects every frame and takes the link down silently. Replay is
     * handled by [seq], which needs no shared clock, and liveness by the
     * receiver's own arrival times.
     */
    data class Frame(
        val kind: Kind,
        val seq: Long,
        val severity: Int,
        val seconds: Int,
        val trend: String,
        val monitoring: Boolean,
        val sentAtMs: Long,
        val title: String,
        val body: String,
    ) {
        val tier: Tier get() = tierFor(severity)

        /** The signed part of the frame, without its tag. */
        internal fun line(): String = listOf(
            VERSION,
            kind.name,
            seq,
            severity,
            seconds,
            clean(trend),
            if (monitoring) 1 else 0,
            sentAtMs,
            clean(title),
            // Last, and so the only field allowed to contain a delimiter --
            // decode rejoins the tail. Advice text is generated prose and will
            // eventually contain a pipe.
            body,
        ).joinToString("|")

        companion object {
            private fun clean(s: String) = s.replace('|', '/').replace('\n', ' ')
        }
    }

    /** `<hex tag>:<line>`, one per line on the socket. */
    fun encode(frame: Frame, key: ByteArray): String {
        val line = frame.line()
        return tag(line, key) + ":" + line
    }

    /**
     * Parse and authenticate one frame, or return null.
     *
     * Null for every failure -- wrong tag, wrong version, malformed, truncated.
     * The caller's only correct response to any of them is to drop the frame and
     * keep reading, and distinguishing them would only tempt somebody into
     * logging the difference, which is how a MAC check becomes an oracle.
     */
    fun decode(raw: String, key: ByteArray): Frame? {
        if (raw.length > MAX_FRAME_BYTES) return null
        val cut = raw.indexOf(':')
        if (cut <= 0) return null

        val line = raw.substring(cut + 1)
        if (!MessageDigest.isEqual(
                raw.substring(0, cut).toByteArray(),
                tag(line, key).toByteArray(),
            )
        ) return null

        val parts = line.split("|")
        if (parts.size < 10) return null
        if (parts[0].toIntOrNull() != VERSION) return null
        val kind = runCatching { Kind.valueOf(parts[1]) }.getOrNull() ?: return null

        return Frame(
            kind = kind,
            seq = parts[2].toLongOrNull() ?: return null,
            severity = parts[3].toIntOrNull() ?: return null,
            seconds = parts[4].toIntOrNull() ?: 0,
            trend = parts[5],
            monitoring = parts[6] == "1",
            sentAtMs = parts[7].toLongOrNull() ?: 0L,
            title = parts[8],
            body = parts.drop(9).joinToString("|"),
        )
    }

    private fun tag(line: String, key: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(key, "HmacSHA256"))
        }
        // Truncated to 128 bits. Full-length would double the tag for no gain
        // against an attacker who gets one guess per TCP connection.
        return mac.doFinal(line.toByteArray())
            .take(16)
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Turn the six digits typed on the parent phone into the link key.
     *
     * Stretched, even though six digits is a million possibilities and no
     * stretching makes that a strong secret. The point is not to survive an
     * offline attack on the code -- it is that the code is used once, on a
     * network the user controls, and the long-lived value stored on both phones
     * afterwards is this derived key rather than the digits themselves.
     *
     * The honest limit: anyone already on your Wi-Fi who watches a pairing and
     * tries a million codes against a recorded frame will find it. The
     * mitigation is that the guardian only accepts pairings for sixty seconds
     * after you ask it to, not that the code is strong.
     */
    fun deriveKey(code: String): ByteArray {
        val spec = PBEKeySpec(code.toCharArray(), SALT, 120_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
    }

    private val SALT = "nila-pair-v1".toByteArray()

    /** Six digits, zero-padded, from a source the caller chooses. */
    fun formatCode(value: Int): String = "%06d".format(value % 1_000_000)
}
