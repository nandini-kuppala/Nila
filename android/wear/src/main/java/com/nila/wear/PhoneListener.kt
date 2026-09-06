package com.nila.wear

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Receives state and alerts from the phone.
 *
 * A WearableListenerService rather than a foreground activity subscription: the
 * whole value of routing alerts to a wrist is that they arrive when nobody is
 * looking at anything, so the watch app must be woken by the message rather than
 * needing to already be open.
 */
class PhoneListener : WearableListenerService() {

    companion object {
        private const val TAG = "PhoneListener"

        /** Latest state, read by the watch UI whenever it happens to be open. */
        val state = MutableStateFlow<WearProtocol.State?>(null)
    }

    override fun onMessageReceived(event: MessageEvent) {
        val decoded = WearProtocol.State.decode(event.data)
        if (decoded == null) {
            Log.w(TAG, "undecodable message on ${event.path}")
            return
        }
        state.value = decoded

        if (event.path == WearProtocol.PATH_ALERT) {
            buzz(decoded.severity)
        }
        Log.i(TAG, "${event.path}: ${decoded.headline}")
    }

    /**
     * Distinct pattern per severity.
     *
     * Amplitude is not varied because many watches ignore it; timing is the
     * channel that reliably survives, so the patterns differ in rhythm and
     * length rather than strength.
     */
    private fun buzz(severity: Int) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                .defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        val pattern = WearProtocol.patternFor(severity)
        runCatching {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }.onFailure { Log.w(TAG, "vibrate failed", it) }
    }
}
