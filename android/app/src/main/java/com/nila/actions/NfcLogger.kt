package com.nila.actions

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.util.Log
import com.nila.data.CareKind

/**
 * Tap a sticker to log a feed.
 *
 * The context log is what makes every other answer in this app specific -- "three
 * hours since the last feed" is only available because someone recorded the
 * feed. And the reliable way to lose that data is to ask a person holding a baby
 * at 3am to unlock a phone, find an app and press a button.
 *
 * A ~5 rupee NFC sticker on the bottle, the changing table and the cot turns
 * that into a tap. The tag's own id is the key, so no writing to the tag is
 * required and any blank sticker works.
 */
class NfcLogger(private val context: Context) {

    companion object {
        private const val TAG = "NfcLogger"
        private const val PREFS = "nfc_tags"
    }

    sealed interface Availability {
        data object Ready : Availability
        data object NoHardware : Availability
        data object Disabled : Availability
    }

    private val adapter: NfcAdapter? = runCatching {
        NfcAdapter.getDefaultAdapter(context)
    }.getOrNull()

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val availability: Availability get() = when {
        adapter == null -> Availability.NoHardware
        !adapter.isEnabled -> Availability.Disabled
        else -> Availability.Ready
    }

    fun describe(): String = when (availability) {
        Availability.Ready -> {
            val n = prefs.all.keys.count { it.startsWith("tag_") }
            if (n == 0) "Ready - hold a blank NFC sticker to the phone to assign it"
            else "$n sticker${if (n == 1) "" else "s"} assigned"
        }
        Availability.Disabled -> "NFC is switched off in system settings"
        Availability.NoHardware -> "This phone has no NFC"
    }

    /**
     * Route tag reads to the foreground activity.
     *
     * Foreground dispatch rather than a manifest intent filter: a tap should log
     * a feed while the app is open, not launch the app every time the phone
     * brushes a transit card.
     */
    fun enableForeground(activity: Activity) {
        val adapter = adapter ?: return
        if (!adapter.isEnabled) return
        val intent = Intent(activity, activity.javaClass)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            activity, 0, intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        runCatching { adapter.enableForegroundDispatch(activity, pending, null, null) }
            .onFailure { Log.w(TAG, "foreground dispatch failed", it) }
    }

    fun disableForeground(activity: Activity) {
        runCatching { adapter?.disableForegroundDispatch(activity) }
    }

    private fun tagId(tag: Tag): String =
        tag.id.joinToString("") { "%02x".format(it) }

    /** What a tap means, or null if this sticker has not been assigned yet. */
    fun kindFor(tag: Tag): CareKind? =
        prefs.getString("tag_${tagId(tag)}", null)?.let { stored ->
            runCatching { CareKind.valueOf(stored) }.getOrNull()
        }

    fun assign(tag: Tag, kind: CareKind) {
        prefs.edit().putString("tag_${tagId(tag)}", kind.name).apply()
        Log.i(TAG, "sticker ${tagId(tag)} now logs ${kind.name}")
    }

    fun forget(tag: Tag) {
        prefs.edit().remove("tag_${tagId(tag)}").apply()
    }

    fun assignedCount(): Int = prefs.all.keys.count { it.startsWith("tag_") }

    /** Extract a tag from an intent delivered by foreground dispatch. */
    fun tagFrom(intent: Intent?): Tag? {
        intent ?: return null
        if (intent.action !in setOf(NfcAdapter.ACTION_TAG_DISCOVERED,
                                    NfcAdapter.ACTION_TECH_DISCOVERED,
                                    NfcAdapter.ACTION_NDEF_DISCOVERED)) return null
        @Suppress("DEPRECATION")
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }
    }
}
