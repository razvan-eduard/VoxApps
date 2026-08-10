package com.voxapps.services

import com.voxapps.identity.VoxRepo

/**
 * Where the shipped schemas live, in the repository and in the APK.
 *
 * One place because the apps share the arrangement, not the files: each app reads its own folder,
 * and the folder is the list — dropping a JSON into it ships it, with no file name written anywhere
 * in a build script or in code.
 */
object SchemaRepo {

    /** The repository serving the schemas when nothing else is configured. */
    const val DEFAULT_BASE_URL = VoxRepo.URL

    /**
     * Names this repository has had. The project was VoxCommander before it was VoxApps, and both
     * still resolve — GitHub redirects a renamed repository, including raw content.
     *
     * Kept because the URL is *persisted per install*: an install that saved the old name is
     * following the same repository, and must not be treated as somebody's fork just because the
     * project was renamed. See [SchemaSignature.isDefaultRepo] — the difference decides whether a
     * schema counts as signed or merely accepted.
     *
     * The redirect is also why this is worth pinning down: if a new repository ever took the old
     * name, every install still using it would silently start following that one instead.
     */
    val KNOWN_BASE_URLS = listOf(
        DEFAULT_BASE_URL,
        VoxRepo.LEGACY_URL
    )

    /** The folder at the repository root holding every app's schemas. */
    const val FOLDER = "remote-schemas"

    /** Where the copies land inside an APK. Flat, because names are unique across the folders. */
    const val ASSET_FOLDER = "schemas"

    /** For a schema more than one app reads. Empty today; the arrangement is what makes it cheap. */
    const val SHARED = "shared"

    /**
     * Which folder this app's schemas come from — `commander`, `expenses`, and so on.
     *
     * Set once by the Application before any registry starts, so a schema need not repeat it and a
     * second app cannot accidentally fetch the first one's files.
     */
    @Volatile
    var appFolder: String = ""
}
