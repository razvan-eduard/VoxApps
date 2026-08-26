package com.voxapps.voxconnect

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.voxapps.logging.Logger
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Scans a QR code shown on a PC screen using the phone's own camera — the phone reliably has one,
 * unlike many desktop PCs. Plain (non-Compose) class wrapping CameraX's [ProcessCameraProvider] +
 * [ImageAnalysis], bindable to any [LifecycleOwner] + [PreviewView] — both plain AndroidX, not
 * Compose-specific, so this stays usable from `core:voxconnect` without pulling in a Compose
 * dependency; the actual on-screen camera preview UI lives in vox-hub.
 *
 * Each analyzed frame's Y (luminance) plane is fed straight to ZXing's [MultiFormatReader] hinted to
 * [BarcodeFormat.QR_CODE] only — no U/V chroma data needed for a QR decode. [onDecoded] fires at most
 * once per [start] call (an [AtomicBoolean] guard, since analysis runs on a background executor and
 * frames keep arriving while a decode is already in flight/being handled by the caller).
 */
class VoxConnectQrScanner(private val onDecoded: (String) -> Unit) {

    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
    }
    private val decoded = AtomicBoolean(false)
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisExecutor: ExecutorService? = null
    // The provider arrives through an async listener; a stop() (or a fresh start()) that lands
    // before it must win — this generation counter is how a stale listener notices it lost.
    private var generation = 0

    fun start(context: Context, lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val myGeneration: Int
        val executor: ExecutorService
        synchronized(this) {
            stopLocked()
            decoded.set(false)
            myGeneration = ++generation
            executor = Executors.newSingleThreadExecutor().also { analysisExecutor = it }
        }
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            synchronized(this) {
                if (generation != myGeneration) {
                    // A stop or a newer start got here first — this binding must not happen.
                    provider.unbindAll()
                    return@addListener
                }
                cameraProvider = provider
            }

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(executor, ::analyze) }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to bind camera for QR scan", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() = synchronized(this) {
        generation++
        stopLocked()
    }

    private fun stopLocked() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        analysisExecutor?.shutdown()
        analysisExecutor = null
    }

    private fun analyze(imageProxy: ImageProxy) {
        if (decoded.get()) {
            imageProxy.close()
            return
        }
        try {
            val (luminance, width, height) = extractRotatedLuminance(imageProxy)
            val source = PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false)
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val result = reader.decodeWithState(bitmap)
            if (decoded.compareAndSet(false, true)) {
                onDecoded(result.text)
            }
        } catch (e: NotFoundException) {
            // No QR code in this frame — the normal case for most frames, not an error.
        } catch (e: Exception) {
            Logger.d(TAG, "QR decode attempt failed: ${e.message}")
        } finally {
            reader.reset()
            imageProxy.close()
        }
    }

    /** Copies the Y plane into a tightly-packed buffer (accounting for row padding — [rowStride] can
     *  exceed [ImageProxy.getWidth] on many devices) and rotates it to upright per
     *  [ImageProxy.getImageInfo]'s [android.media.ImageInfo.getRotationDegrees] — ZXing's luminance
     *  source has no rotation awareness of its own. */
    private fun extractRotatedLuminance(imageProxy: ImageProxy): Triple<ByteArray, Int, Int> {
        val plane = imageProxy.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val width = imageProxy.width
        val height = imageProxy.height

        val packed = ByteArray(width * height)
        buffer.rewind()
        if (rowStride == width) {
            buffer.get(packed)
        } else {
            val row = ByteArray(rowStride)
            for (y in 0 until height) {
                buffer.position(y * rowStride)
                val remaining = buffer.remaining()
                buffer.get(row, 0, minOf(rowStride, remaining))
                System.arraycopy(row, 0, packed, y * width, width)
            }
        }
        return rotate(packed, width, height, imageProxy.imageInfo.rotationDegrees)
    }

    private fun rotate(data: ByteArray, width: Int, height: Int, degrees: Int): Triple<ByteArray, Int, Int> {
        if (degrees == 0) return Triple(data, width, height)
        val rotated = ByteArray(data.size)
        return when (degrees) {
            90 -> {
                for (y in 0 until height) for (x in 0 until width) {
                    rotated[x * height + (height - 1 - y)] = data[y * width + x]
                }
                Triple(rotated, height, width)
            }
            180 -> {
                for (i in data.indices) rotated[data.size - 1 - i] = data[i]
                Triple(rotated, width, height)
            }
            270 -> {
                for (y in 0 until height) for (x in 0 until width) {
                    rotated[(width - 1 - x) * height + y] = data[y * width + x]
                }
                Triple(rotated, height, width)
            }
            else -> Triple(data, width, height)
        }
    }

    companion object {
        private const val TAG = "VoxConnectQrScanner"
    }
}
