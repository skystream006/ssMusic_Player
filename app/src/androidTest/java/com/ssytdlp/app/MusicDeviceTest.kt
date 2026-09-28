package com.ssytdlp.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MusicDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun folderDialogRequiresANameAndTrimsItBeforeSaving() {
        var saved: String? = null
        compose.setContent { MusicTheme { NameDialog("New folder", "", {}, { saved = it }) } }
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("  Road trip  ")
        compose.onNodeWithText("Save").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("Road trip", saved) }
    }
}