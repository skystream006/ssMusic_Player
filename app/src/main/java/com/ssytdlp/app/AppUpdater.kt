package com.ssytdlp.app

import android.app.Application
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal const val MAX_UPDATE_BYTES = 100L * 1024 * 1024
internal const val UPDATE_ENDPOINT = "https://api.github.com/repos/skystream006/ssMusic_Player/releases/latest"
private const val RELEASE_PATH = "/skystream006/ssMusic_Player/releases/download/"
private const val MAX_METADATA_BYTES = 1024L * 1024

internal data class UpdateVersion(val major: Long, val minor: Long, val revision: Long) : Comparable<UpdateVersion> {
    override fun compareTo(other: UpdateVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.revision })

    companion object {
        fun parse(value: String): UpdateVersion? {
            if (!Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)").matches(value)) return null
            val parts = value.split('.').map { it.toLongOrNull() ?: return null }
            return UpdateVersion(parts[0], parts[1], parts[2])
        }
    }
}

internal data class UpdateRelease(val version: String, val url: HttpUrl, val bytes: Long)

internal fun trustedUpdateUrl(url: HttpUrl, initial: Boolean): Boolean {
    if (!url.isHttps || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) return false
    if (url.host == "github.com") {
        val parts = url.encodedPath.removePrefix(RELEASE_PATH).split('/')
        return url.encodedPath.startsWith(RELEASE_PATH) && parts.size == 2 &&
            parts.all { it.isNotBlank() && '%' !in it && it != "." && it != ".." } &&
            (!initial || url.query == null)
    }
    return !initial && url.host in setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com",
        "github-releases.githubusercontent.com")
}

internal fun parseUpdateRelease(body: String): UpdateRelease {
    val root = try { Json.parseToJsonElement(body) as? JsonObject } catch (_: Exception) { null }
        ?: throw IOException("GitHub returned invalid release information. Try checking again later.")
    fun text(key: String) = (root[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    if ((root["draft"] as? JsonPrimitive)?.booleanOrNull != false ||
        (root["prerelease"] as? JsonPrimitive)?.booleanOrNull != false) {
        throw IOException("No stable public release is available yet. Check again later.")
    }
    val tag = text("tag_name").orEmpty()
    val version = tag.removePrefix("v")
    if (!tag.startsWith("v") || UpdateVersion.parse(version) == null) {
        throw IOException("The latest release has an unsupported version tag. Ask the maintainer to publish v1.0.N.")
    }
    val filename = "ssMusic-Player-v$version.apk"
    val assets = (root["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    val asset = assets.filter {
        (it["name"] as? JsonPrimitive)?.contentOrNull?.endsWith(".apk", ignoreCase = true) == true
    }.singleOrNull()?.takeIf { (it["name"] as? JsonPrimitive)?.contentOrNull == filename }
        ?: throw IOException("The latest release has no universal APK ($filename). Check again after publication finishes.")
    val url = (asset["browser_download_url"] as? JsonPrimitive)?.contentOrNull?.toHttpUrlOrNull()
    if (url == null || !trustedUpdateUrl(url, initial = true) || url.encodedPath != "$RELEASE_PATH$tag/$filename") {
        throw IOException("The release APK has an untrusted download address. Contact the maintainer.")
    }
    val bytes = (asset["size"] as? JsonPrimitive)?.longOrNull ?: 0
    if (bytes !in 1..MAX_UPDATE_BYTES) throw IOException("The release APK has an invalid size. Contact the maintainer.")
    return UpdateRelease(version, url, bytes)
}

internal fun updateHttpError(code: Int): IOException = IOException(when (code) {
    404 -> "No published update was found. Check again after the first release is available."
    403, 429 -> "GitHub denied or rate-limited the request. Wait before retrying, or try another network."
    else -> "GitHub returned HTTP $code. Check your connection and try again later."
})

internal suspend fun saveUpdateApk(input: InputStream, file: File, expectedBytes: Long, progress: (Long) -> Unit) {
    try {
        input.use {
            if (expectedBytes !in 1..MAX_UPDATE_BYTES) throw IOException("The release APK has an invalid size.")
            var count = 0L
            file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read == -1) break
                    count += read
                    if (count > expectedBytes) throw IOException("The update exceeded its expected size.")
                    output.write(buffer, 0, read)
                    progress(count)
                }
            }
            if (count != expectedBytes) throw IOException("The APK download was incomplete. Try downloading again.")
        }
    } catch (e: Exception) {
        file.delete()
        throw e
    }
}

private fun updateClient(): OkHttpClient {
    // The server's network config also trusts user CAs; updates must use system roots only.
    val androidStore = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
    val systemStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
    androidStore.aliases().toList().filter { it.startsWith("system:") }.forEach { alias ->
        systemStore.setCertificateEntry(alias, androidStore.getCertificate(alias))
    }
    check(systemStore.size() > 0) { "System TLS certificates are unavailable." }
    val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(systemStore) }
        .trustManagers.filterIsInstance<X509TrustManager>().single()
    val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
    return OkHttpClient.Builder()
        .sslSocketFactory(tls.socketFactory, trust)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .build()
}

internal fun updateInstallIntent(context: Context, apk: File): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
    return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .setClipData(ClipData.newRawUri("App update", uri))
}

internal fun updatePermissionIntent(context: Context): Intent =
    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

@Suppress("DEPRECATION")
internal fun packageVersionCode(info: PackageInfo): Long =
    if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

@Suppress("DEPRECATION")
internal fun currentSigners(info: PackageInfo): Set<String> =
    (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
        .orEmpty().map { it.toCharsString() }.toSet()

internal fun validateUpdateIdentity(installed: PackageInfo, candidate: PackageInfo, packageName: String, version: String) {
    if (candidate.packageName != packageName || installed.packageName != packageName) {
        throw IOException("The downloaded APK is for another app. It has been discarded.")
    }
    if (candidate.versionName != version || packageVersionCode(candidate) <= packageVersionCode(installed)) {
        throw IOException("The APK version does not match a newer release. It has been discarded.")
    }
    val signers = currentSigners(installed)
    if (signers.isEmpty() || signers != currentSigners(candidate)) {
        throw IOException("The update signature does not match this installation. Use a release signed with the same key; do not uninstall to bypass this check.")
    }
}

data class UpdateState(
    val busy: Boolean = false,
    val automaticCheck: Boolean = false,
    val downloading: Boolean = false,
    val downloaded: Long = 0,
    val total: Long = 0,
    val availableVersion: String? = null,
    val ready: Boolean = false,
    val installRequested: Boolean = false,
    val message: String = "Check GitHub for a newer release. No sign-in required."
)

class AppUpdater(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(UpdateState())
    val state = mutableState.asStateFlow()
    private val mutableNotification = MutableStateFlow<String?>(null)
    val notification = mutableNotification.asStateFlow()
    private val directory = File(application.cacheDir, "updates")
    private val partial = File(directory, "update.part")
    private val apk = File(directory, "update.apk")
    private val client by lazy { updateClient() }
    private var release: UpdateRelease? = null
    private var operation: Job? = null
    @Volatile private var activeCall: Call? = null
    private var awaitingPermission = false
    private var startupCheckAttempted = false
    private val cleanup = viewModelScope.launch(Dispatchers.IO) {
        // No persisted trust/permission state: after process death, check and download afresh.
        directory.deleteRecursively()
    }

    private suspend fun request(url: HttpUrl): Response {
        currentCoroutineContext().ensureActive()
        val call = client.newCall(Request.Builder().url(url).header("Accept",
            if (url.host == "api.github.com") "application/vnd.github+json" else "application/octet-stream")
            .header("User-Agent", "ssMusic-Player/${BuildConfig.VERSION_NAME}").build())
        activeCall = call
        currentCoroutineContext().ensureActive()
        return call.execute()
    }

    fun check(automatic: Boolean = false) {
        if (automatic && startupCheckAttempted) return
        if (mutableState.value.busy) return
        startupCheckAttempted = true
        awaitingPermission = false
        release = null
        mutableNotification.value = null
        mutableState.value = UpdateState(busy = true, automaticCheck = automatic,
            message = if (automatic) UpdateState().message else "Checking GitHub…")
        DebugLog.event(DebugEvent.UPDATE_CHECK_STARTED)
        operation = viewModelScope.launch {
            try {
                val latest = withContext(Dispatchers.IO) {
                    cleanup.join()
                    discardFiles()
                    request(UPDATE_ENDPOINT.toHttpUrlOrNull()!!).use { response ->
                        if (!response.isSuccessful) throw updateHttpError(response.code)
                        val body = response.body ?: throw IOException("GitHub returned an empty response. Try again.")
                        if (body.contentLength() > MAX_METADATA_BYTES) throw IOException("Release information is too large.")
                        val source = body.source()
                        source.request(MAX_METADATA_BYTES + 1)
                        if (source.buffer.size > MAX_METADATA_BYTES) throw IOException("Release information is too large.")
                        parseUpdateRelease(source.readUtf8())
                    }
                }
                val installed = UpdateVersion.parse(BuildConfig.VERSION_NAME)
                    ?: throw IOException("This installed build has an unsupported version. Install an official release.")
                if (UpdateVersion.parse(latest.version)!! > installed) {
                    release = latest
                    mutableState.value = UpdateState(busy = true, automaticCheck = automatic,
                        availableVersion = latest.version, total = latest.bytes,
                        message = "A new release is available.")
                    if (automatic) mutableNotification.value = latest.version
                } else mutableState.value = UpdateState(busy = true, automaticCheck = automatic,
                    message = if (automatic) UpdateState().message else "You're up to date. Latest release: ${latest.version}.")
                DebugLog.event(DebugEvent.UPDATE_CHECK_COMPLETED)
            } catch (e: CancellationException) {
                mutableState.value = UpdateState(busy = true, automaticCheck = automatic,
                    message = if (automatic) UpdateState().message else "Update check cancelled.")
                throw e
            } catch (e: Exception) {
                DebugLog.event(DebugEvent.UPDATE_FAILURE)
                mutableState.value = UpdateState(busy = true, automaticCheck = automatic,
                    message = if (automatic) UpdateState().message else failureMessage(e))
            } finally {
                activeCall = null
                withContext(NonCancellable + Dispatchers.IO) { discardFiles() }
                mutableState.update { it.copy(busy = false, automaticCheck = false) }
            }
        }
    }

    fun download() {
        val latest = release ?: return
        if (mutableState.value.busy) return
        awaitingPermission = false
        mutableState.update { it.copy(busy = true, downloading = true, ready = false,
            installRequested = false, downloaded = 0, message = "Downloading update…") }
        DebugLog.event(DebugEvent.UPDATE_DOWNLOAD_STARTED)
        operation = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    cleanup.join()
                    discardFiles()
                    try {
                        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Unable to create update cache. Free device storage and retry.")
                        downloadFile(latest)
                        validateApk(partial, latest.version)
                        currentCoroutineContext().ensureActive()
                        if (!partial.renameTo(apk)) throw IOException("Unable to save the update. Free device storage and retry.")
                    } catch (e: Exception) {
                        discardFiles()
                        throw e
                    }
                }
                mutableState.update { it.copy(ready = true, installRequested = true,
                    message = "Update verified. Android will ask you to confirm installation.") }
                DebugLog.event(DebugEvent.UPDATE_DOWNLOAD_COMPLETED)
            } catch (e: CancellationException) {
                mutableState.update { it.copy(ready = false, installRequested = false, message = "Download cancelled.") }
                throw e
            } catch (e: Exception) {
                DebugLog.event(DebugEvent.UPDATE_FAILURE)
                mutableState.update { it.copy(ready = false, installRequested = false, message = failureMessage(e)) }
            } finally {
                activeCall = null
                if (!mutableState.value.ready) withContext(NonCancellable + Dispatchers.IO) { discardFiles() }
                mutableState.update { it.copy(busy = false, downloading = false) }
            }
        }
    }

    private suspend fun downloadFile(latest: UpdateRelease) {
        var url = latest.url
        repeat(6) { hop ->
            if (!trustedUpdateUrl(url, initial = hop == 0)) throw IOException("Blocked an untrusted update redirect.")
            val redirect = request(url).use { response ->
                if (response.code in listOf(301, 302, 303, 307, 308)) {
                    if (hop == 5) throw IOException("Too many update redirects. Try again later.")
                    return@use response.header("Location")?.let { url.resolve(it) }
                        ?: throw IOException("GitHub returned an invalid download redirect.")
                }
                if (!response.isSuccessful) throw updateHttpError(response.code)
                val body = response.body ?: throw IOException("The download was empty. Try again.")
                val length = body.contentLength()
                if (length > MAX_UPDATE_BYTES || (length >= 0 && length != latest.bytes)) {
                    throw IOException("The APK size differs from the release. Check for updates again.")
                }
                saveUpdateApk(body.byteStream(), partial, latest.bytes) { count ->
                    mutableState.update { it.copy(downloaded = count) }
                }
                null
            }
            if (redirect == null) return
            url = redirect
        }
    }

    @Suppress("DEPRECATION")
    private fun validateApk(file: File, expectedVersion: String) {
        val context = getApplication<Application>()
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = pm.getPackageInfo(context.packageName, flags)
        val candidate = pm.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw IOException("The downloaded file is not a valid APK. Check for updates and retry.")
        validateUpdateIdentity(installed, candidate, context.packageName, expectedVersion)
    }

    fun cancel() {
        operation?.cancel()
        activeCall?.cancel()
    }

    fun requestInstall() {
        if (mutableState.value.ready && !mutableState.value.busy) mutableState.update { it.copy(installRequested = true) }
    }

    fun consumeInstallRequest() {
        mutableState.update { it.copy(installRequested = false) }
    }

    fun consumeNotification() { mutableNotification.value = null }

    fun needsInstallPermission(): Boolean = !getApplication<Application>().packageManager.canRequestPackageInstalls()

    fun beginPermissionRequest() {
        awaitingPermission = true
        DebugLog.event(DebugEvent.UPDATE_PERMISSION_REQUIRED)
    }

    fun permissionReturned() {
        if (!awaitingPermission) return
        awaitingPermission = false
        if (!needsInstallPermission()) requestInstall()
        else mutableState.update { it.copy(message = "Install permission was not granted. Tap Install update to retry when ready.") }
    }

    suspend fun installerIntent(): Intent? {
        val latest = release ?: return null
        return try {
            withContext(Dispatchers.IO) {
                validateApk(apk, latest.version)
                updateInstallIntent(getApplication(), apk).also {
                    DebugLog.event(DebugEvent.UPDATE_INSTALL_REQUESTED)
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            DebugLog.event(DebugEvent.UPDATE_FAILURE)
            withContext(Dispatchers.IO) { discardFiles() }
            mutableState.update { it.copy(ready = false, installRequested = false, message = failureMessage(e)) }
            null
        }
    }

    fun installLaunchFailed() {
        awaitingPermission = false
        DebugLog.event(DebugEvent.UPDATE_FAILURE)
        mutableState.update { it.copy(message = "Unable to open Android's installer or install settings. Check device restrictions and try again.") }
    }

    private fun discardFiles() { partial.delete(); apk.delete() }

    private fun failureMessage(error: Exception): String =
        if (error is IOException && error.javaClass == IOException::class.java) error.message ?: "Update failed. Try again."
        else "Unable to reach GitHub or save the update. Check your connection, device storage and date/time, then retry."

    override fun onCleared() {
        activeCall?.cancel()
        super.onCleared()
    }
}
