package com.voxapps.schema

/**
 * Marks a data class as the output shape a satellite's extraction parser expects (e.g. Expenses'
 * `ExpenseParseResultParser.Parsed`) — a KSP processor (`:core:schema-processor`) generates a
 * structural field schema (names, types, nullability) from it at compile time, instead of that field
 * list being hand-typed prose in a prompt builder that can silently drift out of sync with the actual
 * parser. Only the mechanical field shape is generated; domain reasoning (e.g. the distributive/
 * cumulative disambiguation rules) stays hand-authored prose alongside it — see the collapsed
 * voice-command plan's section 4.
 *
 * [version] mirrors `models.json`'s `schema_version` convention: bump it whenever this class's field
 * shape changes. The processor stamps the bumped value into the generated schema so a stale cache is
 * diagnosable — informational only, it does not drive any automatic cache invalidation.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class VoxExtractionSchema(val version: Int)
