// Adapted (Apache-2.0, GPLv3-compatible) from
// https://github.com/egdels/makeacopy/blob/main/app/src/main/java/de/schliweb/makeacopy/ml/docquad/DocQuadPostprocessor.java
// Copyright 2025 Christian Kierdorf, licensed under the Apache License, Version 2.0
// (http://www.apache.org/licenses/LICENSE-2.0). Simplified for VoxApps: ports only the primary
// corner-heatmap path (argmax + 5x5 sub-pixel refinement) — the original also fuses in a
// segmentation-mask-derived quad with an agreement-scoring system between the two, and flags
// "suspicious" detections for product telemetry. Neither is needed here: the corner-heatmap path
// alone is already a large step up from classical Canny/contour detection, and DocumentCropper's own
// existing rectScore/aspect-ratio/convexity validation (shared with the classical fallback) already
// rejects implausible quads downstream.
package com.voxapps.vision.ml.docquad

import kotlin.math.exp

object DocQuadPostprocessor {

    /** Argmax + sub-pixel refinement of each of the 4 corner heatmaps (shape [1,4,64,64]) into
     *  [4][2] (x,y) coordinates in the model's 256x256 input space. Per corner: parabolic 1D fit
     *  along each axis when the peak isn't on the heatmap's edge and the neighborhood is concave
     *  (a real, well-formed peak); otherwise a 5x5 softmax-weighted centroid around the argmax as a
     *  softer fallback. */
    fun refineCorners(cornerHeatmaps: Array<Array<Array<FloatArray>>>): Array<DoubleArray> {
        val corners256 = Array(4) { DoubleArray(2) }
        for (c in 0 until 4) {
            val hm = cornerHeatmaps[0][c]
            var best = -Float.MAX_VALUE
            var bestX = 0
            var bestY = 0
            for (y in 0 until 64) {
                val row = hm[y]
                for (x in 0 until 64) {
                    val v = row[x]
                    if (v > best) {
                        best = v
                        bestX = x
                        bestY = y
                    }
                }
            }

            var dx = 0.0
            var dxValid = false
            if (bestX in 1..62) {
                val l = hm[bestY][bestX - 1].toDouble()
                val cv = hm[bestY][bestX].toDouble()
                val r = hm[bestY][bestX + 1].toDouble()
                val denom = l - 2.0 * cv + r
                if (denom < -1e-12) {
                    dx = (0.5 * (l - r) / denom).coerceIn(-0.5, 0.5)
                    dxValid = true
                }
            }
            var dy = 0.0
            var dyValid = false
            if (bestY in 1..62) {
                val t = hm[bestY - 1][bestX].toDouble()
                val cv = hm[bestY][bestX].toDouble()
                val b = hm[bestY + 1][bestX].toDouble()
                val denom = t - 2.0 * cv + b
                if (denom < -1e-12) {
                    dy = (0.5 * (t - b) / denom).coerceIn(-0.5, 0.5)
                    dyValid = true
                }
            }

            val x64: Double
            val y64: Double
            if (dxValid || dyValid) {
                x64 = bestX + 0.5 + dx
                y64 = bestY + 0.5 + dy
            } else {
                val x0 = maxOf(0, bestX - 2)
                val x1 = minOf(63, bestX + 2)
                val y0 = maxOf(0, bestY - 2)
                val y1 = minOf(63, bestY + 2)
                var maxLogit = Double.NEGATIVE_INFINITY
                for (y in y0..y1) {
                    val row = hm[y]
                    for (x in x0..x1) if (row[x] > maxLogit) maxLogit = row[x].toDouble()
                }
                var sumW = 0.0
                var sumX = 0.0
                var sumY = 0.0
                for (y in y0..y1) {
                    val row = hm[y]
                    for (x in x0..x1) {
                        val w = exp(row[x] - maxLogit)
                        sumW += w
                        sumX += w * (x + 0.5)
                        sumY += w * (y + 0.5)
                    }
                }
                if (sumW == 0.0 || !sumW.isFinite()) {
                    x64 = bestX + 0.5
                    y64 = bestY + 0.5
                } else {
                    x64 = sumX / sumW
                    y64 = sumY / sumW
                }
            }

            corners256[c][0] = x64 * 4.0
            corners256[c][1] = y64 * 4.0
        }
        return corners256
    }

    /** Maps [corners256] (model 256x256 input space) back to the original source image's pixel
     *  coordinates via [lb]'s inverse letterbox transform. */
    fun mapToOriginal(corners256: Array<DoubleArray>, lb: DocQuadLetterbox): Array<DoubleArray> =
        Array(4) { i -> lb.inverse(corners256[i][0], corners256[i][1]) }
}
