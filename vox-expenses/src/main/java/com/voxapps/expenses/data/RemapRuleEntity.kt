package com.voxapps.expenses.data

import com.voxapps.datahygiene.RemapCondition
import com.voxapps.datahygiene.RemapOp
import com.voxapps.datahygiene.RemapTrigger
import org.json.JSONArray
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
    val fuzzJson: String = "{}",
    /**
     * fieldId → value the records behind a proposal all carried, offered in the editor as one tap
     * that narrows the trigger. Written only by [ExpensesRepository.recordRemapPatternSightings].
     *
     * A drafted rule triggers on the merchant alone, because that is the one thing a later capture
     * is certain to arrive with. Everything else those records had in common is a real narrowing
     * somebody might want — the same shop only when the card was that one — but guessing which is
     * meant would put conditions into a rule nobody wrote. Offering them costs a tap and leaves the
     * decision where it belongs.
     */
    val suggestJson: String = "{}",
    /**
     * Whether the rule asks to be told when it fires.
     *
     * An effect beside the fields it writes, not instead of them: a rule may rewrite, may alert, or
     * may do both, and one that only alerts leaves the record exactly as captured. What it costs to
     * say is a switch; what it buys is the standing request to hear about a payment over a figure,
     * which no amount of field rewriting can express.
     */
    val alertEnabled: Boolean = false
) {
    fun toRemapRule(): RemapRule {
        // Fuzz stayed a separate column when a rule could only ask about a field once, so it is
        // keyed by field. A condition carries its own level now; the stored map still answers for
        // every condition on that field, which is what it always meant.
        val levels = RemapRuleJson.decode(fuzzJson).mapNotNull { (k, v) ->
            v.toIntOrNull()?.takeIf { it > 0 }?.let { k to it }
        }.toMap()
        val groups = RemapConditionsJson.decode(matchJson, levels)
        return RemapRule(
            id = id,
            trigger = RemapTrigger(groups),
            set = RemapRuleJson.decode(setJson),
            sortOrder = sortOrder,
            alert = alertEnabled
        )
    }

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
    val createdAt: Long,
    /** fieldId → what this record carried in the fields nobody touched, normalized as a trigger
     *  value. Kept per sighting so only what every one of them agreed on reaches
     *  [RemapRuleEntity.suggestJson]. */
    val observedJson: String = "{}"
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
/**
 * A rule's conditions, stored as a list so one field can be asked about more than once.
 *
 * Two shapes are read: a JSON array of `{field,value,fuzz}`, and the flat `{field: value}` object
 * rules were written as before conditions could repeat. The old shape is an AND of one condition per
 * key, which is exactly what it always meant — so it keeps working without being rewritten, and a
 * rule nobody has edited is never touched by a migration that could get it wrong.
 */
object RemapConditionsJson {

    fun encode(groups: List<List<RemapCondition>>): String {
        // Sorted at both levels, so two triggers testing the same things in a different order
        // produce the same string — that equality is how a proposal checks whether its rule
        // already exists.
        val outer = JSONArray()
        val sortedGroups = groups.filter { it.isNotEmpty() }
            .map { it.sortedWith(compareBy({ c: RemapCondition -> c.fieldId }, { it.value }, { it.fuzz }, { it.op })) }
            .sortedBy { g -> g.joinToString("|") { "${it.fieldId}${it.op.symbol}${it.value}:${it.fuzz}" } }
        for (group in sortedGroups) {
            val inner = JSONArray()
            for (c in group) {
                inner.put(
                    JSONObject().put("field", c.fieldId).put("value", c.value)
                        .put("fuzz", c.fuzz).put("op", c.op.symbol)
                )
            }
            outer.put(inner)
        }
        return outer.toString()
    }

    /**
     * The groups a stored trigger describes: OR between them, AND inside one.
     *
     * [levels] supplies the fuzziness a condition is compared at, which lives in its own column for
     * rules written before a field could be asked about twice.
     */
    fun decode(json: String?, levels: Map<String, Int> = emptyMap()): List<List<RemapCondition>> {
        if (json.isNullOrBlank()) return emptyList()
        fun conditionOf(field: String, value: String, fuzz: Int?, op: String? = null) =
            RemapCondition(field, value, fuzz ?: levels[field] ?: 0, RemapOp.of(op))
        return try {
            val trimmed = json.trimStart()
            when {
                // The current shape: an array of groups.
                trimmed.startsWith("[[") -> {
                    val outer = JSONArray(json)
                    (0 until outer.length()).map { g ->
                        val inner = outer.optJSONArray(g) ?: JSONArray()
                        (0 until inner.length()).mapNotNull { i ->
                            val o = inner.optJSONObject(i) ?: return@mapNotNull null
                            val field = o.optString("field").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                            conditionOf(
                                field, o.optString("value"),
                                o.optInt("fuzz", 0).takeIf { it > 0 }, o.optString("op")
                            )
                        }
                    }.filter { it.isNotEmpty() }
                }
                // A flat array of conditions: one group, everything required.
                trimmed.startsWith("[") -> {
                    val array = JSONArray(json)
                    listOf((0 until array.length()).mapNotNull { i ->
                        val o = array.optJSONObject(i) ?: return@mapNotNull null
                        val field = o.optString("field").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        conditionOf(
                            field, o.optString("value"),
                            o.optInt("fuzz", 0).takeIf { it > 0 }, o.optString("op")
                        )
                    }).filter { it.isNotEmpty() }
                }
                // The original shape, written before conditions could repeat: field → value, all
                // required. That is one group, which is exactly what it always meant — so a rule
                // nobody has edited keeps working without a migration that could get it wrong.
                else -> {
                    val o = JSONObject(json)
                    listOf(o.keys().asSequence().map { conditionOf(it, o.getString(it), null) }.toList())
                        .filter { it.isNotEmpty() }
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

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
