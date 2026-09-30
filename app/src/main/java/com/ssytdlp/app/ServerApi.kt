package com.ssytdlp.app

import com.ssytdlp.app.core.ApiJson
import com.ssytdlp.app.core.Account
import com.ssytdlp.app.core.AuthProtocol
import com.ssytdlp.app.core.ServerResource
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiException(val status: Int, message: String, val payload: JsonObject? = null) : IOException(message)

internal fun shouldLogApiStatus(account: Account?, path: String): Boolean =
    account?.user?.isShared != true ||
        path != "/api/health" && path != "/api/jobs"

class ServerApi(
    private val currentAccount: () -> Account?,
    private val clearAccount: (String) -> Unit,
    client: OkHttpClient = OkHttpClient()
) {
    constructor(sessions: SessionStore) : this({ sessions.account.value }, { sessions.clear(it) })

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
                DebugLog.event(DebugEvent.API_FAILURE, error = error)
                throw error
            }
        }
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.MINUTES).build()

    val authenticatedClient: OkHttpClient = transport.newBuilder().addInterceptor { chain ->
        val account = currentAccount() ?: throw ApiException(401, "Sign in with your passkey.")
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
        val request = Request.Builder().url(url(path)).method(method,
            if (method in listOf("GET", "HEAD")) null else (body?.toString() ?: "{}").toRequestBody(JSON)).build()
        val client = if (path.substringBefore('?') == "/api/jobs/import") authenticatedClient.newBuilder()
            .readTimeout(30, TimeUnit.MINUTES).build() else authenticatedClient
        return executeJson(client.newCall(request))
    }

    suspend fun exchange(origin: String, body: JsonElement): JsonElement = executeJson(transport.newCall(
        Request.Builder().url("${AuthProtocol.normalizeOrigin(origin)}/api/auth/app/token")
            .post(body.toString().toRequestBody(JSON)).build()
    ))

    suspend fun upload(body: RequestBody): JsonElement = executeJson(authenticatedClient.newBuilder()
        .readTimeout(30, TimeUnit.MINUTES).build().newCall(
            Request.Builder().url(url("/api/jobs/import")).post(body).build()
        ))

    suspend fun download(path: String, write: (java.io.InputStream) -> Unit): Unit = suspendCancellableCoroutine { continuation ->
        val call = authenticatedClient.newCall(Request.Builder().url(url(path)).build())
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

    private suspend fun executeJson(call: Call): JsonElement = withContext(Dispatchers.IO) {
        call.awaitResponse().use { response ->
            val text = response.body?.string().orEmpty()
            val data = runCatching { ApiJson.parseToJsonElement(text) }.getOrNull()
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
    }

    companion object { val JSON = "application/json; charset=utf-8".toMediaType() }
}

private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
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
