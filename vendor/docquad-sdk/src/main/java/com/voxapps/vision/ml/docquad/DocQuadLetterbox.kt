// Adapted (Apache-2.0, GPLv3-compatible) from
// https://github.com/egdels/makeacopy/blob/main/app/src/main/java/de/schliweb/makeacopy/ml/docquad/DocQuadLetterbox.java
// Copyright 2025 Christian Kierdorf, licensed under the Apache License, Version 2.0
// (http://www.apache.org/licenses/LICENSE-2.0).
package com.voxapps.vision.ml.docquad

import kotlin.math.min

/** Scale + offset needed to fit a [srcW]x[srcH] source into a [dstW]x[dstH] destination while
 *  preserving aspect ratio, centered (letterboxed). [forward]/[inverse] convert points between the
 *  two coordinate spaces. */
class DocQuadLetterbox private constructor(
    val srcW: Int,
    val srcH: Int,
    val scale: Double,
    val offsetX: Double,
    val offsetY: Double
) {
    fun inverse(x: Double, y: Double): DoubleArray =
        doubleArrayOf((x - offsetX) / scale, (y - offsetY) / scale)

    companion object {
        fun create(srcW: Int, srcH: Int, dstW: Int, dstH: Int): DocQuadLetterbox {
            require(srcW > 0 && srcH > 0) { "srcW/srcH must be > 0" }
            require(dstW > 0 && dstH > 0) { "dstW/dstH must be > 0" }
            val s = min(dstW.toDouble() / srcW.toDouble(), dstH.toDouble() / srcH.toDouble())
            val newW = srcW * s
            val newH = srcH * s
            val ox = (dstW - newW) / 2.0
            val oy = (dstH - newH) / 2.0
            return DocQuadLetterbox(srcW, srcH, s, ox, oy)
        }
    }
}
