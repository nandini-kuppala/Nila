package com.nila.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val Context.clipStore by preferencesDataStore("clips")

/**
 * Whether Nila keeps the audio of a cry, and the argument for the default.
 *
 * ### Why it is on
 *
 * The clip is the only thing on the summary card a parent can check for
 * themselves. Everything else -- *probably tired, we played white noise, it did
 * not help, we woke you* -- has to be taken on trust, including a cause estimate
 * the app itself says is unreliable. Thirty seconds of listening settles it
 * either way. And the flagged ones outlive the night for a second reason: the
 * cry a parent wants to describe at a Monday appointment is one they cannot
 * reproduce in the room, and a paediatrician who can hear it is being given
 * evidence rather than a recollection.
 *
 * ### Why it is a switch
 *
 * Because recording a baby's room is not a decision an app gets to make on
 * somebody's behalf, however good its reasons are. Off means off: the recorder
 * is never opened, so there is no file to trust anybody with. Nothing about
 * detection, the ladder or the alerts changes -- only the audio.
 *
 * ### What "offline" means here, exactly
 *
 * Files live in [Context.getFilesDir], which is app-private: no other app can
 * read them, `adb pull` cannot reach them on a non-rooted phone, and
 * `allowBackup=false` in the manifest keeps them off any cloud transport. There
 * is no code in this app that uploads audio, and no network permission path
 * that could carry it.
 */
object ClipPolicy {

    private val KEY = booleanPreferencesKey("keep_cry_clips")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _enabled = MutableStateFlow(true)

    /** True when cries may be recorded at all. Default true. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /**
     * Read the stored choice.
     *
     * Called from both the application and the monitoring service, because the
     * service can be started by a notification action with no Activity ever
     * having run -- and a service that recorded because it had not got around
     * to reading the setting would be the worst possible bug in this file.
     */
    fun load(context: Context) {
        val app = context.applicationContext
        scope.launch {
            val stored = runCatching { app.clipStore.data.first()[KEY] }.getOrNull()
            _enabled.value = stored ?: true
        }
    }

    fun set(context: Context, enabled: Boolean) {
        _enabled.value = enabled
        val app = context.applicationContext
        scope.launch { runCatching { app.clipStore.edit { it[KEY] = enabled } } }
    }

    /**
     * Blocking read, for the service's own start-up.
     *
     * [load] is asynchronous, which is right for a screen and wrong for the one
     * caller that must not guess: this returns the stored value itself.
     */
    suspend fun current(context: Context): Boolean {
        val stored = runCatching {
            context.applicationContext.clipStore.data.first()[KEY]
        }.getOrNull() ?: true
        _enabled.value = stored
        return stored
    }
}
