package com.nila.actions

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.sootheStore by preferencesDataStore("soothe_memory")

/**
 * Which sound actually settles *this* baby.
 *
 * After a sound plays, the engine re-reads the cry envelope and reports back
 * whether the episode settled. That verification step is what separates this
 * from a white-noise app: the system does not merely act, it checks whether the
 * action worked and lets that change what it does next time.
 *
 * Scores are a Laplace-smoothed success rate, so a sound that worked once out
 * of one attempt does not immediately outrank one that worked eight times out
 * of ten.
 */
class SootheMemory(private val context: Context) {

    private fun successKey(id: String) = intPreferencesKey("success_$id")
    private fun attemptKey(id: String) = intPreferencesKey("attempt_$id")
    private fun scoreKey(id: String) = floatPreferencesKey("score_$id")

    data class Stats(val id: String, val attempts: Int, val successes: Int, val score: Float) {
        val percent: Int get() = (score * 100).toInt()
    }

    suspend fun record(id: String, settled: Boolean) {
        context.sootheStore.edit { prefs ->
            val attempts = (prefs[attemptKey(id)] ?: 0) + 1
            val successes = (prefs[successKey(id)] ?: 0) + if (settled) 1 else 0
            prefs[attemptKey(id)] = attempts
            prefs[successKey(id)] = successes
            // Laplace smoothing: start everything at 1/3, converge with evidence.
            prefs[scoreKey(id)] = (successes + 1f) / (attempts + 3f)
        }
    }

    suspend fun statsFor(id: String): Stats {
        val prefs = context.sootheStore.data.first()
        val attempts = prefs[attemptKey(id)] ?: 0
        val successes = prefs[successKey(id)] ?: 0
        return Stats(id, attempts, successes,
                     prefs[scoreKey(id)] ?: ((successes + 1f) / (attempts + 3f)))
    }

    suspend fun allStats(ids: List<String>): List<Stats> =
        ids.map { statsFor(it) }.sortedByDescending { it.score }

    /**
     * Pick what to play.
     *
     * Mostly exploit the best-scoring sound, but keep a small exploration rate.
     * Without it the first sound that happens to work gets played forever and
     * the system never discovers that the caregiver's recorded voice works
     * better -- which it usually does.
     */
    suspend fun choose(candidates: List<Soother>, exploreRate: Float = 0.15f): Soother? {
        if (candidates.isEmpty()) return null
        if (Math.random() < exploreRate) return candidates.random()
        val stats = allStats(candidates.map { it.id })
        val bestId = stats.firstOrNull()?.id ?: return candidates.first()
        return candidates.firstOrNull { it.id == bestId } ?: candidates.first()
    }
}
