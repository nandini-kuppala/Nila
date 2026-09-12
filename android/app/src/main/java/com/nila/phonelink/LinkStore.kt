package com.nila.phonelink

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.linkStore by preferencesDataStore("phone_link")

/**
 * Which phone this is, and the key it shares with the other one.
 *
 * DataStore beside [com.nila.vision.SafeZoneStore] rather than a Room table,
 * for the same reason that one is: it is a handful of configuration values read
 * at service start, with nothing to do with the care log.
 */
class LinkStore(private val context: Context) {

    /**
     * What this phone does. Both roles ship in the same APK.
     *
     * Not two apps, because the second phone is the *same* app with its input
     * lanes idle -- and because a household that swaps which phone sits by the
     * cot should be able to swap the roles, not reinstall.
     */
    enum class Role {
        /** The original behaviour: microphone, camera, ladder, log. */
        GUARDIAN,

        /** Receives alerts from a guardian. No microphone, no camera. */
        PARENT,
    }

    companion object {
        private val KEY_ROLE = stringPreferencesKey("role")
        private val KEY_SECRET = stringPreferencesKey("secret")

        /**
         * The last address a guardian was reached at.
         *
         * Discovery finds it normally; this is what makes the *first* reconnect
         * after a reboot instant rather than waiting on mDNS, and what saves a
         * pairing on a network where multicast is filtered.
         */
        private val KEY_HOST = stringPreferencesKey("host")
    }

    val role: Flow<Role> = context.linkStore.data.map { prefs ->
        runCatching { Role.valueOf(prefs[KEY_ROLE] ?: "") }.getOrDefault(Role.GUARDIAN)
    }

    /** The role right now, for a service starting outside a coroutine. */
    val currentRole: Role get() = runBlocking { role.first() }

    val paired: Flow<Boolean> = context.linkStore.data.map { it[KEY_SECRET] != null }

    /**
     * The derived link key, or null if these phones have never been paired.
     *
     * Read synchronously because both links need it before they can accept or
     * send a single frame, and both start from a service callback.
     */
    val key: ByteArray?
        get() = runBlocking {
            context.linkStore.data.first()[KEY_SECRET]?.let(::fromHex)
        }

    val host: String?
        get() = runBlocking { context.linkStore.data.first()[KEY_HOST] }

    suspend fun setRole(role: Role) {
        context.linkStore.edit { it[KEY_ROLE] = role.name }
    }

    /**
     * Store the key derived from a pairing code.
     *
     * The code itself is never written anywhere. It exists for as long as it
     * takes somebody to read it off one screen and type it into another.
     */
    suspend fun pair(code: String) {
        val derived = LinkProtocol.deriveKey(code)
        context.linkStore.edit { it[KEY_SECRET] = toHex(derived) }
    }

    suspend fun rememberHost(host: String) {
        context.linkStore.edit { it[KEY_HOST] = host }
    }

    suspend fun unpair() {
        context.linkStore.edit {
            it.remove(KEY_SECRET)
            it.remove(KEY_HOST)
        }
    }

    private fun toHex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun fromHex(s: String) = ByteArray(s.length / 2) {
        s.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }
}
