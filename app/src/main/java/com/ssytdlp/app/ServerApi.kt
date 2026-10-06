package com.ssytdlp.app

import com.ssytdlp.app.core.ApiJson
import com.ssytdlp.app.core.Account
import com.ssytdlp.app.core.AuthProtocol
import com.ssytdlp.app.core.ServerResource
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.buffer
import okio.source
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiException(val status: Int, message: String, val payload: JsonObject? = null) : IOException(message)

internal fun shouldLogApiStatus(account: Account?, path: String): Boolean =
    account?.user?.isShared != true ||
        path != "/api/health" && path != "/api/jobs"

internal fun isBackgroundPlaybackRequest(method: String, path: String): Boolean =
    method in listOf("GET", "HEAD") &&
        (Regex("/api/jobs/[^/]+/(lyrics|stream|download)/[^/]+").matches(path) ||
            Regex("/api/stream/[^/]+").matches(path))

class ServerApi(
    private val currentAccount: () -> Account?,
    private val clearAccount: (String) -> Unit,
    client: OkHttpClient = OkHttpClient(),
    private val uiActivity: UiActivityGate? = null
) {
    private class RequestOwner(val account: Account)

    constructor(sessions: SessionStore, uiActivity: UiActivityGate? = null) :
        this({ sessions.account.value }, { sessions.clear(it) }, uiActivity = uiActivity)

    private val transport = client.newBuilder()
        .addInterceptor { chain ->
            val account = currentAccount()
            val path = chain.request().url.encodedPath
            try {
                chain.proceed(chain.request()).also {
                    if (shouldLogApiStatus(account, path)) {
                        DebugLog.event(DebugEvent.API_STATUS, status = it.code)
                    }
                }
            } catch (error: IOException) {
                if (!chain.call().isCanceled()) DebugLog.event(DebugEvent.API_FAILURE, error = error)
                throw error
            }
        }
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.MINUTES).build()

    val authenticatedClient: OkHttpClient = transport.newBuilder().addInterceptor { chain ->
        val account = currentAccount() ?: throw ApiException(401, "Sign in with your passkey.")
        chain.request().tag(RequestOwner::class.java)?.let { requireOwner(it.account, account) }
        if (!Instant.parse(account.session.expiresAt).isAfter(Instant.now())) {
            clearAccount(account.session.token)
            throw ApiException(401, "Your session expired. Sign in again.")
        }
        try { ServerResource.resolve(account.origin, chain.request().url.toString()) }
        catch (_: IllegalArgumentException) { throw IOException("Refused a request outside the selected server.") }
        val response = chain.proceed(chain.request().newBuilder()
            .header("Authorization", "Bearer ${account.session.token}").header("User-Agent", "ssMusicPlayer/1.0 Android").build())
        if (response.code == 401) clearAccount(account.session.token)
        response
    }.build()

    fun url(path: String): String = ServerResource.resolve(
        currentAccount()?.origin ?: throw ApiException(401, "Sign in with your passkey."), path
    )

    suspend fun request(path: String, method: String = "GET", body: JsonElement? = null): JsonElement {
        val owner = currentAccount() ?: throw ApiException(401, "Sign in with your passkey.")
        val request = Request.Builder().url(ServerResource.resolve(owner.origin, path)).tag(RequestOwner::class.java, RequestOwner(owner)).method(method,
            if (method in listOf("GET", "HEAD")) null else (body?.toString() ?: "{}").toRequestBody(JSON)).build()
        val client = if (path.substringBefore('?') == "/api/jobs/import") authenticatedClient.newBuilder()
            .readTimeout(30, TimeUnit.MINUTES).build() else authenticatedClient
        suspend fun execute(): JsonElement {
            requireOwner(owner)
            return executeJson(client.newCall(request)).also { requireOwner(owner) }
        }
        if (uiActivity == null || isBackgroundPlaybackRequest(method, request.url.encodedPath)) return execute()
        if (method in listOf("GET", "HEAD")) return uiActivity.readWhileResumed { execute() }
        // A mutation may have reached the server already. Never replay it on a lifecycle transition.
        return mutationWhileResumed { execute() }
    }

    suspend fun exchange(origin: String, body: JsonElement): JsonElement = mutationWhileResumed(
        "Sign-in stopped when the app went into the background. The server may have completed it; start a new sign-in."
    ) {
        executeJson(transport.newCall(
            Request.Builder().url("${AuthProtocol.normalizeOrigin(origin)}/api/auth/app/token")
                .post(body.toString().toRequestBody(JSON)).build()
        ))
    }

    suspend fun upload(body: RequestBody, path: String = "/api/jobs/import"): JsonElement {
        val owner = currentAccount() ?: throw ApiException(401, "Sign in with your passkey.")
        return mutationWhileResumed {
            requireOwner(owner)
            executeJson(authenticatedClient.newBuilder().readTimeout(30, TimeUnit.MINUTES).build().newCall(
                Request.Builder().url(ServerResource.resolve(owner.origin, path))
                    .tag(RequestOwner::class.java, RequestOwner(owner)).post(body).build()
            )).also { requireOwner(owner) }
        }
    }

    private suspend fun <T> mutationWhileResumed(
        interruptedMessage: String = "Request stopped when the app went into the background. Server changes may have completed; refresh before retrying.",
        block: suspend () -> T
    ): T {
        if (uiActivity == null) return block()
        try {
            return uiActivity.onceWhileResumed(block)
        } catch (error: UiActivityGate.UiPaused) {
            currentCoroutineContext().ensureActive()
            throw IOException(interruptedMessage, error)
        }
    }

    suspend fun download(path: String, write: (java.io.InputStream) -> Unit) {
        val owner = currentAccount() ?: throw ApiException(401, "Sign in with your passkey.")
        val request = Request.Builder().url(ServerResource.resolve(owner.origin, path)).tag(RequestOwner::class.java, RequestOwner(owner)).build()
        suspend fun execute() {
            requireOwner(owner)
            download(authenticatedClient.newCall(request), write)
            requireOwner(owner)
        }
        if (uiActivity == null || isBackgroundPlaybackRequest("GET", request.url.encodedPath)) execute()
        else uiActivity.onceWhileResumed { execute() }
    }

    suspend fun artwork(path: String): ByteArray {
        val owner = currentAccount() ?: throw ApiException(401, "Sign in with your passkey.")
        val request = Request.Builder().url(ServerResource.resolve(owner.origin, path))
            .tag(RequestOwner::class.java, RequestOwner(owner)).build()
        suspend fun execute(): ByteArray {
            requireOwner(owner)
            var bytes = ByteArray(0)
            download(authenticatedClient.newCall(request)) { input ->
                input.source().buffer().use { source ->
                    source.request(64 * 1024L + 1)
                    if (source.buffer.size > 64 * 1024) throw IOException("Artwork thumbnail is too large.")
                    bytes = source.readByteArray()
                }
            }
            requireOwner(owner)
            return bytes
        }
        return if (uiActivity == null) execute() else uiActivity.readWhileResumed { execute() }
    }

    private suspend fun download(call: Call, write: (java.io.InputStream) -> Unit): Unit = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (!response.isSuccessful) throw ApiException(response.code, "Download failed (${response.code}).")
                        val body = response.body ?: throw IOException("Empty download response.")
                        if (continuation.isActive) body.byteStream().use(write)
                    }
                    if (continuation.isActive) continuation.resume(Unit)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    private fun requireOwner(expected: Account, account: Account? = currentAccount()) {
        if (account == null || account.origin != expected.origin || account.user.id != expected.user.id ||
            account.session.token != expected.session.token) {
            throw IOException("The account changed. Retry from the current account.")
        }
    }

    private suspend fun executeJson(call: Call): JsonElement = suspendCancellableCoroutine { continuation ->
        val measureMetadata = call.request().method == "GET" &&
            Regex("/api/jobs/[^/]+/lyrics/[^/]+").matches(call.request().url.encodedPath)
        val started = System.nanoTime()
        if (measureMetadata) DebugLog.event(DebugEvent.METADATA_REQUESTED)
        // Keep cancellation attached through body parsing, not only until response headers arrive.
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        val text = response.body?.string().orEmpty()
                        if (measureMetadata) DebugLog.timing(DebugEvent.METADATA_DOWNLOAD, started)
                        val parseStarted = System.nanoTime()
                        val data = runCatching { ApiJson.parseToJsonElement(text) }.getOrNull()
                        if (measureMetadata) DebugLog.timing(DebugEvent.METADATA_JSON, parseStarted)
                        if (!response.isSuccessful) {
                            val payload = data as? JsonObject
                            throw ApiException(response.code, payload?.get("error")?.jsonPrimitive?.content
                                ?: "Server request failed (${response.code}).", payload)
                        }
                        if (text.isBlank()) JsonNull else data ?: run {
                            DebugLog.event(DebugEvent.API_INVALID_RESPONSE, status = response.code)
                            throw IOException("The server returned an invalid response.")
                        }
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    companion object { val JSON = "application/json; charset=utf-8".toMediaType() }
}

internal suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}
