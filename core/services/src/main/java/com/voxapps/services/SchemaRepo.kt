package com.voxapps.services

/**
 * Where the shipped schemas live, in the repository and in the APK.
 *
 * One place because the apps share the arrangement, not the files: each app reads its own folder,
 * and the folder is the list — dropping a JSON into it ships it, with no file name written anywhere
 * in a build script or in code.
 */
object SchemaRepo {

    /** The repository serving the schemas when nothing else is configured. Written here rather than
     *  in either app so pointing the family somewhere else is one edit. */
    const val DEFAULT_BASE_URL = "https://github.com/razvan-eduard/VoxCommander"

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
