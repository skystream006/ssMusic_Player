package com.ssytdlp.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.ssytdlp.app.core.Account
import com.ssytdlp.app.core.ServerResource
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer

@Serializable
internal data class LanPlaybackConfig(
    val primaryOrigin: String,
    val primaryUserId: String,
    val origin: String,
    val account: Account? = null
) {
    fun belongsTo(primary: Account?) =
        primary?.origin == primaryOrigin && primary.user.id == primaryUserId
}

internal class WifiNetwork(context: Context) {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    fun current(): Long? {
        val network = connectivity.activeNetwork ?: return null
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return null
        return network.networkHandle.takeIf {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }
}

internal class LanPlaybackRouter(
    private val currentAccount: () -> Account?,
    private val configuration: () -> LanPlaybackConfig?,
    private val wifiNetwork: () -> Long?,
    private val clearSession: (String) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime
) : Interceptor {
    private data class Route(val primary: Account, val config: LanPlaybackConfig, val network: Long)
    private data class Failure(val route: Route, val at: Long)
    @Volatile private var failure: Failure? = null

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // Only media reads use the alternate session; library reads and mutations never do.
        if (request.method !in listOf("GET", "HEAD") ||
            !Regex("/api/(stream/[^/]+|jobs/[^/]+/(stream|download|video)/[^/]+)")
                .matches(request.url.encodedPath)) return chain.proceed(request)
        val primary = request.tag(ServerApi.RequestOwner::class.java)?.account
            ?: throw IOException("Missing playback account.")
        requirePrimary(primary)
        val config = configuration()?.takeIf { it.belongsTo(primary) } ?: return chain.proceed(request)
        val lan = config.account?.takeIf { it.origin == config.origin } ?: return chain.proceed(request)
        if (!Instant.parse(lan.session.expiresAt).isAfter(Instant.now())) {
            clearSession(lan.session.token)
            return chain.proceed(request)
        }
        val network = wifiNetwork() ?: return chain.proceed(request)
        val route = Route(primary, config, network)
        failure?.let {
            if (it.route == route && nanoTime() - it.at < TimeUnit.SECONDS.toNanos(30)) {
                return chain.proceed(request)
            }
        }
        val path = request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: "")
        val alternate = request.newBuilder().url(ServerResource.resolve(lan.origin, path))
            .removeHeader("Host").removeHeader("Cookie")
            .header("Authorization", "Bearer ${lan.session.token}").build()
        try {
            requireCurrent(route)
            val response = chain.withConnectTimeout(1500, TimeUnit.MILLISECONDS)
                .withReadTimeout(3, TimeUnit.SECONDS).proceed(alternate)
            if (response.isSuccessful) {
                try { requireCurrent(route) }
                catch (error: IOException) { response.close(); throw error }
                DebugLog.event(DebugEvent.LAN_PLAYBACK_SELECTED)
                // Keep Media3's URI and persistent cache identity on the primary origin.
                return response.newBuilder().request(request)
                    .body(response.body?.let { watchBody(it, route, chain) }).build()
            }
            response.close()
            if (response.code == 401) clearSession(lan.session.token)
            failed(route, status = response.code)
        } catch (error: IOException) {
            if (chain.call().isCanceled()) throw error
            failed(route, error = error)
        }
        requirePrimary(primary)
        if (chain.call().isCanceled()) throw IOException("Playback request cancelled.")
        return chain.proceed(request)
    }

    private fun watchBody(body: ResponseBody, route: Route, chain: Interceptor.Chain): ResponseBody {
        val source = object : ForwardingSource(body.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                try {
                    requireCurrent(route)
                    return super.read(sink, byteCount)
                } catch (error: IOException) {
                    // Media3 resumes failed loads with a Range request. Route that retry to
                    // the primary server rather than repeatedly retrying a broken LAN stream.
                    if (!chain.call().isCanceled()) failed(route, error = error)
                    throw error
                }
            }
        }.buffer()
        return object : ResponseBody() {
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun source(): BufferedSource = source
        }
    }

    private fun requireCurrent(route: Route) {
        requirePrimary(route.primary)
        if (configuration() != route.config || wifiNetwork() != route.network) {
            throw IOException("The LAN playback connection changed.")
        }
    }

    private fun requirePrimary(expected: Account) {
        val current = currentAccount()
        if (current?.origin != expected.origin || current.user.id != expected.user.id ||
            current.session.token != expected.session.token) throw IOException("The playback account changed.")
    }

    private fun failed(route: Route, status: Int? = null, error: IOException? = null) {
        failure = Failure(route, nanoTime())
        DebugLog.event(DebugEvent.LAN_PLAYBACK_FALLBACK, status = status, error = error)
    }
}
