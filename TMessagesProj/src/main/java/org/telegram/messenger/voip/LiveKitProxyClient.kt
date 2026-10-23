package org.telegram.messenger.voip

import livekit.LivekitRtc
import net.impulsem.proxy.ProxyRouting
import net.impulsem.proxy.TurnEndpoint
import net.impulsem.proxy.TurnForwarder
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.telegram.messenger.ImpulseFeatures
import org.telegram.messenger.remotelog.RemoteLog
import org.telegram.tgnet.impulse.proxy.ProxyController
import java.io.IOException
import java.net.InetSocketAddress


/**
 * The OkHttpClient LiveKit uses for a call that goes through the VLESS tunnel.
 *
 * Signalling (HTTP and WebSocket) is delegated to the shared proxied client. The SDK never derives a new client
 * from this one (v2.29.0 only calls newCall and newWebSocket on it), so those two overrides cover all of its traffic.
 * ICE servers announced in the join and reconnect responses are rewritten to loopback forwarders, so media
 * is relayed over TURN through the Xray SOCKS inbound and never leaves the device directly. When none of them
 * is reachable through the tunnel, the signalling fails with [NoTunnelledRelayException] instead.
 */
class LiveKitProxyClient(private val delegate: OkHttpClient) : OkHttpClient() {
    /** No ICE server of the last join or reconnect response could be relayed through the tunnel. */
    class NoTunnelledRelayException : IOException("no ICE server is reachable through the tunnel")


    /** True when the last join or reconnect response left no ICE server reachable through the tunnel. */
    @Volatile
    var relayUnavailable: Boolean = false
        private set


    private class Entry(
        val forwarder: TurnForwarder,
        val socks: InetSocketAddress,
        val port: Int
    )


    private val lock: Any = Any()
    private val forwarders: MutableMap<String, Entry> = mutableMapOf()
    private var closed: Boolean = false


    override fun newCall(request: Request): Call {
        return delegate.newCall(request)
    }


    override fun newWebSocket(
        request: Request,
        listener: WebSocketListener
    ): WebSocket {
        return delegate.newWebSocket(request, RewritingListener(listener))
    }


    /** Stops every TURN forwarder. Idempotent; later signalling messages no longer start any. */
    fun closeForwarders() {
        val stopped: List<Entry>
        synchronized(lock) {
            closed = true
            stopped = forwarders.values.toList()
            forwarders.clear()
        }
        for (entry in stopped) {
            entry.forwarder.close()
        }
    }


    internal fun rewrite(servers: List<LivekitRtc.ICEServer>): List<LivekitRtc.ICEServer> {
        val result: MutableList<LivekitRtc.ICEServer> = mutableListOf()
        for (server in servers) {
            val urls: MutableList<String> = mutableListOf()
            for (url in server.urlsList) {
                val endpoint: TurnEndpoint = TurnEndpoint.parse(url) ?: continue
                val port: Int = forwarderPort(endpoint)
                if (port > 0) {
                    urls.add(endpoint.localUrl(port))
                }
            }
            if (urls.isNotEmpty()) {
                result.add(server.toBuilder().clearUrls().addAllUrls(urls).build())
            }
        }
        return result
    }


    /** Local port of the forwarder for this endpoint, or 0 when the tunnel is not available. */
    private fun forwarderPort(endpoint: TurnEndpoint): Int {
        val key: String = endpoint.host + ":" + endpoint.port + ":" + (if (endpoint.tcp) "tcp" else "udp")
        val controller: ProxyController = ProxyController.getInstance()
        val runtime: ProxyController.Runtime? = controller.runtime()
        if (runtime == null || controller.route() != ProxyRouting.Route.PROXY) {
            return 0
        }
        var stale: Entry? = null
        try {
            synchronized(lock) {
                if (closed) {
                    return 0
                }
                val existing: Entry? = forwarders[key]
                if (existing != null && existing.socks == runtime.socksEndpoint) {
                    return existing.port
                }
                // Drop the old entry first so a failed start cannot leave a closed forwarder in the map.
                stale = forwarders.remove(key)
                val forwarder: TurnForwarder = TurnForwarder(endpoint, runtime.socksEndpoint, runtime.user, runtime.password)
                val port: Int = forwarder.start()
                forwarders[key] = Entry(forwarder, runtime.socksEndpoint, port)
                return port
            }
        } catch (exception: Exception) {
            // IOException or anything unexpected from the forwarder: fail closed, the URL is dropped.
            return 0
        } finally {
            stale?.forwarder?.close()
        }
    }


    private inner class RewritingListener(private val target: WebSocketListener) : WebSocketListener() {
        override fun onOpen(
            webSocket: WebSocket,
            response: Response
        ) {
            target.onOpen(webSocket, response)
        }


        override fun onMessage(
            webSocket: WebSocket,
            text: String
        ) {
            target.onMessage(webSocket, text)
        }


        override fun onMessage(
            webSocket: WebSocket,
            bytes: ByteString
        ) {
            val rewritten: ByteString? = rewriteSignal(bytes)
            if (rewritten == null) {
                // Without a tunnelled relay the SDK would fall back to direct media; end the signalling instead.
                // The cancel reaches onFailure below, which reports NoTunnelledRelayException.
                webSocket.cancel()
                return
            }
            target.onMessage(webSocket, rewritten)
        }


        override fun onClosing(
            webSocket: WebSocket,
            code: Int,
            reason: String
        ) {
            target.onClosing(webSocket, code, reason)
        }


        override fun onClosed(
            webSocket: WebSocket,
            code: Int,
            reason: String
        ) {
            target.onClosed(webSocket, code, reason)
        }


        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?
        ) {
            target.onFailure(webSocket, if (relayUnavailable) NoTunnelledRelayException() else t, response)
        }
    }


    /** The signal with its ICE servers rewritten, or null when none of them is reachable through the tunnel. */
    private fun rewriteSignal(bytes: ByteString): ByteString? {
        val response: LivekitRtc.SignalResponse
        try {
            response = LivekitRtc.SignalResponse.parseFrom(bytes.toByteArray())
        } catch (exception: Exception) {
            return bytes
        }
        if (response.hasJoin()) {
            val join: LivekitRtc.JoinResponse = response.join
            val servers: List<LivekitRtc.ICEServer> = rewriteAnnounced("join", join.iceServersList) ?: return null
            return response.toBuilder()
                .setJoin(join.toBuilder().clearIceServers().addAllIceServers(servers))
                .build()
                .toByteArray()
                .toByteString()
        }
        if (response.hasReconnect()) {
            val reconnect: LivekitRtc.ReconnectResponse = response.reconnect
            val servers: List<LivekitRtc.ICEServer> = rewriteAnnounced("reconnect", reconnect.iceServersList) ?: return null
            return response.toBuilder()
                .setReconnect(reconnect.toBuilder().clearIceServers().addAllIceServers(servers))
                .build()
                .toByteArray()
                .toByteString()
        }
        return bytes
    }


    /**
     * Rewrites the ICE servers of one signal and traces how many were announced against how many the tunnel can
     * reach: zero announced is the vendor's list, announced but none reachable is the tunnel. Null when none is
     * reachable.
     */
    private fun rewriteAnnounced(
        signal: String,
        announced: List<LivekitRtc.ICEServer>
    ): List<LivekitRtc.ICEServer>? {
        val servers: List<LivekitRtc.ICEServer> = rewrite(announced)
        relayUnavailable = servers.isEmpty()
        RemoteLog.trace(
            RemoteLog.ComponentCalls,
            "LIVEKIT_ICE_SERVERS",
            "signal", signal,
            "announced", announced.size,
            "announcedUrls", announced.sumOf { it.urlsCount },
            "reachable", servers.size
        )
        return if (servers.isEmpty()) null else servers
    }


    companion object {
        /** True when a new call must be relayed through the VLESS tunnel (fails closed when the core is down). */
        @JvmStatic
        fun shouldTunnel(): Boolean {
            val controller: ProxyController = ProxyController.getInstance()
            return ProxyRouting.tunnelCalls(ImpulseFeatures.VLESS, controller.isEnabled(), controller.useForCalls())
        }
    }
}
