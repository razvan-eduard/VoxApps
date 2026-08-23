package com.voxapps.apppicker

/**
 * The order a list of apps is offered in: starred first, then chosen, then the rest — each group
 * alphabetical.
 *
 * Installed alphabetical order is the order of a list nobody has an opinion about, and these lists
 * are long. The apps a person has already said something about are the ones they came back for, so
 * they are the ones at the top; a star says more than a tick, so starred outranks chosen. Inside a
 * group nothing distinguishes one app from another, and alphabetical is the order you can search by
 * eye.
 */
object AppPickerOrder {

    fun of(
        apps: List<AppPickerEntry>,
        selected: Set<String>,
        starred: Set<String> = emptySet()
    ): List<AppPickerEntry> = apps.sortedWith(
        compareBy<AppPickerEntry> { rankOf(it.packageName, selected, starred) }
            // Case-insensitive, or every lowercase name sorts below every uppercase one and the
            // alphabet appears to run twice.
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
            // A tie-break that cannot tie, so the order is total and never depends on how the list
            // arrived: two apps can share a display name, never a package.
            .thenBy { it.packageName }
    )

    /** Starred beats chosen beats the rest. A starred app ranks there whether or not it is chosen. */
    private fun rankOf(packageName: String, selected: Set<String>, starred: Set<String>): Int = when {
        packageName in starred -> 0
        packageName in selected -> 1
        else -> 2
    }
}
