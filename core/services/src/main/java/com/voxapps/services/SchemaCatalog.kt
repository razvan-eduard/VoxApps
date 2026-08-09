package com.voxapps.services

import com.voxapps.logging.Logger
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Every schema this app actually loads, without anyone writing the list down.
 *
 * A [RemoteSchema] enters here by existing — each registry constructs one, which is the thing it
 * would do anyway — so "refresh the schemas" and "reset the schemas" mean *all of them* by
 * construction. The list used to be typed out at each call site, and the moment two of them existed
 * they disagreed: a refresh button that synced four of the six schemas the app had, because the two
 * newest were wired into startup and nobody remembered the button.
 *
 * Only initialised schemas take part: an app links a module whose registry it never starts, and a
 * schema nothing has claimed is one this app does not read.
 */
object SchemaCatalog {

    private const val TAG = "SchemaCatalog"

    private val registered = CopyOnWriteArrayList<RemoteSchema<*>>()

    internal fun register(schema: RemoteSchema<*>) {
        registered += schema
    }

    /** The schemas this app has claimed, in the order they were constructed. */
    fun all(): List<RemoteSchema<*>> = registered.filter { it.isInitialised }

    /**
     * Asks the repository for every schema, adopting whichever ones actually changed.
     *
     * Returns what happened per file so a caller can report it rather than guess — "3 updated, 2
     * unchanged, 1 unreachable" is a different message from "done".
     */
    suspend fun refreshAll(baseUrl: String): Map<String, RemoteSchema.Refreshed> =
        all().associate { it.fileName to it.refresh(baseUrl) }

    /** Throws away every accepted copy, returning the app to what it shipped with. */
    fun resetAll(): List<String> = all().filter { it.resetToBundled() }.map { it.fileName }

    /**
     * Deletes saved copies left by an older scheme, once.
     *
     * A previous loader wrote into `filesDir` for two different reasons — a repository copy it
     * adopted, *and* a bundled copy it pushed forward when the app shipped a newer version. Nothing
     * in the file says which, so a copy left by that code cannot honestly be called "accepted from
     * the repository". Rather than label it wrongly, the app starts from what it shipped with; the
     * next check re-adopts anything the repository genuinely offers.
     *
     * [alreadyDone] is the caller's stored flag, and [markDone] records that this ran, so it happens
     * exactly once per install rather than on every launch.
     */
    fun discardCopiesFromOlderScheme(alreadyDone: Boolean, markDone: () -> Unit) {
        if (alreadyDone) return
        val discarded = resetAll()
        if (discarded.isNotEmpty()) {
            Logger.log("Discarded copies left by an older scheme: $discarded", TAG)
        }
        markDone()
    }

    /** What is in force for each schema, for a screen that shows the user where they stand. */
    fun provenance(): List<Provenance> = all().map {
        Provenance(it.fileName, it.source, it.acceptedAt)
    }

    data class Provenance(
        val fileName: String,
        val source: RemoteSchema.Source,
        val acceptedAt: Long?
    )
}
