package com.voxapps.voxconnect

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import com.voxapps.ipc.VoxAppsDiscovery
import com.voxapps.ipc.VoxCommand
import com.voxapps.ipc.VoxDataTransferClient
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxResult
import com.voxapps.logging.Logger
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections

/**
 * The VoxConnect Bridge: an embedded HTTP+WebSocket server hosted inside Vox Hub, letting a paired
 * VoxConnect desktop instance send/receive commands over the local network using the same
 * [VoxCommand]/[VoxResult] JSON contract satellite apps already speak over `core/ipc`'s broadcast
 * bus — this server is just a network-facing front door onto that same existing bus, not a new
 * command language.
 *
 * Every route requires `Authorization: Bearer <deviceId>` naming an entry in [deviceStore] —
 * including `/events`, checked manually right after the WebSocket upgrade since Ktor's `webSocket {}`
 * DSL has no route-level auth hook the way `get`/`post` do. POST bodies are [AesGcmCipher]-encrypted
 * with that device's session key (so is every response), even on a trusted LAN — defense in depth,
 * since the transport itself is plain HTTP. GET routes carry no request payload to encrypt; only
 * their responses are. This server has no role in *establishing* a pairing — the PC generates the
 * pairing QR and the phone pushes its credential to a temporary listener the PC runs for that one
 * handshake (see [VoxConnectPairing]); this server only ever serves an already-paired device.
 */
class VoxConnectServer(
    private val context: Context,
    private val deviceStore: PairedDeviceStore,
    private val allowedDomains: () -> Set<String>,
    private val mediaControlEnabled: () -> Boolean
) {
    private var server: EmbeddedServer<*, *>? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** Every currently-open `/events` session, so [broadcastMonitoredDomainsChanged] (called from a
     *  settings-flow collector, not from inside a route handler) can push to all of them — multiple
     *  desktops can be paired and connected at once, per [PairedDeviceStore]. */
    private val eventSessions = Collections.synchronizedSet(mutableSetOf<DefaultWebSocketServerSession>())

    fun start(port: Int) {
        if (server != null) return
        // Without this, Ktor CIO's NIO selector binds a genuinely IPv6-only socket on this Android
        // runtime (confirmed via `netstat`: `tcp6 [::]:PORT`, not dual-stack) even though the host
        // is left as the "0.0.0.0" default — a desktop VoxConnect client connecting over plain IPv4
        // then gets a silent TCP-level connect timeout (ICMP ping to the phone still succeeds; only
        // the port is unreachable), which is indistinguishable from the phone actually being off the
        // network. Forcing IPv4 here is a known, standard fix for this class of dual-stack binding
        // mismatch and must be set before the very first socket opens.
        System.setProperty("java.net.preferIPv4Stack", "true")
        acquireLocks()
        server = embeddedServer(CIO, port = port) {
            install(WebSockets)
            routing {
                get("/apps") { authenticated { handleApps() } }
                post("/command") { authenticated { handleCommand() } }
                get("/media") { authenticated { handleMediaStatus() } }
                post("/media/{action}") { authenticated { handleMediaAction() } }
                post("/query") { authenticated { handleQuery() } }
                webSocket("/events") {
                    val header = call.request.header("Authorization")
                    val deviceId = header?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotBlank() }
                    if (deviceId == null || deviceStore.getDevice(deviceId) == null) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
                        return@webSocket
                    }
                    eventSessions += this
                    try {
                        // No client->server events are defined yet — this just holds the connection
                        // open for pushes (see broadcastMonitoredDomainsChanged) until the peer closes.
                        for (frame in incoming) {
                            if (frame is Frame.Close) break
                        }
                    } finally {
                        eventSessions -= this
                    }
                }
            }
        }.also { it.start(wait = false) }
        Logger.log("VoxConnect Bridge started on port $port", TAG)
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 200, timeoutMillis = 1000)
        server = null
        eventSessions.clear()
        releaseLocks()
        Logger.log("VoxConnect Bridge stopped", TAG)
    }

    /** Confirmed on-device: without these, the WiFi radio's power-save mode on screen-off wedges
     *  the embedded server's accept loop — the process survives and the socket stays bound, but no
     *  new connection completes, and it never self-heals even once the screen wakes back up and WiFi
     *  latency recovers; only a full app restart clears it. Held only while the bridge is enabled
     *  (released in [stop]), a deliberate battery-for-reachability tradeoff — this server exists
     *  specifically to stay reachable from a desktop client at any time. */
    private fun acquireLocks() {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:bridge").apply {
            setReferenceCounted(false)
            acquire()
        }
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wifiManager.createWifiLock(lockMode, "$TAG:bridge").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    fun isRunning(): Boolean = server != null

    /** Pushes a `{"op": "monitored_domains_changed", "domains": [...]}` frame to every connected
     *  `/events` session, so an already-paired VoxConnect instance retries a domain it was previously
     *  blocked from (or drops one it can no longer reach) immediately instead of waiting for its next
     *  poll tick. Safe to call from outside any route handler — [DefaultWebSocketServerSession.send]
     *  delegates to a channel send, which is concurrency-safe; a dead/closed peer's send failure just
     *  evicts it from [eventSessions] rather than breaking the broadcast for anyone else. */
    suspend fun broadcastMonitoredDomainsChanged(domains: Set<String>) {
        val payload = JSONObject()
            .put("op", "monitored_domains_changed")
            .put("domains", JSONArray(domains))
            .toString()
        for (session in eventSessions.toList()) {
            try {
                session.send(Frame.Text(payload))
            } catch (e: Exception) {
                Logger.w(TAG, "Dropping dead /events session: ${e.message}")
                eventSessions -= session
            }
        }
    }

    private suspend fun RoutingContext.authenticated(block: suspend (PairedDevice) -> Unit) {
        // Confirmed on-device: this Android CIO build leaves keep-alive HTTP connections in
        // CLOSE_WAIT after VoxConnect's periodic polling (MediaCard every 15s, SyncEngine every
        // 60s) is done with them — they're never reaped, and enough of them piling up eventually
        // starves the server into accepting connections but never responding. Every plain HTTP
        // route (everything but the persistent /events WebSocket) is short-lived request/response
        // anyway, so there's no real cost to declaring the connection closed and letting each poll
        // open a fresh one — cheap on a LAN, and it sidesteps the leak entirely rather than trying
        // to reap it after the fact.
        call.response.header(HttpHeaders.Connection, "close")
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
