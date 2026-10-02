package com.ssytdlp.app

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ssytdlp.app.core.Job
import com.ssytdlp.app.core.Track
import com.ssytdlp.app.core.User
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h900dp")
class ReplaceFileDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val track = Track("job", "song.mp3")
    private val owner = User("owner")
    private val job = Job("job", status = "completed", initiatedBy = owner, contributors = listOf(User("contributor")))

    @Test fun `only owners contributors and admins can replace idle audio including no vocals`() {
        for (user in listOf(owner, User("contributor"), User("admin", role = "admin"))) {
            assertTrue(canReplaceFile(user, job, track))
            assertTrue(canReplaceFile(user, job, track.copy(name = "[NoVocals]/song.mp3", transcriptionLocked = true)))
            assertFalse(canReplaceFile(user, job.copy(status = "running"), track))
            assertFalse(canReplaceFile(user, job.copy(status = "queued"), track))
            assertFalse(canReplaceFile(user, job, track.copy(mediaType = "video")))
        }
        assertFalse(canReplaceFile(null, job, track))
        assertFalse(canReplaceFile(User("other"), job, track))
        assertFalse(canReplaceFile(owner.copy(role = "Shared"), job, track))
        assertFalse(canReplaceFile(owner, null, track))
        assertFalse(canReplaceFile(owner, job.copy(id = "other"), track))
    }

    @Test fun `choosing a file does not upload and confirmation requires a selection`() {
        var choices = 0
        var uploads = 0
        compose.setContent {
            MusicTheme(waveAppearance = false) {
                ReplaceFileDialog(track, null, null, false, {}, { choices++ }, { uploads++ })
            }
        }
        compose.onNode(hasText("Replace File") and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithText("Choose replacement file").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, choices); assertEquals(0, uploads) }
    }

    @Test fun `selected file requires explicit overwrite confirmation and supports cancel`() {
        var uploads = 0
        var cancelled = false
        compose.setContent {
            MusicTheme(waveAppearance = false) {
                ReplaceFileDialog(track, "new.mp3", null, false, { cancelled = true }, {}, { uploads++ })
            }
        }
        compose.onNodeWithText("This permanently overwrites", substring = true).assertExists()
        compose.runOnIdle { assertEquals(0, uploads) }
        compose.onNode(hasText("Replace File") and hasClickAction()).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, uploads) }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertTrue(cancelled) }
    }

    @Test fun `validation errors disable overwrite`() {
        compose.setContent {
            MusicTheme(waveAppearance = false) {
                ReplaceFileDialog(track, "new.flac", "Choose a .mp3 audio file.", false, {}, {}, {})
            }
        }
        compose.onNodeWithText("Choose a .mp3 audio file.").assertExists()
        compose.onNode(hasText("Replace File") and hasClickAction()).assertIsNotEnabled()
    }

    @Test fun `upload disables selection confirmation and dismissal`() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MusicTheme(waveAppearance = false) {
                ReplaceFileDialog(track, "new.mp3", null, true, {}, {}, {})
            }
        }
        compose.onNodeWithText("Uploading and replacing file...").assertExists()
        compose.onNodeWithText("new.mp3").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
        compose.onNode(hasText("Replace File") and hasClickAction()).assertIsNotEnabled()
    }
}
