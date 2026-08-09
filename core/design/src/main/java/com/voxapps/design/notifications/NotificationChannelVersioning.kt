package com.voxapps.design.notifications

/**
 * Shared logic behind the "_v1, _v2, ..." notification channel rotation used by every per-app
 * notifier (ReminderNotifier, SpendingLimitNotifier, ...): a channel's sound/vibration/importance
 * are immutable once created, so a settings change mints a new versioned channel id instead of
 * mutating the old one. Left uncleaned, every settings change permanently orphans the previous
 * channel — Android never deletes it on its own, so it stays visible (generically named, unusable)
 * in the system notification settings forever.
 */
object NotificationChannelVersioning {

    /** Every id in [existingIds] that belongs to [baseId]'s versioned family (either [baseId]
     *  itself or `"${baseId}_v<N>"`) but isn't the currently-active [activeChannelId] — these are
     *  stale and safe to delete. */
    fun staleChannelIds(existingIds: Collection<String>, baseId: String, activeChannelId: String): List<String> {
        val versionedPrefix = "${baseId}_v"
        return existingIds.filter { id ->
            id != activeChannelId && (id == baseId || id.startsWith(versionedPrefix))
        }
    }
}
