package com.voxapps.commander.domain.engine

/**
 * An engine and the model to run on it — the pair every domain stores and each one spells
 * differently (`aiProcessor`/`activeIntentModelId`, `voiceProcessor`/`activeVoiceModelId`,
 * `wakeWordEngineType`/`wakeWordModelPath`, `ttsEngineType`/`piperVoiceModelId`).
 *
 * It exists as a type because a selection has to be *passed*, not looked up. An engine that reads
 * the user's active model from settings can only ever run the active model — which is why the
 * configured intent fallback did nothing at all: the fallback stage asked the same interpreter,
 * which loaded `activeIntentModelId`, the primary's model, and re-ran what had just failed.
 *
 * [modelId] is null for an engine whose model is not ours to choose — a cloud API, or a platform
 * service that has exactly one.
 */
data class EngineSelection(val engineKey: String, val modelId: String? = null)
