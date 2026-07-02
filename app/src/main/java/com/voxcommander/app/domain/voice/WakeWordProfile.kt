package com.voxcommander.app.domain.voice

import org.json.JSONObject

data class WakeWordProfile(
    val rmsThreshold: Float,
    val minRms: Float,
    val maxRms: Float,
    val avgRms: Float,
    val peakFreqLow: Float,
    val peakFreqHigh: Float,
    val wakeWord: String,
    val calibrationDate: Long,
    val voicePrint: String? = null,
    val similarityThreshold: Float = 0.65f,
    val wakeWordTemplate: String? = null,
    val templateThreshold: Float = 0.45f,
    val profileName: String? = null,
    val noiseFloorRms: Float = 0f
) {
    companion object {
        fun fromJson(jsonString: String): WakeWordProfile? {
            return try {
                val json = JSONObject(jsonString)
                WakeWordProfile(
                    rmsThreshold = json.getDouble("rmsThreshold").toFloat(),
                    minRms = json.getDouble("minRms").toFloat(),
                    maxRms = json.getDouble("maxRms").toFloat(),
                    avgRms = json.getDouble("avgRms").toFloat(),
                    peakFreqLow = json.getDouble("peakFreqLow").toFloat(),
                    peakFreqHigh = json.getDouble("peakFreqHigh").toFloat(),
                    wakeWord = json.getString("wakeWord"),
                    calibrationDate = json.getLong("calibrationDate"),
                    voicePrint = if (json.has("voicePrint")) json.getString("voicePrint") else null,
                    similarityThreshold = if (json.has("similarityThreshold")) json.getDouble("similarityThreshold").toFloat() else 0.65f,
                    wakeWordTemplate = if (json.has("wakeWordTemplate")) json.getString("wakeWordTemplate") else null,
                    templateThreshold = if (json.has("templateThreshold")) json.getDouble("templateThreshold").toFloat() else 0.45f,
                    profileName = if (json.has("profileName")) json.getString("profileName") else null,
                    noiseFloorRms = if (json.has("noiseFloorRms")) json.getDouble("noiseFloorRms").toFloat() else 0f
                )
            } catch (e: Exception) {
                null
            }
        }

        fun toJson(profile: WakeWordProfile): String {
            return JSONObject().apply {
                put("rmsThreshold", profile.rmsThreshold.toDouble())
                put("minRms", profile.minRms.toDouble())
                put("maxRms", profile.maxRms.toDouble())
                put("avgRms", profile.avgRms.toDouble())
                put("peakFreqLow", profile.peakFreqLow.toDouble())
                put("peakFreqHigh", profile.peakFreqHigh.toDouble())
                put("wakeWord", profile.wakeWord)
                put("calibrationDate", profile.calibrationDate)
                profile.voicePrint?.let { put("voicePrint", it) }
                put("similarityThreshold", profile.similarityThreshold.toDouble())
                profile.wakeWordTemplate?.let { put("wakeWordTemplate", it) }
                put("templateThreshold", profile.templateThreshold.toDouble())
                profile.profileName?.let { put("profileName", it) }
                put("noiseFloorRms", profile.noiseFloorRms.toDouble())
            }.toString()
        }
    }
}
