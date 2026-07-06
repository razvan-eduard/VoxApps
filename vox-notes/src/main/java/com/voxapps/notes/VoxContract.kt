package com.voxapps.notes

/**
 * The Vox intent contract — declared LOCALLY (no shared library). VoxCommander is completely
 * independent; if it's installed it may hand this app a note via this native intent. Any app can
 * integrate by declaring the same action/extra strings — no dependency required.
 */
object VoxContract {
    const val ACTION_HANDLE = "com.voxapps.action.HANDLE"
    const val CATEGORY_VOX = "com.voxapps.category.VOX"
    const val EXTRA_QUERY = "com.voxapps.extra.QUERY"
}
