// Adapted (Apache-2.0, GPLv3-compatible) from
// https://github.com/egdels/makeacopy/blob/main/app/src/main/java/de/schliweb/makeacopy/ml/docquad/DocQuadOrtRunner.java
// Copyright 2025 Christian Kierdorf, licensed under the Apache License, Version 2.0
// (http://www.apache.org/licenses/LICENSE-2.0). Simplified for VoxApps: single-instance lifecycle
// tied to DocumentCropper.init() instead of a lazy static singleton, no NNAPI/XNNPACK EP tiers (this
// model is small enough — 64x64 heatmap output — that plain CPU inference is fast enough per frame;
// EP selection can be added later if profiling shows it's worth the added failure surface).
package com.voxapps.vision.ml.docquad

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer

/** Runs the DocQuadNet-256 ONNX model: a [1,3,256,256] RGB float input in, a segmentation mask
 *  [1,1,64,64] and 4 per-corner heatmaps [1,4,64,64] out. Model asset loaded once, copied to the
 *  cache dir first so ONNX Runtime can memory-map it instead of loading the full byte array. */
class DocQuadOrtRunner private constructor(private val session: OrtSession, private val env: OrtEnvironment) : AutoCloseable {

    data class Outputs(val maskLogits: Array<Array<Array<FloatArray>>>, val cornerHeatmaps: Array<Array<Array<FloatArray>>>)

    fun run(inputNchw: FloatArray): Outputs {
        require(inputNchw.size == 3 * IN_H * IN_W) { "inputNchw must have length ${3 * IN_H * IN_W}" }
        val inputShape = longArrayOf(1, 3, IN_H.toLong(), IN_W.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(inputNchw), inputShape).use { input ->
            session.run(mapOf("input" to input)).use { results ->
                @Suppress("UNCHECKED_CAST")
                val maskLogits = results.get("mask_logits").get().value as Array<Array<Array<FloatArray>>>
                @Suppress("UNCHECKED_CAST")
                val cornerHeatmaps = results.get("corner_heatmaps").get().value as Array<Array<Array<FloatArray>>>
                return Outputs(maskLogits, cornerHeatmaps)
            }
        }
    }

    override fun close() {
        session.close()
    }

    companion object {
        const val IN_H = 256
        const val IN_W = 256
        const val OUT_H = 64
        const val OUT_W = 64

        /** Loads the model fresh — caller owns the lifecycle (see DocumentCropper.init/release). */
        fun create(context: Context, modelAssetPath: String): DocQuadOrtRunner {
            val env = OrtEnvironment.getEnvironment()
            val modelFile = copyAssetToCache(context, modelAssetPath)
            OrtSession.SessionOptions().use { opts ->
                opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                opts.setIntraOpNumThreads(maxOf(1, Runtime.getRuntime().availableProcessors() / 2))
                val session = env.createSession(modelFile.absolutePath, opts)
                return DocQuadOrtRunner(session, env)
            }
        }

        private fun copyAssetToCache(context: Context, assetPath: String): File {
            val baseName = File(assetPath).name
            val versionCode = try {
                context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
            } catch (t: Throwable) {
                -1L
            }
            val outFile = File(context.cacheDir, "${versionCode}_$baseName")
            if (!outFile.exists()) {
                context.assets.open(assetPath).use { input ->
                    FileOutputStream(outFile).use { output -> input.copyTo(output) }
                }
                // Clean up stale cached copies from previous app versions.
                context.cacheDir.listFiles { _, name -> name.endsWith("_$baseName") && name != outFile.name }
                    ?.forEach { it.delete() }
            }
            return outFile
        }
    }
}
