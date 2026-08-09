package com.voxapps.commander.utils

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.voxapps.logging.Logger
import java.security.MessageDigest

/**
 * How this build identifies itself to a service that registers Android apps.
 *
 * A developer console asks for the package name and the signing certificate's SHA-1 before it will
 * accept a redirect back into the app, so the setup dialog has to show both. They were written out
 * as literals, which made them wrong for anyone who is not running the release the literals were
 * copied from — a debug build shows a fingerprint it was never signed with, and the console then
 * rejects a redirect that looks correct.
 *
 * Read from the running package instead, so what is on screen is what the app will actually present.
 */
object AppSigningIdentity {

    private const val TAG = "AppSigningIdentity"

    fun packageName(context: Context): String = context.packageName

    /** The signing certificate's SHA-1, colon-separated and uppercase, as consoles expect it. */
    fun signingSha1(context: Context): String? = try {
        val pm = context.packageManager
        val certificate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                .signatures?.firstOrNull()
        }

        certificate?.toByteArray()?.let { bytes ->
            MessageDigest.getInstance("SHA-1").digest(bytes)
                .joinToString(":") { "%02X".format(it) }
        }
    } catch (e: Exception) {
        Logger.log("Could not read this build's signing certificate: ${e.message}", TAG)
        null
    }
}
