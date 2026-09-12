package com.nila.ui.theme

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val Context.themeStore by preferencesDataStore("appearance")

/**
 * Which of the two schemes the app is in, and who decided.
 *
 * [SYSTEM] is the default and stays the default: a phone set to switch at
 * sunset should switch this app at sunset too, and an app that ignores that
 * setting is an app somebody has to fix by hand twice a day. The other two
 * exist because following the system is not always what a person wants at the
 * moment they want it -- a nursery at 3am with the phone still in day mode is
 * the case this app is actually used in.
 */
enum class ThemeMode {
    SYSTEM, LIGHT, DARK;

    /** One word. These sit three-across in a row a third of a phone wide. */
    val label: String
        get() = when (this) {
            SYSTEM -> "System"
            LIGHT -> "Light"
            DARK -> "Dark"
        }
}

/**
 * The chosen appearance, held for the process and written through to disk.
 *
 * A `StateFlow` rather than reading the DataStore flow directly at the call
 * site, because the theme is read by the activity *above* the view model: it
 * wraps everything, including the permission gate, so it cannot depend on
 * anything built inside it. Holding it here also makes the toggle instant --
 * the tap flips the flow and the write happens behind it, instead of the whole
 * app waiting a frame or two on a disk round trip to change colour.
 *
 * Hydrated once, from [load], at application start.
 */
object Appearance {

    private val KEY = stringPreferencesKey("theme_mode")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _mode = MutableStateFlow(ThemeMode.SYSTEM)
    val mode: StateFlow<ThemeMode> = _mode.asStateFlow()

    /** Read what was chosen last time. Safe to call more than once. */
    fun load(context: Context) {
        val app = context.applicationContext
        scope.launch {
            val stored = runCatching { app.themeStore.data.first()[KEY] }.getOrNull()
            _mode.value = stored?.let { name ->
                ThemeMode.entries.firstOrNull { it.name == name }
            } ?: ThemeMode.SYSTEM
        }
    }

    fun set(context: Context, mode: ThemeMode) {
        _mode.value = mode
        val app = context.applicationContext
        scope.launch {
            runCatching { app.themeStore.edit { it[KEY] = mode.name } }
        }
    }

    /**
     * What the top-bar button does: the opposite of what is on screen now.
     *
     * Deliberately not a three-way cycle. One icon that walks through system,
     * light and dark gives the reader no way to know which of the three they
     * are in without watching the colours change, and two of the three look
     * identical most of the time. The button switches the thing you can see;
     * the three-way choice, including going back to following the system,
     * lives in Settings where there is room to name all three.
     */
    fun toggle(context: Context, currentlyDark: Boolean) {
        set(context, if (currentlyDark) ThemeMode.LIGHT else ThemeMode.DARK)
    }
}
