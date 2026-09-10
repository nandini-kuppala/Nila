package com.nila.vision

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.safeZoneStore by preferencesDataStore("safe_zone")

/**
 * The safe zone, remembered between nights.
 *
 * DataStore rather than the Room database: this is one rectangle of app
 * configuration, read on every camera bind, with nothing to do with the care
 * log. It sits beside the other preference stores for the same reason.
 */
class SafeZoneStore(private val context: Context) {

    companion object {
        private val KEY = stringPreferencesKey("zone")
    }

    val zone: Flow<SafeZone> =
        context.safeZoneStore.data.map { SafeZone.decode(it[KEY]) }

    /**
     * The zone right now, for a caller that cannot suspend.
     *
     * The camera binds from a service callback with no scope to suspend in, and
     * a watch that armed with the default zone because the read had not
     * finished yet would alarm about a cot the parent had already fenced off.
     * Reading one small preference synchronously, once per bind, is the cheaper
     * mistake.
     */
    val current: SafeZone
        get() = runBlocking { zone.first() }

    suspend fun save(zone: SafeZone) {
        val normalised = zone.normalised()
        context.safeZoneStore.edit { it[KEY] = normalised.encode() }
    }

    /** Back to the inner 70% of the frame, and back to being unconfigured. */
    suspend fun reset() {
        context.safeZoneStore.edit { it.remove(KEY) }
    }
}
