package com.ssytdlp.app

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.ssytdlp.app.core.Track
import com.ssytdlp.app.core.TrackPage
import com.ssytdlp.app.core.Transcription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp")
class SongListGesturesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val first = Track("job", "first.mp3", playlistId = "playlist")
    private val second = Track("job", "second.mp3", playlistId = "playlist")
    private val third = Track("job", "third.mp3", playlistId = "playlist")

    @Test fun playlistDragUsesServerKeysAndKeepsPlaybackIndicesInRawOrder() {
        val instrumental = first.copy(name = "[NoVocals]/first.mp3")
        val files = listOf(instrumental, first, second, third)
        val moves = mutableListOf<Triple<Track, Track, Boolean>>()
        val played = mutableListOf<Int>()
        compose.setContent {
            MusicTheme {
                LibraryContent(LibraryState(tracks = TrackPage(files = files, totalPages = 2), selectedId = "playlist"),
                    PlaybackState(connected = true), onPlay = { played += it }, onPage = {},
                    onReorder = { source, target, after -> moves += Triple(source, target, after) }) { _, _ -> }
            }
        }
        drag("first", "third")
        compose.runOnIdle {
            assertEquals(listOf(Triple(first, third, true)), moves)
            assertTrue(played.isEmpty())
        }
        compose.onNodeWithText("second").performClick()
        compose.runOnIdle { assertEquals(listOf(2), played) }
        drag("third", "first")
        compose.runOnIdle { assertEquals(Triple(third, first, false), moves.last()) }
    }

    @Test fun playlistDragDoesNotCrossNoVocalsHeaderAndSwipingDoesNotRemoveSongs() {
        val instrumental = third.copy(name = "[NoVocals]/instrumental.mp3")
        val files = listOf(first, second, instrumental)
        val moves = mutableListOf<Triple<Track, Track, Boolean>>()
        compose.setContent {
            MusicTheme {
                LibraryContent(LibraryState(tracks = TrackPage(files = files), selectedId = "playlist"),
                    PlaybackState(connected = true), onPlay = {}, onPage = {},
                    onReorder = { source, target, after -> moves += Triple(source, target, after) }) { _, _ -> }
            }
        }
        compose.onNodeWithContentDescription("Expand NoVocals").performClick()
        drag("second", "instrumental")
        compose.runOnIdle { assertTrue(moves.isEmpty()) }
        compose.onNodeWithText("first").performTouchInput { swipeLeft() }
        compose.onNodeWithText("first").assertIsDisplayed()
        compose.onNodeWithText("second").assertIsDisplayed()
        compose.runOnIdle { assertTrue(moves.isEmpty()) }
    }

    @Test fun libraryDoesNotOfferReorderingOutsideAnUnfilteredPlaylist() {
        val library = mutableStateOf(LibraryState(tracks = TrackPage(files = listOf(first, second))))
        compose.setContent {
            MusicTheme {
                LibraryContent(library.value, PlaybackState(connected = true), onPlay = {}, onPage = {},
                    onReorder = { _, _, _ -> error("Unexpected reorder") }) { _, _ -> }
            }
        }
        compose.onNodeWithContentDescription("Drag to reorder first").assertDoesNotExist()
        compose.runOnIdle { library.value = library.value.copy(selectedId = "playlist", search = "first") }
        compose.onNodeWithContentDescription("Drag to reorder first").assertDoesNotExist()
        compose.runOnIdle { library.value = library.value.copy(search = "", loading = true) }
        compose.onNodeWithContentDescription("Drag to reorder first").assertIsNotEnabled()
    }

    @Test fun queueDragMovesOnlyTheSelectedDuplicateOccurrenceAndDoesNotPlayOrRemove() {
        val moves = mutableListOf<Pair<Int, Int>>()
        val removed = mutableListOf<Int>()
        val played = mutableListOf<Int>()
        compose.setContent {
            MusicTheme {
                QueueContent(PlaybackState(connected = true, queue = listOf(first, second, first), index = 2),
                    LibraryState(), onSelect = { played += it }, onMove = { from, to -> moves += from to to },
                    onRemove = { removed += it })
            }
        }
        val from = compose.onAllNodesWithContentDescription("Drag to reorder first")[1]
        val to = compose.onAllNodesWithContentDescription("Drag to reorder first")[0]
        val distance = to.fetchSemanticsNode().boundsInRoot.center.y - from.fetchSemanticsNode().boundsInRoot.center.y
        from.performTouchInput {
            down(center)
            moveBy(Offset(0f, distance - height / 2f))
            up()
        }
        compose.runOnIdle {
            assertEquals(listOf(2 to 0), moves)
            assertTrue(removed.isEmpty())
            assertTrue(played.isEmpty())
        }
    }

    @Test fun queueOnlyRemovesOnCompletedLeftSwipeAndUsesTheOccurrenceIndex() {
        val removed = mutableListOf<Int>()
        val queue = mutableStateOf(listOf(first, second, first))
        compose.setContent {
            MusicTheme {
                QueueContent(PlaybackState(connected = true, queue = queue.value), LibraryState(),
                    onSelect = { error("Swipe must not select") }, onMove = { _, _ -> error("Swipe must not reorder") },
                    onRemove = { index -> removed += index; queue.value = queue.value.filterIndexed { i, _ -> i != index } })
            }
        }
        compose.onNodeWithTag("queue-row-1").performTouchInput { swipeRight() }
        compose.onNodeWithTag("queue-row-1").performTouchInput {
            swipe(center, center - Offset(50f, 0f), 300)
        }
        compose.onNodeWithTag("queue-row-1").performTouchInput {
            down(centerRight - Offset(2f, 0f))
            moveBy(Offset(-width * 0.7f, 0f))
            cancel()
        }
        compose.runOnIdle { assertTrue(removed.isEmpty()) }
        compose.onNodeWithTag("queue-row-2").performTouchInput { swipeLeft() }
        compose.runOnIdle {
            assertEquals(listOf(2), removed)
            assertEquals(listOf(first, second), queue.value)
        }
        compose.onNodeWithTag("queue-row-1").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("queue-row-0").performTouchInput { swipeLeft() }
        compose.runOnIdle {
            assertEquals(listOf(2, 1, 0), removed)
            assertTrue(queue.value.isEmpty())
        }
    }

    @Test fun disconnectedQueueIgnoresReorderAndSwipe() {
        compose.setContent {
            MusicTheme {
                QueueContent(PlaybackState(queue = listOf(first, second)), LibraryState(), onSelect = {},
                    onMove = { _, _ -> error("Disconnected reorder") }, onRemove = { error("Disconnected removal") })
            }
        }
        compose.onNodeWithContentDescription("Drag to reorder first").assertIsNotEnabled()
        compose.onNodeWithTag("queue-row-0").performTouchInput { swipeLeft() }
        compose.onNodeWithText("first").assertIsDisplayed()
    }

    @Test fun narrowQueueKeepsSongTextAndFullSizeActionsWithTranscriptionStatus() {
        val track = first.copy(title = "A title that stays visible", transcription = Transcription(status = "transcribed"))
        compose.setContent {
            MusicTheme {
                Box(Modifier.width(280.dp)) {
                    QueueContent(PlaybackState(connected = true, queue = listOf(track, second)), LibraryState(),
                        onSelect = {}, onMove = { _, _ -> }, onRemove = {}) { _, _ ->
                        ToolButton(Icons.Rounded.MoreVert, "Song options") {}
                    }
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(track.title).assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.single().getLineEnd(0) > 5)
        compose.onNodeWithContentDescription("Drag to reorder ${track.title}").assertWidthIsAtLeast(48.dp)
        val actionWidth = compose.onAllNodesWithContentDescription("Song options")[0]
            .fetchSemanticsNode().touchBoundsInRoot.width
        assertTrue(actionWidth >= with(compose.density) { 48.dp.toPx() })
    }

    private fun drag(source: String, target: String) {
        val from = compose.onNodeWithContentDescription("Drag to reorder $source")
        val to = compose.onNodeWithContentDescription("Drag to reorder $target")
        val distance = to.fetchSemanticsNode().boundsInRoot.center.y - from.fetchSemanticsNode().boundsInRoot.center.y
        from.performTouchInput {
            down(center)
            moveBy(Offset(0f, distance + if (distance > 0) height / 2f else -height / 2f))
            up()
        }
    }
}
