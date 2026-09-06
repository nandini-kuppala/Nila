package com.nila.ui

import android.nfc.Tag
import com.nila.data.CareKind
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Where NFC taps land on their way from the activity to the view model.
 *
 * Foreground dispatch delivers to an Activity, but the state that should change
 * lives in a ViewModel that outlives configuration changes. A tiny shared bus
 * beats threading a callback through every composable, and keeps the activity
 * ignorant of what a tap means.
 */
object NfcInbox {
    val taps = MutableSharedFlow<CareKind>(extraBufferCapacity = 4)

    /** An unrecognised sticker, held so the UI can offer to assign it. */
    val pendingTag = MutableStateFlow<Tag?>(null)

    fun tapped(kind: CareKind) { taps.tryEmit(kind) }
    fun unassigned(tag: Tag) { pendingTag.value = tag }
    fun clearPending() { pendingTag.value = null }
}
