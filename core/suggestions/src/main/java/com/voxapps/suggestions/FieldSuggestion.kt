package com.voxapps.suggestions

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One value proposed for one field of one already-saved record, waiting to be accepted or dismissed.
 *
 * Stored per field rather than per record, which is the whole reason this can be shared. A typed row
 * — one column per field — has to know what a record is made of, and that differs completely between
 * an expense, a note and a calendar entry. A key and a value do not, so the storage stops being the
 * satellite's business and only the *meaning* of the keys remains its own: each app declares what may
 * be suggested on its own record page (see [SuggestableField]) and how an accepted value is applied.
 *
 * Nothing here is ever written onto a record by itself. A suggestion exists precisely because
 * something — a rescan, a later model answer — proposed a value for a record a person may already
 * have reviewed, and overwriting that silently is the failure this whole mechanism exists to avoid.
 *
 * [sourceTag] is an opaque marker for whatever produced the suggestion, so accepting or dismissing it
 * can also dispose of that source. The satellite gives it whatever meaning it needs — expenses uses
 * the attachment group whose rescan produced the values, so dismissing the suggestion can remove the
 * photos that would otherwise stay attached with nothing left to apply.
 */
@Entity(tableName = "field_suggestions", primaryKeys = ["recordId", "fieldKey"])
data class FieldSuggestion(
    val recordId: Long,
    val fieldKey: String,
    /**
     * The proposed value as text. Anything not textual — an amount, a timestamp, a list of line
     * items — is encoded by the satellite that declared the field and decoded by the same one when
     * it applies it. Core never interprets this string, so it never has to know the field's type.
     */
    val value: String?,
    val sourceTag: String? = null
)

@Dao
interface FieldSuggestionDao {

    @Query("SELECT * FROM field_suggestions WHERE recordId = :recordId")
    fun forRecord(recordId: Long): Flow<List<FieldSuggestion>>

    @Query("SELECT * FROM field_suggestions WHERE recordId = :recordId")
    suspend fun snapshot(recordId: Long): List<FieldSuggestion>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(suggestions: List<FieldSuggestion>)

    @Query("DELETE FROM field_suggestions WHERE recordId = :recordId AND fieldKey = :fieldKey")
    suspend fun clearField(recordId: Long, fieldKey: String)

    /** Cleared once the record is saved, so a stale proposal never outlives the review it belonged
     *  to. */
    @Query("DELETE FROM field_suggestions WHERE recordId = :recordId")
    suspend fun clearRecord(recordId: Long)

    @Query("DELETE FROM field_suggestions WHERE recordId = :recordId AND sourceTag = :sourceTag")
    suspend fun clearSource(recordId: Long, sourceTag: String)
}
