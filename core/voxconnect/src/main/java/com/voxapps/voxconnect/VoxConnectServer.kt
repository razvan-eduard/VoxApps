package com.voxapps.voxconnect

import android.content.Context
import com.voxapps.ipc.VoxAppsDiscovery
import com.voxapps.ipc.VoxCommand
import com.voxapps.ipc.VoxDataTransferClient
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxResult
import com.voxapps.logging.Logger
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import org.json.JSONArray
import org.json.JSONObject

/**
 * The VoxConnect Bridge: an embedded HTTP+WebSocket server hosted inside Vox Hub, letting a paired
 * VoxConnect desktop instance send/receive commands over the local network using the same
 * [VoxCommand]/[VoxResult] JSON contract satellite apps already speak over `core/ipc`'s broadcast
 * bus — this server is just a network-facing front door onto that same existing bus, not a new
 * command language.
 *
 * Every route requires `Authorization: Bearer <deviceId>` naming an entry in [deviceStore]; POST
 * bodies are [AesGcmCipher]-encrypted with that device's session key (so is every response), even on
 * a trusted LAN — defense in depth, since the transport itself is plain HTTP. GET routes carry no
 * request payload to encrypt; only their responses are. This server has no role in *establishing* a
 * pairing — the PC generates the pairing QR and the phone pushes its credential to a temporary
 * listener the PC runs for that one handshake (see [VoxConnectPairing]); this server only ever serves
 * an already-paired device.
 */
class VoxConnectServer(
    private val context: Context,
    private val deviceStore: PairedDeviceStore,
    private val allowedDomains: () -> Set<String>,
    private val mediaControlEnabled: () -> Boolean
) {
    private var server: EmbeddedServer<*, *>? = null

    fun start(port: Int) {
        if (server != null) return
        server = embeddedServer(CIO, port = port) {
            install(WebSockets)
            routing {
                get("/apps") { authenticated { handleApps() } }
                post("/command") { authenticated { handleCommand() } }
                get("/media") { authenticated { handleMediaStatus() } }
                post("/media/{action}") { authenticated { handleMediaAction() } }
                post("/query") { authenticated { handleQuery() } }
                webSocket("/events") {
                    // Present but intentionally inert for this slice — accepts the upgrade and holds
                    // the connection open, pushes nothing. Live notification/media/data-change
                    // events are a deliberately deferred follow-up (see VoxConnect kickoff prompt).
                    for (frame in incoming) {
                        if (frame is Frame.Close) break
                    }
                }
            }
        }.also { it.start(wait = false) }
        Logger.log("VoxConnect Bridge started on port $port", TAG)
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 200, timeoutMillis = 1000)
        server = null
        Logger.log("VoxConnect Bridge stopped", TAG)
    }

    fun isRunning(): Boolean = server != null

    private suspend fun RoutingContext.authenticated(block: suspend (PairedDevice) -> Unit) {
        val header = call.request.header("Authorization")
        val deviceId = header?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotBlank() }
        val device = deviceId?.let { deviceStore.getDevice(it) }
        if (device == null) {
            call.respond(HttpStatusCode.Unauthorized, "not paired")
            return
        }
        block(device)
    }

    private suspend fun RoutingContext.respondEncrypted(device: PairedDevice, plaintext: String) {
        call.respondText(AesGcmCipher.encrypt(device.sessionKey, plaintext))
    }

    private suspend fun RoutingContext.decryptedBody(device: PairedDevice): String? {
        val token = call.receiveText()
        return AesGcmCipher.decrypt(device.sessionKey, token)
    }

    private suspend fun RoutingContext.handleApps() {
        authenticated { device ->
            val apps = VoxAppsDiscovery.discover(context).filter { it.domain in allowedDomains() }
            val json = JSONArray(apps.map { app ->
                JSONObject()
                    .put("packageName", app.packageName)
                    .put("label", app.label)
                    .put("domain", app.domain)
                    .put("actions", JSONArray(app.actions))
            })
            respondEncrypted(device, json.toString())
        }
    }

    private suspend fun RoutingContext.handleCommand() {
        authenticated { device ->
            val plaintext = decryptedBody(device)
            val command = plaintext?.let { VoxCommand.fromJson(it) }
            if (command == null) {
                respondEncrypted(device, VoxResult(ok = false, text = "malformed command").toJson())
                return@authenticated
            }
            val domain = command.domain
            if (domain == null || domain !in allowedDomains()) {
                respondEncrypted(device, VoxResult(ok = false, text = "domain not monitored").toJson())
                return@authenticated
            }
            val target = VoxAppsDiscovery.discover(context).firstOrNull { it.domain == domain }
            if (target == null) {
                respondEncrypted(device, VoxResult(ok = false, text = "domain unreachable").toJson())
                return@authenticated
            }
            val result = VoxDataTransferClient.sendCommand(context, target.packageName, command)
                ?: VoxResult(ok = false, text = "no response")
            respondEncrypted(device, result.toJson())
        }
    }

    private suspend fun RoutingContext.handleMediaStatus() {
        authenticated { device -> respondEncrypted(device, mediaControl("status")) }
    }

    private suspend fun RoutingContext.handleMediaAction() {
        authenticated { device ->
            val action = call.parameters["action"].orEmpty()
            respondEncrypted(device, mediaControl(action))
        }
    }

    private suspend fun mediaControl(action: String): String {
        if (!mediaControlEnabled()) return VoxResult(ok = false, text = "media control disabled").toJson()
        val result = VoxDataTransferClient.sendCommand(
            context,
            VoxAppsDiscovery.COMMANDER_PACKAGE,
            VoxCommand(op = VoxIpc.OP_MEDIA_CONTROL, mediaAction = action)
        ) ?: VoxResult(ok = false, text = "Commander unreachable")
        return result.toJson()
    }

    private suspend fun RoutingContext.handleQuery() {
        authenticated { device ->
            // Reserved for a future free-form question ("what's the weather now?") forwarded to
            // Commander's existing generic-LLM hook — deliberately not implemented yet.
            respondEncrypted(device, VoxResult(ok = false, text = "not yet implemented").toJson())
        }
    }

    companion object {
        private const val TAG = "VoxConnectServer"
        private const val PAIR_PATH = "/pair"
    }
}
