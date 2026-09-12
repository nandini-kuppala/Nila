package com.nila.phonelink

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import java.io.Closeable

/**
 * The sound the parent phone makes, on the stream that still works at 2am.
 *
 * A notification's own sound rides the notification stream, which is exactly the
 * stream a parent silences before going to bed. Playing on `USAGE_ALARM` instead
 * is the difference between an alert that is heard and an alert that is
 * technically delivered: the alarm stream survives the ringer being off, which
 * is the same reason an alarm clock app uses it.
 *
 * It keeps sounding until somebody stops it, with a ceiling. Both halves matter
 * -- a single chime is missed by a sleeping adult, and a tone with no ceiling
 * runs the battery flat in a room nobody is in.
 */
class AlertPlayer(private val context: Context) : Closeable {

    companion object {
        private const val TAG = "AlertPlayer"

        /**
         * Stop by itself after two minutes.
         *
         * If two minutes of an alarm two rooms away has not produced a person,
         * the phone continuing to sound is not going to. The notification stays
         * up, so the alert is not lost -- only the noise stops.
         */
        private const val MAX_MS = 120_000L

        /** Repeats from index 1, so the pattern loops without the leading pause. */
        private val PATTERN_URGENT = longArrayOf(0, 500, 400, 500, 1_200)
        private val PATTERN_CRITICAL = longArrayOf(0, 800, 200, 800, 200, 800, 700)
    }

    private var player: MediaPlayer? = null
    private var stopAt: Thread? = null

    @Volatile var isSounding: Boolean = false
        private set

    private val vibrator: Vibrator? by lazy {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                    as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
        }.getOrNull()
    }

    /**
     * Start sounding for a tier. Anything below ALARM makes no noise here --
     * a NOTIFY-tier alert is a notification, and the notification channel
     * already carries its own short vibration.
     */
    fun sound(tier: LinkProtocol.Tier) {
        if (tier != LinkProtocol.Tier.ALARM && tier != LinkProtocol.Tier.FULL_SCREEN) return
        stop()
        isSounding = true
        playTone()
        buzz(if (tier == LinkProtocol.Tier.FULL_SCREEN) PATTERN_CRITICAL else PATTERN_URGENT)
        stopAt = kotlin.concurrent.thread(name = "nila-alert-timeout") {
            runCatching { Thread.sleep(MAX_MS) }.onSuccess { stop() }
        }
    }

    private fun playTone() {
        // The device's own alarm sound rather than a bundled asset: it is the
        // sound this person already wakes up to, it is guaranteed to exist, and
        // it costs nothing in the APK. Falls back through the ringtone types
        // because a few OEM builds ship no default alarm.
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return

        runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, uri)
                isLooping = true
                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "player error $what/$extra")
                    true
                }
                prepare()
                start()
            }
        }.onFailure { Log.w(TAG, "could not sound the alarm", it) }
    }

    /**
     * Buzz on the *alarm* usage, for the same reason the tone plays there.
     *
     * The usage is not decoration: a vibration tagged as a notification is
     * suppressed by Do Not Disturb, and this has to reach somebody who put their
     * phone face down at ten o'clock. VibrationAttributes from API 33, which is
     * where AudioAttributes was deprecated for this call; the older overload
     * carries the same meaning on everything below.
     */
    private fun buzz(pattern: LongArray) {
        val v = vibrator ?: return
        runCatching {
            // Repeats from index 1, so the loop skips the leading zero pause.
            val effect = VibrationEffect.createWaveform(pattern, 1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                v.vibrate(
                    effect,
                    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
                )
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(
                    effect,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
            }
        }.onFailure { Log.w(TAG, "could not vibrate", it) }
    }

    fun stop() {
        isSounding = false
        stopAt?.interrupt()
        stopAt = null
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { vibrator?.cancel() }
    }

    override fun close() = stop()
}
