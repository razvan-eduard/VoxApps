// Copyright (c) 2026 PaddlePaddle Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.paddle.ocr.util

import org.opencv.core.Mat

object ImageUtils {

    fun resizeToMultipleOf32(
        src: Mat,
        limitSideLen: Int,
        limitType: String,
        maxSideLimit: Int,
    ): Mat {
        val h = src.rows()
        val w = src.cols()
        var ratio = when (limitType.lowercase()) {
            "max" -> if (maxOf(h, w) > limitSideLen) limitSideLen.toDouble() / maxOf(h, w) else 1.0
            "min" -> if (minOf(h, w) < limitSideLen) limitSideLen.toDouble() / minOf(h, w) else 1.0
            "resize_long" -> limitSideLen.toDouble() / maxOf(h, w)
            else -> throw IllegalArgumentException("Unsupported det limit type: $limitType")
        }

        var newH = (h * ratio).toInt()
        var newW = (w * ratio).toInt()
        if (maxOf(newH, newW) > maxSideLimit) {
            ratio = maxSideLimit.toDouble() / maxOf(newH, newW)
            newH = (newH * ratio).toInt()
            newW = (newW * ratio).toInt()
        }

        newH = maxOf(MathUtils.roundHalfToEven(newH / 32.0) * 32, 32)
        newW = maxOf(MathUtils.roundHalfToEven(newW / 32.0) * 32, 32)
        return nearestNeighborResize(src, newW, newH)
    }

    // VoxApps patch: Imgproc.resize() has a confirmed native SIGSEGV in the vendored
    // libopencv_imgproc.so build used by this app (SEGV_ACCERR at a tagged-pointer address —
    // Scudo's hardened allocator catching an out-of-bounds native read), reproduced on-device across
    // multiple call sites and confirmed unaffected by Core.setUseOptimized(false), i.e. it isn't gated
    // behind OpenCV's IPP/optimized-path flag. It's intermittent (depends on where the allocator
    // happens to place the buffer, not on input size or call count), consistent with a genuine
    // out-of-bounds read in that .so's resize kernel. This does the same spatial resize by hand over
    // the Mat's raw bytes instead, so it never touches that native routine. Channel-count agnostic
    // (works for the 3-channel BGR/RGB Mats this function actually receives) since it doesn't need to
    // know color order, only bytes-per-pixel.
    private fun nearestNeighborResize(src: Mat, newW: Int, newH: Int): Mat {
        val channels = src.channels()
        val srcH = src.rows()
        val srcW = src.cols()
        val srcData = ByteArray(srcH * srcW * channels)
        src.get(0, 0, srcData)

        val dstData = ByteArray(newH * newW * channels)
        for (y in 0 until newH) {
            val srcY = (y * srcH / newH).coerceIn(0, srcH - 1)
            val srcRowOffset = srcY * srcW * channels
            val dstRowOffset = y * newW * channels
            for (x in 0 until newW) {
                val srcX = (x * srcW / newW).coerceIn(0, srcW - 1)
                val srcOffset = srcRowOffset + srcX * channels
                val dstOffset = dstRowOffset + x * channels
                System.arraycopy(srcData, srcOffset, dstData, dstOffset, channels)
            }
        }

        val dst = Mat(newH, newW, src.type())
        dst.put(0, 0, dstData)
        return dst
    }
}
