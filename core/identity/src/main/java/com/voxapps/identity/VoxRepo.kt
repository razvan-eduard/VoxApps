package com.voxapps.identity

/**
 * The repository this build belongs to — written once, for everything that needs to name it.
 *
 * It was written in four places instead, and they disagreed: the native libraries and the schemas
 * pointed at VoxApps while the Whisper engine still pointed at VoxCommander, the name the project
 * had before it was renamed. That kept working only because GitHub redirects a renamed repository,
 * which is not a thing to depend on — the redirect lapses the moment somebody claims the old name,
 * and then one download breaks while every other one carries on, which is the hardest kind of
 * failure to reason about.
 *
 * Renaming the repository, or forking the family somewhere else, should be this file and nothing
 * else.
 */
object VoxRepo {

    const val OWNER = "razvan-eduard"

    const val NAME = "VoxApps"

    /** The repository itself — the default the schema settings show, among other things. */
    const val URL = "https://github.com/$OWNER/$NAME"

    /** Prefix for a release asset; a tag and a file name complete it. See [releaseAsset]. */
    const val RELEASE_DOWNLOAD_BASE = "$URL/releases/download/"

    /**
     * The name this repository had before. Both still resolve, and an install that saved the old
     * one is following the same repository — so it is recognised, never used to build a new URL.
     */
    const val LEGACY_URL = "https://github.com/$OWNER/VoxCommander"

    /** Full URL of one asset on one release, e.g. `releaseAsset("whisper-libs", "libwhisper.so")`. */
    fun releaseAsset(tag: String, fileName: String): String = "$RELEASE_DOWNLOAD_BASE$tag/$fileName"
}
