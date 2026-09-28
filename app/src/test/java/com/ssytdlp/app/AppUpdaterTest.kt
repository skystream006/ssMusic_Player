package com.ssytdlp.app

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppUpdaterTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val download = "https://github.com/skystream006/ssMusic_Player/releases/download/v1.0.12/ssMusic-Player-v1.0.12.apk"

    private fun release(
        tag: String = "v1.0.12",
        draft: Boolean = false,
        prerelease: Boolean = false,
        name: String = "ssMusic-Player-v1.0.12.apk",
        url: String = download,
        size: Long = 1234
    ) = """{"tag_name":"$tag","draft":$draft,"prerelease":$prerelease,"assets":[{"name":"$name","browser_download_url":"$url","size":$size}]}"""

    @Test fun versionsCompareNumerically() {
        assertTrue(UpdateVersion.parse("1.0.12")!! > UpdateVersion.parse("1.0.9")!!)
        assertTrue(UpdateVersion.parse("2.0.0")!! > UpdateVersion.parse("1.99.999")!!)
        assertEquals(0, UpdateVersion.parse("1.0.12")!!.compareTo(UpdateVersion.parse("1.0.12")!!))
        listOf("", "v1.0.12", "1.0", "1.0.12-beta", "1.0.-1", "1.0.01", "1.0.999999999999999999999999").forEach {
            assertNull(it, UpdateVersion.parse(it))
        }
    }

    @Test fun stableReleaseSelectsExpectedUniversalApk() {
        val parsed = parseUpdateRelease(release())
        assertEquals("1.0.12", parsed.version)
        assertEquals(download, parsed.url.toString())
        assertEquals(1234L, parsed.bytes)
        assertEquals(100L * 1024 * 1024, MAX_UPDATE_BYTES)
        assertEquals(MAX_UPDATE_BYTES, parseUpdateRelease(release(size = MAX_UPDATE_BYTES)).bytes)
    }

    @Test fun rejectsReleasesWithMoreThanOneApk() {
        val multiple = release().replace("}]}", """},{"name":"arm64.apk"}]}""")
        assertThrows(IOException::class.java) { parseUpdateRelease(multiple) }
    }

    @Test fun rejectsUnpublishedMalformedAndMissingAssets() {
        listOf(
            release(draft = true), release(prerelease = true), release(tag = "latest"),
            release(tag = "1.0.12"), release(tag = "v1.0.12-beta"),
            release(name = "arm64.apk"), release(size = 0), release(size = MAX_UPDATE_BYTES + 1),
            release(url = "https://example.com/update.apk"),
            release(url = download.replace("/v1.0.12/", "/v1.0.13/")),
            release(url = download.replace("ssMusic-Player-v1.0.12.apk", "other.apk")),
            """{"tag_name":"v1.0.12","draft":false,"prerelease":false,"assets":[]}""",
            "{}", "[]", "not json"
        ).forEach { body -> assertThrows(body, IOException::class.java) { parseUpdateRelease(body) } }
    }

    @Test fun initialUrlIsRestrictedToThisRepository() {
        assertTrue(trustedUpdateUrl(download.toHttpUrl(), initial = true))
        listOf(
            download.replace("https:", "http:"),
            download.replace("github.com", "github.com.evil.example"),
            download.replace("github.com", "username@github.com"),
            download.replace("github.com", "github.com:444"),
            download.replace("skystream006", "someone-else"),
            download.replace("/releases/download/", "/blob/"),
            "$download?token=anything", "$download#fragment",
            download.replace("v1.0.12.apk", "v1.0.12%2Fother.apk"),
            "https://release-assets.githubusercontent.com/signed.apk"
        ).forEach { assertFalse(it, trustedUpdateUrl(it.toHttpUrl(), initial = true)) }
    }

    @Test fun redirectsOnlyAllowExactHttpsGithubAssetHosts() {
        listOf("release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com").forEach {
            assertTrue(trustedUpdateUrl("https://$it/assets/file.apk?signature=value".toHttpUrl(), initial = false))
        }
        listOf(
            "http://release-assets.githubusercontent.com/file.apk",
            "https://release-assets.githubusercontent.com.evil.example/file.apk",
            "https://evil.githubusercontent.com/file.apk",
            "https://github.com/login",
            "https://username@objects.githubusercontent.com/file.apk",
            "https://objects.githubusercontent.com:444/file.apk",
            "https://example.com/file.apk"
        ).forEach { assertFalse(it, trustedUpdateUrl(it.toHttpUrl(), initial = false)) }
    }

    @Test fun errorsOfferUsefulNextSteps() {
        assertTrue(updateHttpError(404).message!!.contains("first release"))
        assertTrue(updateHttpError(403).message!!.contains("Wait"))
        assertTrue(updateHttpError(429).message!!.contains("rate-limited"))
        assertTrue(updateHttpError(500).message!!.contains("try again"))
    }

    @Test fun streamedDownloadReportsProgressAndRetainsOnlyCompleteBytes() = runBlocking {
        val file = File(context.cacheDir, "update-test.part")
        val bytes = ByteArray(150_000) { (it % 127).toByte() }
        val progress = mutableListOf<Long>()
        try {
            saveUpdateApk(ByteArrayInputStream(bytes), file, bytes.size.toLong()) { progress.add(it) }
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(bytes.size.toLong(), progress.last())
            assertTrue(progress.zipWithNext().all { (a, b) -> b > a })
        } finally { file.delete() }
    }

    @Test fun truncatedOrOversizedDownloadDeletesPartialFile() = runBlocking {
        val file = File(context.cacheDir, "update-test.part")
        try {
            listOf(50L, 200L, MAX_UPDATE_BYTES + 1).forEach { expected ->
                val failure = runCatching {
                    saveUpdateApk(ByteArrayInputStream(ByteArray(100)), file, expected) {}
                }.exceptionOrNull()
                assertTrue(failure is IOException)
                assertFalse(file.exists())
            }
        } finally { file.delete() }
    }

    @Test fun cancellingStreamClosesInputAndDeletesPartialFile() = runBlocking {
        val file = File(context.cacheDir, "update-test.part")
        var closed = false
        val input = object : ByteArrayInputStream(ByteArray(150_000)) {
            override fun close() { closed = true; super.close() }
        }
        try {
            val job = launch {
                saveUpdateApk(input, file, 150_000) { cancel() }
            }
            job.join()
            assertTrue(job.isCancelled)
            assertTrue(closed)
            assertFalse(file.exists())
        } finally { file.delete() }
    }

    @Test fun unknownSourcesReturnResumesExactlyOnceAndDenialDoesNotLoop() {
        val store = ViewModelStore()
        val provider = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(context))
        val model = provider[AppUpdater::class.java]
        val state = ReflectionHelpers.getField<MutableStateFlow<UpdateState>>(model, "mutableState")
        state.value = UpdateState(ready = true)
        try {
            shadowOf(context.packageManager).setCanRequestPackageInstalls(false)
            assertTrue(model.needsInstallPermission())
            model.beginPermissionRequest()
            model.permissionReturned()
            assertFalse(model.state.value.installRequested)
            assertTrue(model.state.value.message.contains("not granted"))
            shadowOf(context.packageManager).setCanRequestPackageInstalls(true)
            assertFalse(model.needsInstallPermission())
            model.permissionReturned()
            assertFalse(model.state.value.installRequested)

            model.beginPermissionRequest()
            // Rotation retrieves the same ViewModel and retains the pending permission handoff.
            val rotated = provider[AppUpdater::class.java]
            assertSame(model, rotated)
            rotated.permissionReturned()
            assertTrue(model.state.value.installRequested)
            model.consumeInstallRequest()
            model.permissionReturned()
            assertFalse(model.state.value.installRequested)
        } finally { store.clear() }
    }

    @Test fun recreatedProcessDoesNotResumeUnvalidatedOrMissingDownload() {
        val store = ViewModelStore()
        val model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(context))[AppUpdater::class.java]
        try {
            shadowOf(context.packageManager).setCanRequestPackageInstalls(true)
            model.permissionReturned()
            model.requestInstall()
            assertFalse(model.state.value.ready)
            assertFalse(model.state.value.installRequested)
        } finally { store.clear() }
    }

    @Suppress("DEPRECATION")
    @Test @Config(sdk = [26])
    fun apkMustHaveOwnPackageHigherCodeExpectedVersionAndIdenticalSigner() {
        fun info(name: String = context.packageName, code: Int = 13, version: String = "1.0.12", signer: String? = "abcd") =
            PackageInfo().apply {
                packageName = name
                versionCode = code
                versionName = version
                signatures = signer?.let { arrayOf(Signature(it)) }
            }
        val installed = info(code = 10, version = "1.0.9")
        validateUpdateIdentity(installed, info(), context.packageName, "1.0.12")
        listOf(
            info(name = "another.app"), info(code = 10), info(code = 9),
            info(version = "1.0.13"), info(signer = "1234"), info(signer = null)
        ).forEach { candidate ->
            assertThrows(IOException::class.java) {
                validateUpdateIdentity(installed, candidate, context.packageName, "1.0.12")
            }
        }
        assertThrows(IOException::class.java) {
            validateUpdateIdentity(info(code = 10, signer = null), info(signer = null), context.packageName, "1.0.12")
        }
    }

    @Test fun modernPackageWithoutSigningInfoIsNotTrusted() {
        val info = PackageInfo().apply { setLongVersionCode(Int.MAX_VALUE.toLong() + 10) }
        assertEquals(Int.MAX_VALUE.toLong() + 10, packageVersionCode(info))
        assertTrue(currentSigners(info).isEmpty())
    }

    @Test fun modernPackagesRequireIdenticalCurrentSignersNotJustSigningHistory() {
        fun info(code: Long, vararg signers: String): PackageInfo = PackageInfo().apply {
            packageName = context.packageName
            versionName = "1.0.12"
            setLongVersionCode(code)
            signingInfo = Shadow.newInstanceOf(SigningInfo::class.java).also {
                shadowOf(it).setSignatures(signers.map(::Signature).toTypedArray())
                shadowOf(it).setPastSigningCertificates(arrayOf(Signature("abcd")))
            }
        }
        val installed = info(10, "abcd")
        validateUpdateIdentity(installed, info(13, "abcd"), context.packageName, "1.0.12")
        assertThrows(IOException::class.java) {
            validateUpdateIdentity(installed, info(13, "1234"), context.packageName, "1.0.12")
        }
        validateUpdateIdentity(info(10, "abcd", "1234"), info(13, "1234", "abcd"), context.packageName, "1.0.12")
        assertThrows(IOException::class.java) {
            validateUpdateIdentity(info(10, "abcd", "1234"), info(13, "abcd"), context.packageName, "1.0.12")
        }
    }

    @Test fun installerHasReadGrantClipDataAndOnlyPrivateUpdateFiles() {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val apk = File(directory, "test.apk").apply { writeText("test") }
        try {
            val intent = updateInstallIntent(context, apk)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("application/vnd.android.package-archive", intent.type)
            assertEquals("content", intent.data!!.scheme)
            assertEquals("${context.packageName}.updates", intent.data!!.authority)
            assertEquals(intent.data, intent.clipData!!.getItemAt(0).uri)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            context.contentResolver.openInputStream(intent.data!!)!!.use {
                assertEquals("test", it.bufferedReader().readText())
            }
            assertThrows(IllegalArgumentException::class.java) {
                FileProvider.getUriForFile(context, "${context.packageName}.updates", File(context.cacheDir, "private-session"))
            }
            assertThrows(IllegalArgumentException::class.java) {
                FileProvider.getUriForFile(context, "${context.packageName}.updates", File(context.filesDir, "private-session"))
            }
        } finally { apk.delete() }
    }

    @Suppress("DEPRECATION")
    @Test fun manifestProviderIsPrivateAndPermissionSettingsAreScoped() {
        val provider = context.packageManager.resolveContentProvider("${context.packageName}.updates", PackageManager.GET_META_DATA)!!
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
        assertEquals("androidx.core.content.FileProvider", provider.name)
        assertTrue(provider.metaData.containsKey("android.support.FILE_PROVIDER_PATHS"))
        val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertTrue(Manifest.permission.REQUEST_INSTALL_PACKAGES in permissions)
        assertFalse(Manifest.permission.WRITE_EXTERNAL_STORAGE in permissions)
        assertFalse(Manifest.permission.MANAGE_EXTERNAL_STORAGE in permissions)
        val intent = updatePermissionIntent(context)
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intent.action)
        assertEquals("package:${context.packageName}", intent.data.toString())
    }
}
