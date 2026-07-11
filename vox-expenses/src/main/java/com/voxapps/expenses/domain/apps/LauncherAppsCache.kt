package com.voxapps.expenses.domain.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.voxapps.apppicker.AppPickerEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * In-memory + persisted cache of launcher apps for [com.voxapps.apppicker.AppPickerCard] callers
 * (currently just the notification-source picker). Scanning is a single `queryIntentActivities`
 * call — unlike vox-commander's [com.voxapps.commander.domain.intent.registry.AppRegistry], there's
 * no per-app intent probing here, so this stays a plain object rather than needing a dedicated splash
 * screen: [ExpensesContainer][com.voxapps.expenses.di.ExpensesContainer] warms it synchronously at
 * app startup (before any UI composes), and the persisted [toJsonCache] copy means even that first
 * scan is skipped on every launch after the first.
 */
object LauncherAppsCache {

    enum class ScanStatus { IDLE, SCANNING, DONE }

    private val _scanStatus = MutableStateFlow(ScanStatus.IDLE)
    val scanStatus: StateFlow<ScanStatus> = _scanStatus

    private var apps: List<AppPickerEntry> = emptyList()

    val cachedApps: List<AppPickerEntry> get() = apps

    fun scan(context: Context) {
        _scanStatus.value = ScanStatus.SCANNING
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        apps = pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_DEFAULT_ONLY)
            .map {
                AppPickerEntry(
                    packageName = it.activityInfo.packageName,
                    displayName = it.loadLabel(pm).toString(),
                    isSystemApp = (it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                )
            }
            .distinctBy { it.packageName }
            .filter { it.packageName != context.packageName }
            .sortedBy { it.displayName.lowercase() }
        _scanStatus.value = ScanStatus.DONE
    }

    fun loadFromCache(json: String?): Boolean {
        if (json.isNullOrBlank()) return false
        return try {
            val array = JSONArray(json)
            if (array.length() == 0) return false
            val loaded = (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                AppPickerEntry(
                    packageName = o.getString("packageName"),
                    displayName = o.getString("displayName"),
                    isSystemApp = o.optBoolean("isSystemApp", false)
                )
            }
            apps = loaded
            _scanStatus.value = ScanStatus.DONE
            true
        } catch (e: Exception) {
            false
        }
    }

    fun toJsonCache(): String {
        val array = JSONArray()
        apps.forEach { app ->
            array.put(
                JSONObject().apply {
                    put("packageName", app.packageName)
                    put("displayName", app.displayName)
                    put("isSystemApp", app.isSystemApp)
                }
            )
        }
        return array.toString()
    }

    fun rescanAndCache(context: Context): String {
        scan(context)
        return toJsonCache()
    }
}
