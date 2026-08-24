package com.voxapps.datahygiene

/**
 * The category records fall back to, and the rules that make it one.
 *
 * Every app that files records under categories needs the same three things to be true, and each
 * one had been decided separately: that exactly one category is the fallback, that it cannot be
 * deleted, and that a record whose category is deleted moves there rather than being left with
 * none. A record with no category drops out of every per-category total and reads as though it was
 * never filed, when what actually happened is that somebody deleted a label.
 *
 * The rows themselves stay per app: Room entities are not shared across app databases in this
 * codebase, each app owning its own. What is shared is what the rows must mean — including the
 * seeded name and colour, so the fallback is recognisably the same thing in every app rather than
 * a coincidence of two migrations agreeing.
 */
object CategoryFallback {

    /** The name the fallback is seeded under. Renaming it afterwards is the user's business — the
     *  star, not the name, is what makes a category the fallback from then on. */
    const val SEED_NAME = "Uncategorised"

    /** Grey, deliberately outside the palette: it is the absence of a category rather than one
     *  more of them. */
    const val SEED_COLOR = 0xFF9E9E9EL

    /** Ahead of every ordinary category, so the list opens with what everything else falls back to. */
    const val SEED_POSITION = -1

    /**
     * Where the records of a category about to be deleted belong.
     *
     * Null only when there is no fallback at all — a database nobody seeded — and the caller then
     * has no choice but to leave the records without one. [deletingId] is excluded so a caller
     * cannot be told to move records onto the very row it is deleting.
     */
    fun <T> destinationFor(
        categories: List<T>,
        deletingId: Long,
        idOf: (T) -> Long,
        isFallback: (T) -> Boolean
    ): T? = categories.firstOrNull { isFallback(it) && idOf(it) != deletingId }

    /** Whether this category may be deleted at all: the fallback may not. There has to be somewhere
     *  for a record with no opinion to land, and silently electing a new one would move every
     *  future record without saying so. */
    fun <T> deletable(category: T, isFallback: (T) -> Boolean): Boolean = !isFallback(category)

    /**
     * The SQL that seeds the fallback and leaves exactly one, for a migration to run as written.
     *
     * Idempotent on both halves — the insert is conditional on the row's absence and the star ends
     * up in exactly one place — so running it from either of two migration paths is safe. Given as
     * statements rather than executed here because this module knows nothing of any app's database;
     * [table] and [starColumn] are the caller's own names for its own schema.
     */
    fun seedStatements(
        table: String = "categories",
        starColumn: String = "isDefault",
        createdAt: Long
    ): List<String> = listOf(
        "INSERT INTO $table (name, colorArgb, position, createdAt, $starColumn) " +
            "SELECT '$SEED_NAME', $SEED_COLOR, $SEED_POSITION, $createdAt, 0 " +
            "WHERE NOT EXISTS (SELECT 1 FROM $table WHERE name = '$SEED_NAME')",
        "UPDATE $table SET $starColumn = 0",
        "UPDATE $table SET $starColumn = 1 WHERE name = '$SEED_NAME'"
    )
}
