package com.voxapps.apppicker

import org.junit.Assert.assertEquals
import org.junit.Test

/** Starred first, then chosen, then the rest — each group alphabetical. */
class AppPickerOrderTest {

    private fun app(pkg: String, name: String, system: Boolean = false) =
        AppPickerEntry(packageName = pkg, displayName = name, isSystemApp = system)

    private val all = listOf(
        app("com.d", "Delta"),
        app("com.b", "Bravo"),
        app("com.a", "Alpha"),
        app("com.c", "Charlie")
    )

    private fun names(selected: Set<String>, starred: Set<String> = emptySet()) =
        AppPickerOrder.of(all, selected, starred).map { it.displayName }

    @Test
    fun `nothing chosen leaves the list alphabetical`() {
        assertEquals(listOf("Alpha", "Bravo", "Charlie", "Delta"), names(emptySet()))
    }

    @Test
    fun `chosen apps lead, and are alphabetical among themselves`() {
        assertEquals(
            listOf("Charlie", "Delta", "Alpha", "Bravo"),
            names(selected = setOf("com.d", "com.c"))
        )
    }

    @Test
    fun `starred apps lead the chosen ones`() {
        assertEquals(
            listOf("Delta", "Charlie", "Alpha", "Bravo"),
            names(selected = setOf("com.c"), starred = setOf("com.d"))
        )
    }

    @Test
    fun `each group is alphabetical on its own`() {
        assertEquals(
            listOf("Bravo", "Delta", "Alpha", "Charlie"),
            names(selected = setOf("com.a", "com.c"), starred = setOf("com.d", "com.b"))
        )
    }

    /** A star already says the app matters; being ticked as well does not move it again. */
    @Test
    fun `an app both starred and chosen sits with the starred`() {
        assertEquals(
            listOf("Delta", "Charlie", "Alpha", "Bravo"),
            names(selected = setOf("com.c", "com.d"), starred = setOf("com.d"))
        )
    }

    @Test
    fun `case does not split the alphabet in two`() {
        val mixed = listOf(app("com.1", "apple"), app("com.2", "Banana"), app("com.3", "Cherry"))
        assertEquals(
            listOf("apple", "Banana", "Cherry"),
            AppPickerOrder.of(mixed, emptySet()).map { it.displayName }
        )
    }

    /** Two apps may share a display name; the package breaks the tie, so the order is total. */
    @Test
    fun `apps sharing a name keep a stable order`() {
        val twins = listOf(app("com.z", "Camera"), app("com.a", "Camera"))
        assertEquals(listOf("com.a", "com.z"), AppPickerOrder.of(twins, emptySet()).map { it.packageName })
        assertEquals(listOf("com.a", "com.z"), AppPickerOrder.of(twins.reversed(), emptySet()).map { it.packageName })
    }

    @Test
    fun `a chosen package that is not installed changes nothing`() {
        assertEquals(listOf("Alpha", "Bravo", "Charlie", "Delta"), names(selected = setOf("com.gone")))
    }

    @Test
    fun `nothing is added or lost`() {
        val out = AppPickerOrder.of(all, setOf("com.c"), setOf("com.d"))
        assertEquals(all.size, out.size)
        assertEquals(all.toSet(), out.toSet())
    }

    @Test
    fun `an empty list stays empty`() {
        assertEquals(emptyList<AppPickerEntry>(), AppPickerOrder.of(emptyList(), setOf("com.a")))
    }
}
