// Adapted (Apache-2.0, GPLv3-compatible) from
// https://github.com/egdels/makeacopy/blob/main/app/src/main/java/de/schliweb/makeacopy/ml/corners/DocQuadDetector.java
// Copyright 2025 Christian Kierdorf, licensed under the Apache License, Version 2.0
// (http://www.apache.org/licenses/LICENSE-2.0).
package com.voxapps.vision.ml.docquad

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/** Runs DocQuadNet-256 (an ONNX model trained specifically to find document corners — see
 *  [DocQuadOrtRunner]) on [src] and returns the 4 detected corners in [src]'s own pixel coordinates
 *  as (x, y) pairs, ordered [topLeft, topRight, bottomRight, bottomLeft], or `null` if the model
 *  isn't confident or the result doesn't pass basic geometric sanity checks.
 *
 *  Deliberately OpenCV-agnostic (plain `DoubleArray` pairs, not `org.opencv.core.Point`) — this
 *  module has no OpenCV dependency of its own; callers that need an `org.opencv.core.Point` (like
 *  vox-vision's `DocumentCropper`) convert at the call site. */
object DocQuadDetector {

    private const val LETTERBOX_PAD_COLOR = 0xFF808080.toInt() // mid-gray — see renderLetterbox256 doc

    fun detect(runner: DocQuadOrtRunner, src: Bitmap): Array<DoubleArray>? {
        val srcW = src.width
        val srcH = src.height
        if (srcW <= 0 || srcH <= 0) return null

        var in256: Bitmap? = null
        try {
            val lb = DocQuadLetterbox.create(srcW, srcH, DocQuadOrtRunner.IN_W, DocQuadOrtRunner.IN_H)
            in256 = renderLetterbox256(src, lb)
            val input = bitmapToNchwFloat01(in256)
            val outputs = runner.run(input)
            val corners256 = DocQuadPostprocessor.refineCorners(outputs.cornerHeatmaps)
            val cornersOriginal = DocQuadPostprocessor.mapToOriginal(corners256, lb)
            if (!isValidQuad(cornersOriginal, srcW, srcH)) return null
            return cornersOriginal
        } catch (t: Throwable) {
            return null
        } finally {
            in256?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    /** Preprocess exactly as trained: RGB, 0..1, NCHW float32. */
    private fun bitmapToNchwFloat01(bmp: Bitmap): FloatArray {
        val w = bmp.width
        val h = bmp.height
        check(w == DocQuadOrtRunner.IN_W && h == DocQuadOrtRunner.IN_H) { "bitmap must be 256x256" }
        val hw = h * w
        val out = FloatArray(3 * hw)
        val px = IntArray(hw)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = px[y * w + x]
                val idx = y * w + x
                out[idx] = ((c shr 16) and 0xFF) / 255.0f
                out[hw + idx] = ((c shr 8) and 0xFF) / 255.0f
                out[2 * hw + idx] = (c and 0xFF) / 255.0f
            }
        }
        return out
    }

    // Neutral mid-gray padding reduces hard contrast at letterbox borders compared to pure black,
    // which empirically reduces spurious heatmap peaks at the image edge for documents close to the
    // frame border (per the original implementation's own finding).
    private fun renderLetterbox256(src: Bitmap, lb: DocQuadLetterbox): Bitmap {
        val out = Bitmap.createBitmap(DocQuadOrtRunner.IN_W, DocQuadOrtRunner.IN_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(LETTERBOX_PAD_COLOR)

        val left = lb.offsetX.toFloat()
        val top = lb.offsetY.toFloat()
        val right = (lb.offsetX + lb.srcW.toDouble() * lb.scale).toFloat()
        val bottom = (lb.offsetY + lb.srcH.toDouble() * lb.scale).toFloat()
        val dst = RectF(left, top, right, bottom)

        val paint = Paint().apply {
            isFilterBitmap = true
            isDither = true
            isAntiAlias = true
        }
        canvas.drawBitmap(src, null, dst, paint)
        return out
    }

    private fun isFinite(v: Double) = !v.isNaN() && !v.isInfinite()

    private fun isValidQuad(c: Array<DoubleArray>, w: Int, h: Int): Boolean {
        for (i in 0 until 4) {
            val x = c[i][0]
            val y = c[i][1]
            if (!isFinite(x) || !isFinite(y)) return false
            // Tolerate slightly outside the frame, but not wildly off.
            if (x < -w * 0.25 || x > w * 1.25) return false
            if (y < -h * 0.25 || y > h * 1.25) return false
        }
        return isConvexTlTrBrBl(c)
    }

    /** True iff the 4 corners form a strictly convex, non-self-intersecting quad traversed in
     *  TL->TR->BR->BL order (clockwise in image coordinates, where y grows downward) — rejects
     *  flipped/degenerate orderings such as the "cold start" frame before the model has locked onto
     *  the document. */
    private fun isConvexTlTrBrBl(c: Array<DoubleArray>): Boolean {
        var prevSign = 0.0
        for (i in 0 until 4) {
            val a = c[i]
            val b = c[(i + 1) % 4]
            val d = c[(i + 2) % 4]
            val abx = b[0] - a[0]
            val aby = b[1] - a[1]
            val bdx = d[0] - b[0]
            val bdy = d[1] - b[1]
            val cross = abx * bdy - aby * bdx
            if (!isFinite(cross) || cross == 0.0) return false
            val sign = Math.signum(cross)
            if (i == 0) {
                if (sign < 0.0) return false
                prevSign = sign
            } else if (sign != prevSign) {
                return false
            }
        }
        return true
    }
}
