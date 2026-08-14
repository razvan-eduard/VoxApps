package com.voxapps.expenses.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import com.voxapps.datahygiene.RemapRule
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

/**
 * A re-map rule (see [com.voxapps.datahygiene.RemapEngine]): WHEN the match fields equal the stored
 * values THEN the set fields are written onto a newly captured expense. [matchJson]/[setJson] are
 * flat org.json objects of fieldId → value ([ExpenseRemapFields] ids), encoded with sorted keys so
 * equal maps always produce equal strings — that string equality is how a proposal checks whether
 * a rule for its pattern already exists. Persisted here (not `:core:datahygiene`) because Room
 * entities aren't shared across app databases in this codebase — each app owns its own table, same
 * as [DuplicateRuleEntity].
 *
 * [origin] records who wrote the row: [ORIGIN_USER] rows were authored in the rule editor;
 * [ORIGIN_PROPOSED] rows were drafted by [ExpensesRepository.recordRemapPatternSightings] from a
 * repeated edit pattern and are created DISABLED — a proposal never acts until a human reviews and
 * enables it, which is the entire authority model: the machine drafts, only the user activates.
 * A rule applies exactly when [enabled], regardless of origin.
 */
@Entity(tableName = "remap_rules")
data class RemapRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val matchJson: String,
    val setJson: String,
    val origin: String,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val updatedAt: Long,
    /** fieldId → fuzziness level (1..3) for match entries that compare fuzzily; absent = exact.
     *  Only ever written by the rule editor — proposals always start exact. */
    val fuzzJson: String = "{}"
) {
    fun toRemapRule(): RemapRule = RemapRule(
        id = id,
        match = RemapRuleJson.decode(matchJson),
        set = RemapRuleJson.decode(setJson),
        sortOrder = sortOrder,
        fuzz = RemapRuleJson.decode(fuzzJson).mapNotNull { (k, v) ->
            v.toIntOrNull()?.takeIf { it > 0 }?.let { k to it }
        }.toMap()
    )

    companion object {
        const val ORIGIN_USER = "USER"
        const val ORIGIN_PROPOSED = "PROPOSED"
    }
}

/**
 * One record's contribution to an edit pattern: the user saved [recordId] with [fieldId] changed
 * from [beforeText] to [afterText], alongside [companionsJson] (the OTHER set-field edits of that
 * same save, fieldId → set value). One row per (pattern, record) — re-saving the same record
 * replaces its row rather than counting again, so a single record can never accumulate a pattern
 * by itself. When distinct records reach the user's threshold,
 * [ExpensesRepository.recordRemapPatternSightings] drafts the disabled rule and deletes the
 * pattern's rows — the rule, enabled or not, is the durable form from then on.
 */
@Entity(tableName = "remap_pattern_sightings", primaryKeys = ["patternKey", "recordId"])
data class RemapPatternSighting(
    /** fieldId + before + after, normalized — the exact pair IS the pattern's identity. */
    val patternKey: String,
    val recordId: Long,
    val fieldId: String,
    val beforeText: String,
    val afterText: String,
    /** The set-field id/value this pattern writes when it becomes a rule — differs from
     *  [fieldId]/[afterText] only for category, whose trigger is a name but whose set is an id. */
    val setFieldId: String,
    val setValue: String,
    val companionsJson: String,
    val createdAt: Long
)

@Dao
interface RemapPatternSightingDao {
    @Query("SELECT * FROM remap_pattern_sightings WHERE patternKey = :patternKey")
    suspend fun getForPattern(patternKey: String): List<RemapPatternSighting>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(sighting: RemapPatternSighting)

    @Query("DELETE FROM remap_pattern_sightings WHERE patternKey = :patternKey")
    suspend fun deleteForPattern(patternKey: String)

    @Query("DELETE FROM remap_pattern_sightings")
    suspend fun deleteAll()
}

/** Hand-rolled org.json (de)serialization for the two rule maps — matches this codebase's existing
 *  convention (no Gson/kotlinx.serialization for stored data). Keys are sorted so the encoding is
 *  canonical: equal maps encode identically, making [RemapRuleDao.getByMatch] a plain string match. */
object RemapRuleJson {
    fun encode(map: Map<String, String>): String {
        val o = JSONObject()
        for (key in map.keys.sorted()) o.put(key, map.getValue(key))
        return o.toString()
    }

    fun decode(json: String?): Map<String, String> = try {
        if (json.isNullOrBlank()) emptyMap() else {
            val o = JSONObject(json)
            o.keys().asSequence().associateWith { o.getString(it) }
        }
    } catch (e: Exception) {
        emptyMap()
    }
}

@Dao
interface RemapRuleDao {
    @Query("SELECT * FROM remap_rules ORDER BY sortOrder, id")
    suspend fun getAll(): List<RemapRuleEntity>

    @Query("SELECT * FROM remap_rules ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<RemapRuleEntity>>

    @Query("SELECT * FROM remap_rules WHERE matchJson = :matchJson LIMIT 1")
    suspend fun getByMatch(matchJson: String): RemapRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: RemapRuleEntity): Long

    @Update
    suspend fun update(rule: RemapRuleEntity)

    @Delete
    suspend fun delete(rule: RemapRuleEntity)

    @Query("DELETE FROM remap_rules WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE remap_rules SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun setSortOrder(id: Long, sortOrder: Int)

    @Query("UPDATE remap_rules SET enabled = :enabled")
    suspend fun setAllEnabled(enabled: Boolean)

    @Query("DELETE FROM remap_rules")
    suspend fun deleteAll()
}
