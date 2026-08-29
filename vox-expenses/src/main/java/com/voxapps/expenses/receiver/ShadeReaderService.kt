package com.voxapps.expenses.receiver

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import com.voxapps.logging.Logger
import java.util.concurrent.Executors

private const val TAG = "ShadeReaderService"

/**
 * Recovers what the platform's code-protection guard withholds from a notification listener — by
 * reading the one surface where the payment still exists whole: the notification shade the person
 * is already looking at.
 *
 * The guard redacts the *listener's* copy of a sensitive notification, not what the screen renders,
 * so a stub captured from a gutted delivery is completed here: the shade is opened, the screen is
 * captured with [takeScreenshot] (an accessibility service's own capability — no projection-consent
 * dialog), and the figure is read back off the pixels. Nothing here reads a notification the person
 * has not already summoned; the action rides a tap they made.
 *
 * OEM-fragile by nature (Honor's shade layout is its own), so every step logs and no failure is
 * silent — a recovery that cannot read the screen leaves the stub exactly where it was, in review.
 */
class ShadeReaderService : AccessibilityService() {

    private val io = Executors.newSingleThreadExecutor()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Logger.d(TAG, "connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /**
     * Opens the shade, reads every piece of text the panel renders out of the accessibility tree,
     * and hands it over — the primary recovery path: exact text, no screenshot, no OCR. A stricter
     * OEM may strip the sensitive lines from the tree while still rendering them; the caller falls
     * back to [captureShade] when the tree comes up empty of what it needed.
     */
    fun readShadeText(onText: (List<String>) -> Unit) {
        performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        io.execute {
            Thread.sleep(700)
            val lines = mutableListOf<String>()
            runCatching {
                // The shade is a system window of its own, not the active app window — so every
                // retrievable window is walked, and the one the platform draws notifications in
                // (systemui) is read. rootInActiveWindow would only ever see the app underneath.
                val wins = windows
                for (w in wins) {
                    val root = w.root ?: continue
                    if (root.packageName == "com.android.systemui") collectText(root, lines)
                    root.recycle()
                }
            }.onFailure { Logger.w(TAG, "node read failed: ${it.message}") }
            Logger.d(TAG, "shade tree yielded ${lines.size} text nodes")
            onText(lines)
            closeShade()
        }
    }

    private fun collectText(node: android.view.accessibility.AccessibilityNodeInfo, out: MutableList<String>) {
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                collectText(child, out)
                child.recycle()
            }
        }
    }

    /**
     * Opens the shade, captures it, hands the bitmap to [onCaptured], then closes the shade again.
     * The open→settle→capture→close sequence is deliberately paced: the shade animates in, and a
     * capture taken mid-animation catches a half-drawn panel.
     */
    fun captureShade(onCaptured: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Logger.w(TAG, "takeScreenshot needs API 30+")
            onCaptured(null)
            return
        }
        performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        // The shade's entrance animation; capture before it settles catches a half-drawn panel.
        io.execute {
            Thread.sleep(700)
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                io,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val bmp = runCatching {
                            Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                        }.getOrNull()
                        runCatching { screenshot.hardwareBuffer.close() }
                        Logger.d(TAG, "screenshot ${if (bmp != null) "${bmp.width}x${bmp.height}" else "FAILED to wrap"}")
                        onCaptured(bmp)
                        closeShade()
                    }

                    override fun onFailure(errorCode: Int) {
                        Logger.w(TAG, "takeScreenshot failed: $errorCode")
                        onCaptured(null)
                        closeShade()
                    }
                }
            )
        }
    }

    private fun closeShade() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        } else {
            @Suppress("DEPRECATION")
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    companion object {
        @Volatile
        var instance: ShadeReaderService? = null
            private set
    }
}
