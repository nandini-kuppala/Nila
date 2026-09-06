package com.nila.actions

import android.content.Context
import android.hardware.ConsumerIrManager
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.irStore by preferencesDataStore("ir_remote")

/**
 * The infrared blaster, if this device will let a third-party app use it.
 *
 * Worth attempting because it is the one thing that lets the phone change the
 * room rather than just describe it -- turning a fan or air conditioner down
 * when a baby is restless and the room is warm. No Pixel, Galaxy or iPhone
 * flagship ships an IR emitter; several iQOO and Xiaomi devices do.
 *
 * It is also genuinely uncertain. ConsumerIrManager is deprecated from Android
 * 12 and several OEMs no longer expose it to third-party apps even on hardware
 * that physically has the emitter. So [availability] reports precisely which of
 * those situations we are in, the UI shows it honestly, and nothing else in the
 * app depends on this working.
 */
class IrActuator(private val context: Context) {

    companion object {
        private const val TAG = "IrActuator"

        /**
         * NEC protocol, 38 kHz. Carrier pulse/space pairs in microseconds:
         * 9 ms lead-in, 4.5 ms space, then 32 bits where a 1 is a long space.
         * Address and command below are the generic "power" frame -- real use
         * needs the target device's own code, which the user teaches in settings.
         */
        private const val NEC_CARRIER_HZ = 38_000

        private val ADDRESS = intPreferencesKey("nec_address")
        private val COMMAND = intPreferencesKey("nec_command")
    }

    sealed interface Availability {
        data object Ready : Availability
        data object NoEmitter : Availability
        data class Blocked(val reason: String) : Availability
    }

    private val manager = runCatching {
        context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
    }.getOrNull()

    val availability: Availability by lazy {
        val mgr = manager ?: return@lazy Availability.Blocked("service unavailable")
        try {
            if (!mgr.hasIrEmitter()) return@lazy Availability.NoEmitter
            val freqs = mgr.carrierFrequencies
            if (freqs.isNullOrEmpty()) {
                return@lazy Availability.Blocked("emitter present, no frequencies exposed")
            }
            Availability.Ready
        } catch (t: Throwable) {
            Availability.Blocked(t.message ?: t::class.java.simpleName)
        }
    }

    val isReady: Boolean get() = availability is Availability.Ready

    /** Human-readable state, shown in settings rather than hidden in a log. */
    fun describe(): String = when (val a = availability) {
        Availability.Ready -> "Ready - this phone can control your fan or AC"
        Availability.NoEmitter -> "This phone has no infrared emitter"
        is Availability.Blocked -> "Blocked by the system: ${a.reason}"
    }

    /** Encode a 32-bit NEC frame into the alternating on/off pattern transmit() wants. */
    fun necPattern(address: Int, command: Int): IntArray {
        val bits = mutableListOf(9000, 4500)                 // lead-in burst + space
        val payload = ((address and 0xFF)) or
            ((address.inv() and 0xFF) shl 8) or
            ((command and 0xFF) shl 16) or
            ((command.inv() and 0xFF) shl 24)

        for (i in 0 until 32) {
            bits += 560
            bits += if ((payload shr i) and 1 == 1) 1690 else 560
        }
        bits += 560                                          // stop bit
        return bits.toIntArray()
    }

    fun transmit(pattern: IntArray, carrierHz: Int = NEC_CARRIER_HZ): Boolean {
        val mgr = manager ?: return false
        if (!isReady) return false
        return try {
            mgr.transmit(carrierHz, pattern)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "transmit failed", t)
            false
        }
    }

    fun sendNec(address: Int, command: Int): Boolean =
        transmit(necPattern(address, command))

    /**
     * The code the parent taught this app for their own fan or air conditioner.
     *
     * There is no universal "turn the fan down" frame -- every remote uses its
     * own address and command -- so this has to be entered per household. It is
     * stored rather than guessed, and until it is set nothing is ever
     * transmitted: an app that fires arbitrary infrared codes at a dark room is
     * a poltergeist, not a feature.
     */
    data class Remote(val address: Int, val command: Int, val label: String) {
        val configured: Boolean get() = address in 0..255 && command in 0..255
    }

    suspend fun remote(): Remote? {
        val prefs = context.irStore.data.first()
        val address = prefs[ADDRESS] ?: return null
        val command = prefs[COMMAND] ?: return null
        return Remote(address, command, "Fan or air conditioner")
    }

    suspend fun saveRemote(address: Int, command: Int) {
        context.irStore.edit {
            it[ADDRESS] = address.coerceIn(0, 255)
            it[COMMAND] = command.coerceIn(0, 255)
        }
    }

    suspend fun clearRemote() {
        context.irStore.edit { it.remove(ADDRESS); it.remove(COMMAND) }
    }

    /**
     * Send the taught code, if there is one and this phone can.
     *
     * Returns false rather than throwing on every one of the ways this can be
     * unavailable, because the caller is the escalation ladder and a missing
     * emitter must not stop a baby being attended to.
     */
    suspend fun nudgeRoom(): Boolean {
        if (!isReady) return false
        val remote = remote() ?: return false
        if (!remote.configured) return false
        return sendNec(remote.address, remote.command)
    }

}
