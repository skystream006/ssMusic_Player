package com.ssytdlp.app

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.ssytdlp.app.core.*
import java.security.Provider
import java.security.Security
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp")
class JobsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val provider = object : Provider("JobsTestKeyStore", 1.0, "Empty test session keystore") {}
    private val models = ViewModelStore()
    private lateinit var application: MusicApplication
    private lateinit var model: MusicViewModel
    private val user = User("me", "Me")
    private val other = User("other", "Another user")
    private val jobs = listOf(
        Job("owned", "Owned download", initiatedBy = user, updatedAt = "2026-11-22"),
        Job("contributing", "Contributed download", initiatedBy = other, contributors = listOf(user)),
        Job("other", "Other download", initiatedBy = other, url = "https://example.com/unique"),
        Job("unknown", "Unknown download")
    )
    private val account get() =
        ReflectionHelpers.getField<MutableStateFlow<Account?>>(application.sessions, "mutableAccount")

    @Before fun setup() {
        provider.put("KeyStore.AndroidKeyStore", Security.getProvider("SUN").getService("KeyStore", "JKS").className)
        Security.addProvider(provider)
        val context = ApplicationProvider.getApplicationContext<Application>()
        listOf("private_session", "playback", "settings").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        application = MusicApplication()
        ReflectionHelpers.callInstanceMethod<Unit>(application, "attach", ClassParameter.from(Context::class.java, context))
        application.onCreate()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(ApiJson.encodeToString(jobs).toResponseBody()).build()
        }.build()
        ReflectionHelpers.setField(application, "api", ServerApi({ account.value }, {}, client))
        compose.runOnUiThread {
            model = MusicViewModel(application, connectPlayback = false)
            models.put("jobs", model)
            model.viewModelScope.cancel()
            account.value = testAccount(user)
            ReflectionHelpers.getField<MutableState<List<Job>>>(model, "jobs\$delegate").value = jobs
        }
    }

    @After fun cleanup() {
        compose.runOnUiThread { models.clear() }
        Security.removeProvider(provider.name)
    }

    @Test fun ordinaryUsersOnlySeeOwnedAndContributedJobsWithoutAnOwnershipFilter() {
        showJobs()
        compose.onNodeWithText("Owned download").assertIsDisplayed()
        compose.onNodeWithText("Contributed download").assertIsDisplayed()
        compose.onNodeWithText("Other download").assertDoesNotExist()
        compose.onNodeWithText("Unknown download").assertDoesNotExist()
        compose.onNodeWithContentDescription("Filter jobs by user").assertDoesNotExist()
        compose.onNodeWithText("My downloads").assertDoesNotExist()
        compose.onNodeWithText("Search downloads").performTextInput("unique")
        compose.onNodeWithText("No downloads").assertIsDisplayed()
    }

    @Test fun adminsDefaultToMineAndCanFilterAllOrAnInitiatorAlongsideSearch() {
        compose.runOnIdle { account.value = testAccount(user.copy(role = "admin")) }
        showJobs()
        compose.onNodeWithText("Owned download").assertIsDisplayed()
        compose.onNodeWithText("Contributed download").assertIsDisplayed()
        compose.onNodeWithText("Other download").assertDoesNotExist()
        selectFilter("All users")
        compose.onNodeWithText("Other download").assertIsDisplayed()
        compose.onNodeWithText("Unknown download").assertIsDisplayed()
        selectFilter("Initiated by Another user")
        compose.onNodeWithText("Owned download").assertDoesNotExist()
        compose.onNodeWithText("Contributed download").assertIsDisplayed()
        compose.onNodeWithText("Other download").assertIsDisplayed()
        compose.onNodeWithText("Search downloads").performTextInput("UNIQUE")
        compose.onNodeWithText("Contributed download").assertDoesNotExist()
        compose.onNodeWithText("Other download").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextClearance()
        selectFilter("Initiated by Unknown")
        compose.onNodeWithText("Unknown download").assertIsDisplayed()
        compose.onNodeWithText("Other download").assertDoesNotExist()
        selectFilter("My jobs (owned and contributing)")
        compose.onNodeWithText("Owned download").assertIsDisplayed()
        compose.onNodeWithText("Contributed download").assertIsDisplayed()
        compose.onNodeWithText("Unknown download").assertDoesNotExist()
    }

    @Test fun adminFilterCannotSurviveRoleOrAccountChanges() {
        compose.runOnIdle { account.value = testAccount(user.copy(role = "admin")) }
        showJobs()
        selectFilter("All users")
        compose.runOnIdle { account.value = testAccount(user) }
        compose.onNodeWithContentDescription("Filter jobs by user").assertDoesNotExist()
        compose.onNodeWithText("Other download").assertDoesNotExist()
        compose.runOnIdle { account.value = testAccount(other.copy(role = "admin")) }
        compose.onNodeWithText("My jobs (owned and contributing)").assertIsDisplayed()
        compose.onNodeWithText("Other download").assertIsDisplayed()
        compose.onNodeWithText("Owned download").assertDoesNotExist()
        compose.onNodeWithText("Unknown download").assertDoesNotExist()
    }

    private fun showJobs() {
        compose.setContent { MusicTheme { JobsScreen(model, {}, { _, _ -> }) } }
    }

    private fun selectFilter(label: String) {
        compose.onNodeWithContentDescription("Filter jobs by user").performClick()
        compose.onNodeWithText(label).performClick()
    }

    private fun testAccount(user: User) =
        Account("https://music.example", user, Session("test-session", "2099-01-01T00:00:00Z"))
}
