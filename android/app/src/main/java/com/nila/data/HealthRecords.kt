package com.nila.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Whose record this is. Two people are being cared for here, not one. */
enum class RecordSubject(val label: String) {
    BABY("Baby"),
    MOTHER("Mother");

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: BABY
    }
}

/**
 * Categories, kept deliberately few.
 *
 * A filing system with thirty categories gets used for about a week. These are
 * the buckets a parent actually reaches for, and anything that does not fit goes
 * in OTHER rather than forcing a decision at the moment of saving.
 */
enum class RecordCategory(
    val label: String,
    val subject: RecordSubject?,   // null = applies to both
) {
    GROWTH("Growth & weight", RecordSubject.BABY),
    VACCINATION("Vaccination", RecordSubject.BABY),
    ILLNESS("Illness & visits", RecordSubject.BABY),
    SCREENING("Screening", RecordSubject.BABY),

    POSTNATAL("Postnatal check", RecordSubject.MOTHER),
    LACTATION("Feeding & lactation", RecordSubject.MOTHER),
    MENTAL_HEALTH("Mood & wellbeing", RecordSubject.MOTHER),

    PRESCRIPTION("Prescription", null),
    LAB_REPORT("Lab report", null),
    OTHER("Other", null);

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: OTHER
        fun forSubject(subject: RecordSubject) =
            entries.filter { it.subject == null || it.subject == subject }
    }
}

/**
 * A stored document: a photo of a prescription, a lab report, a discharge note.
 *
 * [extractedText] is filled by on-device OCR at import. That is what makes a
 * shoebox of photographs actually useful -- the assistant can retrieve over it,
 * and the medicine review can check a new drug against what is already on a
 * prescription she photographed three weeks ago.
 *
 * The file itself never leaves the app's private storage.
 */
@Entity(
    tableName = "health_records",
    indices = [Index("subject"), Index("category"), Index("recordedAtMs")],
)
data class HealthRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subject: String,
    val category: String,
    val title: String,
    val notes: String = "",
    /** Absolute path inside the app's private files directory. */
    val filePath: String? = null,
    val mimeType: String? = null,
    val extractedText: String = "",
    val recordedAtMs: Long,
    val createdAtMs: Long = System.currentTimeMillis(),
) {
    val recordSubject: RecordSubject get() = RecordSubject.of(subject)
    val recordCategory: RecordCategory get() = RecordCategory.of(category)
    val hasFile: Boolean get() = filePath != null
    val searchable: String get() = "$title $notes $extractedText"
}

/**
 * The breastfeeding parent's own profile.
 *
 * This exists because every honest answer about a medicine depends on it. Half
 * the medicines in the corpus change their answer given asthma, a penicillin
 * allergy, or something she is already taking -- and none of that is knowable
 * from a photograph of a strip.
 */
@Entity(tableName = "mother_profile")
data class MotherProfile(
    @PrimaryKey val id: Int = 1,
    val name: String = "",
    val isBreastfeeding: Boolean = true,
    val deliveryDateMs: Long = 0,
    /** Comma-separated, free text. Parsed leniently -- people write "asthma"
     *  or "mild asthma since childhood" and both must work. */
    val conditions: String = "",
    val allergies: String = "",
    val currentMedicines: String = "",
    val notes: String = "",
) {
    private fun split(value: String) = value
        .split(',', ';', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    val conditionList: List<String> get() = split(conditions)
    val allergyList: List<String> get() = split(allergies)
    val medicineList: List<String> get() = split(currentMedicines)

    val weeksPostpartum: Int?
        get() = if (deliveryDateMs <= 0) null
        else ((System.currentTimeMillis() - deliveryDateMs) / (7 * 86_400_000L)).toInt()

    val isComplete: Boolean
        get() = conditions.isNotBlank() || allergies.isNotBlank() ||
            currentMedicines.isNotBlank()
}

@Dao
interface HealthRecordDao {
    @Insert
    suspend fun insert(record: HealthRecord): Long

    @Upsert
    suspend fun upsert(record: HealthRecord)

    @Delete
    suspend fun delete(record: HealthRecord)

    @Query("SELECT * FROM health_records ORDER BY recordedAtMs DESC")
    fun all(): Flow<List<HealthRecord>>

    @Query("SELECT * FROM health_records WHERE subject = :subject ORDER BY recordedAtMs DESC")
    fun forSubject(subject: String): Flow<List<HealthRecord>>

    @Query("SELECT * FROM health_records WHERE id = :id")
    suspend fun byId(id: Long): HealthRecord?

    @Query("SELECT * FROM health_records WHERE subject = :subject ORDER BY recordedAtMs DESC")
    suspend fun listForSubject(subject: String): List<HealthRecord>

    @Query("SELECT COUNT(*) FROM health_records WHERE subject = :subject")
    suspend fun countFor(subject: String): Int

    @Query("SELECT COUNT(*) FROM health_records")
    suspend fun count(): Int

    /** Used only by the demo seeder's reset. Never called on real data. */
    @Query("DELETE FROM health_records")
    suspend fun deleteAll()

    /**
     * Keyword search across title, notes and OCR text.
     *
     * Room's LIKE is enough here: a family's record shelf is tens of documents,
     * not thousands, and a substring match over OCR text is both predictable and
     * explainable -- which matters more than recall when the answer feeds a
     * medicine decision.
     */
    @Query("""
        SELECT * FROM health_records
        WHERE (:subject IS NULL OR subject = :subject)
          AND (title LIKE '%' || :term || '%'
               OR notes LIKE '%' || :term || '%'
               OR extractedText LIKE '%' || :term || '%')
        ORDER BY recordedAtMs DESC LIMIT :limit
    """)
    suspend fun search(term: String, subject: String? = null, limit: Int = 10):
        List<HealthRecord>
}

@Dao
interface MotherDao {
    @Upsert
    suspend fun upsert(profile: MotherProfile)

    @Query("SELECT * FROM mother_profile WHERE id = 1")
    fun observe(): Flow<MotherProfile?>

    @Query("SELECT * FROM mother_profile WHERE id = 1")
    suspend fun get(): MotherProfile?
}
