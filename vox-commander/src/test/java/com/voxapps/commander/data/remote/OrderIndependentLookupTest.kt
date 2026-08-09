package com.voxapps.commander.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the step that replaced "first engine in the map" lookups with runtime-constrained ones.
 *
 * Those lookups are masked today because the registry holds only downloadable engines, so every
 * candidate happens to be the right one. They become live bugs the moment cloud and OS-supplied
 * engines join the map, because the winner is then decided by JSON key order. Asserting the old and
 * new expressions agree against the *shipped* schema is what makes the change verifiable as a no-op
 * rather than merely believed to be one.
 */
class OrderIndependentLookupTest {

    private fun shippedSchema(): RemoteModelSchema {
        val file = listOf("src/main/assets/schemas/models.json", "vox-commander/src/main/assets/schemas/models.json")
            .map { java.io.File(it) }.firstOrNull { it.exists() }
        assertTrue("models.json not found from ${java.io.File(".").absolutePath}", file != null)
        return com.google.gson.Gson().fromJson(file!!.readText(), RemoteModelSchema::class.java)
    }

    private fun voiceKeys(schema: RemoteModelSchema) =
        schema.engines.filter { "voice" in it.value.type }.keys.toList()

    private fun isArchive(schema: RemoteModelSchema, key: String) =
        RemoteModelRegistry.ARCHIVE_EXTENSIONS.any { schema.engines[key]?.extension.equals(it, ignoreCase = true) }

    private fun isLocalFile(schema: RemoteModelSchema, key: String) =
        EngineRuntime.fromKey(schema.engines[key]?.runtime) == EngineRuntime.LOCAL_FILE

    @Test
    fun `constraining the default voice engine to local_file changes nothing today`() {
        val schema = shippedSchema()
        val keys = voiceKeys(schema)

        val old = keys.firstOrNull()
        val new = keys.firstOrNull { isLocalFile(schema, it) } ?: keys.firstOrNull()

        assertEquals(old, new)
    }

    @Test
    fun `splitting the model lists by packaging changes nothing today`() {
        val schema = shippedSchema()
        val keys = voiceKeys(schema)

        // Old: "first non-zip voice engine" / "first zip voice engine".
        val oldFileBased = keys.firstOrNull { !schema.engines[it]!!.extension.equals(".zip", true) }
        val oldDirBased = keys.firstOrNull { schema.engines[it]!!.extension.equals(".zip", true) }

        // New: restricted to downloadable engines, and split on archive rather than on ".zip".
        val downloadable = keys.filter { isLocalFile(schema, it) }
        val newFileBased = downloadable.firstOrNull { !isArchive(schema, it) }
        val newDirBased = downloadable.firstOrNull { isArchive(schema, it) }

        assertEquals(oldFileBased, newFileBased)
        assertEquals(oldDirBased, newDirBased)
    }

    /**
     * The fallback checkbox is enabled by `isBuiltIn || isModelDownloaded`, and every virtual model
     * is built-in — so a cloud service would offer itself as the *offline* fallback as soon as one
     * joined the registry. Excluding declared-cloud engines is what stops that, and today, with no
     * engine declaring `cloud`, it excludes nothing.
     */
    @Test
    fun `excluding cloud engines from the fallback offer changes nothing today`() {
        val schema = shippedSchema()

        val excluded = schema.engines.keys.filter {
            EngineRuntime.fromKey(schema.engines[it]?.runtime) == EngineRuntime.CLOUD
        }

        assertEquals(emptyList<String>(), excluded)
    }

    /**
     * The reason the constraints exist at all. If a voice engine were ever listed before the local
     * ones without being local itself, the unconstrained lookups would hand a fresh install a
     * processor that cannot transcribe anything without a key it has not been given.
     */
    @Test
    fun `an engine ordered first but not local_file would break the unconstrained lookups`() {
        val schema = shippedSchema()
        val keys = voiceKeys(schema)
        val withCloudFirst = listOf("SOME_CLOUD_STT") + keys

        val old = withCloudFirst.firstOrNull()
        val new = withCloudFirst.firstOrNull { isLocalFile(schema, it) } ?: withCloudFirst.firstOrNull()

        assertEquals("SOME_CLOUD_STT", old)
        assertEquals(keys.first(), new)
    }
}
