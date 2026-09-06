package com.nila.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: EventRecord): Long

    @Update
    suspend fun update(event: EventRecord)

    @Query("SELECT * FROM events ORDER BY startedAtMs DESC LIMIT :limit")
    fun recent(limit: Int = 200): Flow<List<EventRecord>>

    @Query("SELECT * FROM events WHERE startedAtMs >= :sinceMs ORDER BY startedAtMs DESC")
    suspend fun since(sinceMs: Long): List<EventRecord>

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun byId(id: Long): EventRecord?

    /**
     * Total seconds of crying in a window.
     *
     * This backs the colic tracker. The Wessel rule of threes -- crying three or
     * more hours a day, three or more days a week -- is defined purely on
     * duration, so measuring it makes no clinical claim; it just replaces a
     * sleep-deprived parent's paper diary with an accurate count.
     */
    @Query("""
        SELECT COALESCE(SUM(durationSeconds), 0) FROM events
        WHERE kind = 'CRY_ENDED' AND startedAtMs BETWEEN :fromMs AND :toMs
    """)
    suspend fun cryingSecondsBetween(fromMs: Long, toMs: Long): Int

    @Query("""
        SELECT COUNT(*) FROM events
        WHERE kind = 'CRY_ENDED' AND startedAtMs BETWEEN :fromMs AND :toMs
    """)
    suspend fun cryEpisodesBetween(fromMs: Long, toMs: Long): Int

    @Query("DELETE FROM events WHERE startedAtMs < :beforeMs")
    suspend fun prune(beforeMs: Long): Int
}

@Dao
interface CareDao {
    @Insert
    suspend fun insert(record: CareRecord): Long

    @Query("SELECT * FROM care_log ORDER BY atMs DESC LIMIT :limit")
    fun recent(limit: Int = 100): Flow<List<CareRecord>>

    @Query("SELECT * FROM care_log WHERE kind = :kind ORDER BY atMs DESC LIMIT 1")
    suspend fun latestOf(kind: String): CareRecord?

    @Query("SELECT * FROM care_log WHERE atMs >= :sinceMs ORDER BY atMs DESC")
    suspend fun since(sinceMs: Long): List<CareRecord>

    @Query("DELETE FROM care_log WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface BabyDao {
    @Upsert
    suspend fun upsert(profile: BabyProfile)

    @Query("SELECT * FROM baby WHERE id = 1")
    fun observe(): Flow<BabyProfile?>

    @Query("SELECT * FROM baby WHERE id = 1")
    suspend fun get(): BabyProfile?
}

@Dao
interface ConversationDao {
    @Insert
    suspend fun insert(record: ConversationRecord): Long

    @Query("SELECT * FROM conversations ORDER BY atMs DESC LIMIT :limit")
    fun recent(limit: Int = 50): Flow<List<ConversationRecord>>

    @Query("SELECT * FROM conversations ORDER BY atMs DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<ConversationRecord>
}
