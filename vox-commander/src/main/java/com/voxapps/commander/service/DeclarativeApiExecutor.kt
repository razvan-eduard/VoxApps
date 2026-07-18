package com.voxapps.commander.service

import com.voxapps.commander.domain.intent.registry.ApiIntegration
import com.voxapps.commander.domain.intent.registry.CapabilitySlot
import com.voxapps.commander.domain.intent.registry.SequenceStep
import com.voxapps.commander.utils.Logger
import com.voxapps.commander.utils.NetworkMonitor
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Generic declarative-REST executor. Generalized from the original Spotify-only `SpotifyWebApi` —
 * `getRequest`/`putRequest`/`postRequest` were already provider-agnostic HTTP helpers; the only
 * real (small) piece of logic, the device-preference heuristic in `findAvailableDevice()`, is
 * lifted here as the generic `device_select` step type.
 *
 * Runs one named capability slot from an [ApiIntegration]: a single `api_call`, a linear
 * `api_sequence` of steps, or a `deep_link` URI template (caller launches it; this executor
 * doesn't touch Android intents).
 */
object DeclarativeApiExecutor {

    private const val TAG = "DeclarativeApiExecutor"
    private val placeholderRegex = Regex("""\{([a-zA-Z0-9_.]+)\}""")

    /** Runs [capabilityName] on [integration]. Returns the slot's result, or null on failure. */
    fun run(integration: ApiIntegration, capabilityName: String, token: String, query: String? = null): String? {
        val slot = integration.capabilities[capabilityName] ?: return null
        val vars = mutableMapOf<String, Any?>("token" to token)
        if (query != null) vars["query"] = query
        return runSlot(integration, slot, token, vars)
    }

    private fun runSlot(integration: ApiIntegration, slot: CapabilitySlot, token: String, vars: MutableMap<String, Any?>): String? {
        return when (slot.type) {
            "api_call" -> executeApiCall(integration, slot.method ?: "GET", slot.path, slot.body, slot.responsePath, token, vars)?.let { (result, _) -> result?.toString() }
            "api_sequence" -> runSequence(integration, slot.steps.orEmpty(), token, vars)
            "deep_link" -> slot.uriTemplate?.let { substituteForPath(it, vars) }
            else -> {
                Logger.log("Unknown capability slot type: ${slot.type}", TAG)
                null
            }
        }
    }

    private fun runSequence(integration: ApiIntegration, steps: List<SequenceStep>, token: String, vars: MutableMap<String, Any?>): String? {
        var finalResult: String? = null

        for (step in steps) {
            if (step.delayBeforeMs > 0) Thread.sleep(step.delayBeforeMs)

            if (step.capability != null) {
                val referenced = integration.capabilities[step.capability] ?: return null
                val result = runSlot(integration, referenced, token, vars) ?: return null
                vars["${step.capability}.result"] = result
                finalResult = result
                continue
            }

            when (step.type) {
                "device_select" -> {
                    val chosen = runDeviceSelect(step, vars)
                    if (chosen != null) {
                        val asKey = step.`as` ?: "device_id"
                        vars[asKey] = chosen
                        finalResult = chosen
                    } else if (!step.optional) {
                        Logger.log("DeclarativeApiExecutor: required device_select found nothing, aborting sequence", TAG)
                        return null
                    }
                    // optional device_select with no match: leave device_id unset and fall through —
                    // later steps that reference {device_id} will fail harmlessly (empty placeholder)
                    // and, if marked optional/stop_on_success themselves, the sequence still reaches
                    // its device-less final fallback step, same as the original hardcoded chain.
                }
                "api_call" -> {
                    val result = runApiCallStepWithRetry(integration, step, token, vars)
                    if (result != null) {
                        step.`as`?.let { vars[it] = result }
                        finalResult = result.toString()
                        if (step.stopOnSuccess) return finalResult
                    } else if (!step.optional && !step.stopOnSuccess) {
                        Logger.log("DeclarativeApiExecutor: required step failed, aborting sequence", TAG)
                        return null
                    }
                    // optional or stop_on_success steps: a failure just falls through to the next step
                }
                else -> {
                    Logger.log("Unknown sequence step type: ${step.type}", TAG)
                    return null
                }
            }
        }

        return finalResult
    }

    private fun runApiCallStepWithRetry(integration: ApiIntegration, step: SequenceStep, token: String, vars: Map<String, Any?>): Any? {
        val attempts = 1 + (step.retry?.times ?: 0)
        var result: Any? = null
        for (attempt in 0 until attempts) {
            result = executeApiCall(integration, step.method ?: "GET", step.path, step.body, step.responsePath, token, vars)?.first
            if (result != null) return result
            if (attempt < attempts - 1) {
                val delay = step.retry?.delayMs ?: 0
                if (delay > 0) Thread.sleep(delay)
            }
        }
        return result
    }

    private fun runDeviceSelect(step: SequenceStep, vars: Map<String, Any?>): String? {
        val fromKey = step.from ?: return null
        val devices = vars[fromKey] as? JSONArray ?: return null
        val idField = step.idField ?: "id"

        for (rule in step.prefer.orEmpty()) {
            for (i in 0 until devices.length()) {
                val device = devices.optJSONObject(i) ?: continue
                if (matchesPreferRule(device, rule.field, rule.equals)) {
                    return device.optString(idField)
                }
            }
        }
        // No preference matched — fall back to the first available device.
        return if (devices.length() > 0) devices.optJSONObject(0)?.optString(idField) else null
    }

    private fun matchesPreferRule(device: JSONObject, field: String, expected: Any?): Boolean {
        if (!device.has(field)) return false
        val actual = device.opt(field)
        return when (expected) {
            is Boolean -> actual == expected
            else -> actual?.toString() == expected?.toString()
        }
    }

    /** Executes one HTTP call. Returns (extractedOrRawResult, rawResponseBody) or null on failure. */
    private fun executeApiCall(
        integration: ApiIntegration,
        method: String,
        path: String?,
        body: String?,
        responsePath: String?,
        token: String,
        vars: Map<String, Any?>
    ): Pair<Any?, String>? {
        if (!NetworkMonitor.isOnline) {
            Logger.log("DeclarativeApiExecutor: no internet connection", TAG)
            return null
        }
        val rawPath = path ?: return null
        val url = integration.baseUrl.trimEnd('/') + substituteForPath(rawPath, vars)
        val requestBody = body?.let { substituteForBody(it, vars) }

        val raw = httpRequest(method, url, token, requestBody) ?: return null
        if (responsePath == null) return raw to raw

        val root = try { JSONObject(raw) } catch (e: Exception) { return null }
        val extracted = extractPath(root, responsePath) ?: return null
        return extracted to raw
    }

    private fun httpRequest(method: String, url: String, token: String, body: String?): String? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.setRequestProperty("Authorization", "Bearer $token")
            if (body != null || method == "POST" || method == "PUT") {
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.use { it.write((body ?: "").toByteArray()) }
            }
            val code = conn.responseCode
            Logger.log("DeclarativeApiExecutor $method $url -> $code", TAG)
            if (code in 200..299) {
                try { conn.inputStream.bufferedReader().use { it.readText() } } catch (e: Exception) { "" }
            } else {
                null
            }
        } catch (e: Exception) {
            Logger.log("DeclarativeApiExecutor $method $url exception: ${e.message}", TAG)
            null
        }
    }

    // --- JSON path extraction: "tracks.items[0].uri", "devices" ---

    private val pathTokenRegex = Regex("""^(\w+)(\[(\d+)])?$""")

    private fun extractPath(root: Any, path: String): Any? {
        var current: Any? = root
        for (rawToken in path.split(".")) {
            if (current == null) return null
            val match = pathTokenRegex.find(rawToken) ?: return null
            val field = match.groupValues[1]
            val index = match.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()

            current = (current as? JSONObject)?.opt(field)
            if (index != null) {
                current = (current as? JSONArray)?.opt(index)
            }
        }
        return current
    }

    // --- Placeholder substitution ---

    private fun substituteForPath(template: String, vars: Map<String, Any?>): String {
        return placeholderRegex.replace(template) { m ->
            val value = vars[m.groupValues[1]]?.toString() ?: ""
            URLEncoder.encode(value, "UTF-8")
        }
    }

    private fun substituteForBody(template: String, vars: Map<String, Any?>): String {
        return placeholderRegex.replace(template) { m ->
            val value = vars[m.groupValues[1]]?.toString() ?: ""
            value.replace("\\", "\\\\").replace("\"", "\\\"")
        }
    }
}
