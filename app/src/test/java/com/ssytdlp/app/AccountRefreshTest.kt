package com.ssytdlp.app

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.ssytdlp.app.core.Account
import com.ssytdlp.app.core.ApiJson
import com.ssytdlp.app.core.Session
import com.ssytdlp.app.core.Track
import com.ssytdlp.app.core.User
import com.ssytdlp.app.core.UserResponse
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.Security
import java.security.cert.Certificate
import java.util.Collections
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = MusicApplication::class)
class AccountRefreshTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val models = ViewModelStore()
    private lateinit var context: Context
    private lateinit var application: MusicApplication
    private lateinit var server: MockWebServer
    private lateinit var account: Account
    private val sessions get() = application.sessions

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("private_session", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("playback", Context.MODE_PRIVATE).edit().clear().commit()
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        application = context as MusicApplication
        ReflectionHelpers.setField(application, "api", ServerApi({ sessions.account.value }, { sessions.clear(it) },
            OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()))
        account = Account(server.url("/").newBuilder().host("localhost").build().toString().removeSuffix("/"), User("listener", "Listener"),
            Session("T".repeat(43), "2099-01-01T00:00:00Z"))
    }

    @After fun teardown() {
        compose.runOnUiThread { models.clear() }
        server.shutdown()
        context.getSharedPreferences("private_session", Context.MODE_PRIVATE).edit().clear().commit()
    }

    companion object {
        private val provider = object : Provider("AccountRefreshKeyStore", 1.0, "In-memory session test key") {}

        @JvmStatic @BeforeClass fun installKeyStore() {
            provider.put("KeyStore.AndroidKeyStore", AccountRefreshKeyStore::class.java.name)
            Security.addProvider(provider)
        }

        @JvmStatic @AfterClass fun removeKeyStore() {
            Security.removeProvider(provider.name)
        }
    }

    @Test fun startupConsumesCurrentUserWithoutRestartingLibraryInitialization() {
        sessions.save(account)
        val shared = account.user.copy(role = "shared", sharedUserIds = listOf("owner"))
        enqueueUser(shared)
        server.enqueue(MockResponse().setBody("""{"theme":"midnight"}"""))
        server.enqueue(MockResponse().setBody("""{"songCount":1}"""))
        server.enqueue(MockResponse().setBody("""{"files":[{"jobId":"job","name":"song.mp3"}]}"""))
        val model = createModel(collectSession = true, connectPlayback = false)
        compose.waitUntil(timeoutMillis = 10_000) { model.library.tracks.files.size == 1 }
        assertEquals(shared, sessions.account.value!!.user)
        assertEquals(shared, SessionStore(context).account.value!!.user)
        assertEquals("midnight", model.preferences.theme)
        assertEquals(1, model.library.library.songCount)
        assertEquals(4, server.requestCount)
        assertEquals("/api/auth/me", takePath())
        assertEquals("/api/preferences", takePath())
        assertEquals("/api/library", takePath())
        assertTrue(takePath().startsWith("/api/library/tracks?"))
    }

    @Test fun settingsRefreshRoleAndOnlyPollPermittedEndpoints() = runBlocking {
        val model = createModel()
        sessions.save(account)
        enqueueUser(account.user.copy(role = "admin"))
        server.enqueue(MockResponse().setBody("""{"running":false}"""))
        server.enqueue(MockResponse().setBody("""{"media":{"totalFiles":1}}"""))
        model.pollSettings()
        assertNotNull(model.backup)
        assertNotNull(model.health)
        assertEquals(listOf("/api/auth/me", "/api/library/backup", "/api/health"), List(3) { takePath() })

        val shared = account.user.copy(role = "shared", sharedUserIds = listOf("owner"))
        enqueueUser(shared)
        model.pollSettings()
        assertEquals(shared, sessions.account.value!!.user)
        assertEquals("/api/auth/me", takePath())
        assertEquals(4, server.requestCount)
        assertNull(model.backup)
        assertNull(model.health)
        assertNull(model.notice)

        enqueueUser(account.user)
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setBody("{}"))
        model.pollSettings()
        assertEquals(account.user, sessions.account.value!!.user)
        assertEquals(listOf("/api/auth/me", "/api/library/backup", "/api/health"), List(3) { takePath() })
        assertEquals(7, server.requestCount)
    }

    @Test fun userRefreshPreservesSessionAndPlaybackAndSurvivesReload() {
        sessions.save(account)
        sessions.playback.saveSelectedLibrary(account, "playlist")
        val playback = SavedPlayback(listOf(Track("job", "song.mp3")), position = 12_345)
        sessions.playback.savePlayback(account, playback)
        val user = account.user.copy(name = "Renamed", role = "shared", status = "pending", sharedUserIds = listOf("owner"))
        assertTrue(sessions.updateUser(account, user))
        val updated = account.copy(user = user)
        assertEquals(updated, sessions.account.value)
        assertEquals(updated, SessionStore(context).account.value)
        assertEquals("playlist", sessions.playback.selectedLibrary(updated))
        assertEquals(playback, sessions.playback.playback(updated))
        assertTrue(sessions.updateUser(updated, user))
        assertFalse(sessions.updateUser(account, account.user))
    }

    @Test fun lateUserResponsesCannotRestoreOrReplaceAnotherSession() {
        sessions.save(account)
        val shared = account.user.copy(role = "shared")
        sessions.clear()
        assertFalse(sessions.updateUser(account, shared))
        assertNull(sessions.account.value)
        val replacement = account.copy(session = account.session.copy(token = "N".repeat(43)))
        sessions.save(replacement)
        assertFalse(sessions.updateUser(account, shared))
        assertFalse(sessions.updateUser(replacement, shared.copy(id = "other-user")))
        assertEquals(replacement, sessions.account.value)
    }

    private fun createModel(collectSession: Boolean = false, connectPlayback: Boolean = true): MusicViewModel {
        lateinit var model: MusicViewModel
        compose.runOnUiThread {
            model = MusicViewModel(application, connectPlayback)
            models.put("music", model)
            if (!collectSession) model.viewModelScope.cancel()
        }
        return model
    }

    private fun enqueueUser(user: User) {
        server.enqueue(MockResponse().setBody(ApiJson.encodeToString(UserResponse(user))))
    }

    private fun takePath(): String = server.takeRequest(2, TimeUnit.SECONDS)!!.path!!
}

class AccountRefreshKeyStore : KeyStoreSpi() {
    override fun engineGetKey(alias: String?, password: CharArray?): Key = key
    override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null
    override fun engineGetCertificate(alias: String?): Certificate? = null
    override fun engineGetCreationDate(alias: String?): Date = Date(0)
    override fun engineSetKeyEntry(alias: String?, key: Key?, password: CharArray?, chain: Array<out Certificate>?) = Unit
    override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?) = Unit
    override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) = Unit
    override fun engineDeleteEntry(alias: String?) = Unit
    override fun engineAliases() = Collections.enumeration(listOf("ssmusic.session"))
    override fun engineContainsAlias(alias: String?) = alias == "ssmusic.session"
    override fun engineSize() = 1
    override fun engineIsKeyEntry(alias: String?) = engineContainsAlias(alias)
    override fun engineIsCertificateEntry(alias: String?) = false
    override fun engineGetCertificateAlias(cert: Certificate?): String? = null
    override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
    override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit

    companion object {
        private val key = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
    }
}
