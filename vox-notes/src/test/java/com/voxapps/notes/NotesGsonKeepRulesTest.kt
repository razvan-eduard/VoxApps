package com.voxapps.notes

import com.voxapps.testing.GsonKeepRules
import org.junit.Test

/**
 * Every type this module hands to Gson must survive R8 — see [GsonKeepRules] for what that costs
 * when it does not, and why a unit test is the only cheap place to notice.
 */
class NotesGsonKeepRulesTest {

    @Test
    fun `every type parsed by Gson is kept from R8`() {
        GsonKeepRules.assertParsedTypesAreKept(GsonKeepRules.moduleDir("vox-notes"))
    }
}
