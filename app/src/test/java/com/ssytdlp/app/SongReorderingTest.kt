package com.ssytdlp.app

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SongReorderingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private data class Move(val source: String, val target: String, val after: Boolean, val version: Int = 1)
    private val moves = mutableListOf<Move>()
    private var source by mutableStateOf(listOf("a", "b", "c", "d", "e"))
    private var enabled by mutableStateOf(true)
    private var handleEnabled by mutableStateOf(true)
    private var visible by mutableStateOf(true)
    private var callbackVersion by mutableIntStateOf(1)
    private var restriction by mutableStateOf<(String, String) -> Boolean>({ _, _ -> true })
    private lateinit var state: SongReorderState
    private lateinit var list: LazyListState
    private var rowHeight = 0f

    @Test fun downwardDragPreviewsFollowsPointerAndCommitsOriginalTargetOnce() {
        show()
        val initialTop = rowTop("a")
        begin("a", 1.4f)
        compose.runOnIdle {
            assertEquals(listOf("b", "a", "c", "d", "e"), state.keys)
            assertTrue(moves.isEmpty())
            assertEquals(0, list.firstVisibleItemIndex)
            assertEquals(0, list.firstVisibleItemScrollOffset)
        }
        assertTrue("Dragged row top=${rowTop("a")}, initial=$initialTop, height=$rowHeight, translation=${state.translation("a")}",
            rowTop("a") > initialTop + rowHeight)
        touch { moveBy(Offset(0f, rowHeight)) }
        advance()
        compose.runOnIdle {
            assertEquals(listOf("b", "c", "a", "d", "e"), state.keys)
            assertTrue(moves.isEmpty())
        }
        touch { up() }
        advance()
        compose.runOnIdle {
            assertEquals(listOf(Move("a", "c", true)), moves)
            assertEquals(source, state.keys)
        }
    }

    @Test fun upwardDragCommitsBeforeOriginalTargetWithoutLongPress() {
        show()
        begin("c", -2.4f)
        compose.runOnIdle {
            assertEquals(listOf("c", "a", "b", "d", "e"), state.keys)
            assertTrue(moves.isEmpty())
        }
        touch { up() }
        advance()
        compose.runOnIdle { assertEquals(listOf(Move("c", "a", false)), moves) }
    }

    @Test fun cancellationAndReturningToOriginalPositionNeverCommit() {
        show()
        begin("a", 1.4f)
        touch { cancel() }
        advance()
        compose.runOnIdle {
            assertEquals(source, state.keys)
            assertTrue(moves.isEmpty())
        }
        begin("a", 1.4f)
        touch { moveBy(Offset(0f, -rowHeight * 1.4f)) }
        advance()
        compose.runOnIdle { assertEquals(source, state.keys) }
        touch { up() }
        advance()
        compose.runOnIdle { assertTrue(moves.isEmpty()) }
    }

    @Test fun externalChangesOrDisablingDuringDragDiscardPreview() {
        show()
        begin("a", 1.4f)
        compose.runOnIdle { source = listOf("a", "c", "b", "d", "e") }
        advance()
        compose.runOnIdle { assertEquals(source, state.keys) }
        touch { up() }
        advance()
        compose.runOnIdle { assertTrue(moves.isEmpty()) }

        begin("a", 1.4f)
        compose.runOnIdle { enabled = false }
        advance()
        touch { up() }
        advance()
        compose.runOnIdle {
            assertEquals(source, state.keys)
            assertTrue(moves.isEmpty())
        }
        handle("a").assertIsNotEnabled()
        compose.runOnIdle { enabled = true }
        advance()
        begin("a", 1.4f)
        compose.runOnIdle { handleEnabled = false }
        advance()
        touch { up() }
        advance()
        compose.runOnIdle {
            assertEquals(source, state.keys)
            assertTrue(moves.isEmpty())
        }
    }

    @Test fun recompositionKeepsGestureAndUsesLatestCallback() {
        show()
        begin("a", 1.4f)
        compose.runOnIdle {
            callbackVersion = 2
            source = source.toList()
            restriction = { _, _ -> true }
        }
        advance()
        touch { moveBy(Offset(0f, rowHeight)) }
        advance()
        touch { up() }
        advance()
        compose.runOnIdle { assertEquals(listOf(Move("a", "c", true, 2)), moves) }
    }

    @Test fun removingDraggedKeysNeverExposesStalePreviewOrCallsPredicateWithRemovedKeys() {
        show()
        begin("a", 1.4f)
        compose.runOnIdle { source = listOf("x", "c", "d", "e") }
        advance()
        touch { up() }
        advance()
        compose.runOnIdle {
            assertEquals(source, state.keys)
            assertTrue(moves.isEmpty())
        }
        handle("a").assertDoesNotExist()
        handle("x").assertIsDisplayed()
    }

    @Test fun headersAreNotTargetsAndGroupBoundariesCannotBeCrossed() {
        source = listOf("a0", "a1", "b0", "b1")
        restriction = { first, second -> first.first() == second.first() }
        show(headerBefore = "b0")
        begin("a0", 3.5f)
        compose.runOnIdle {
            assertEquals(listOf("a1", "a0", "b0", "b1"), state.keys)
            assertTrue(moves.isEmpty())
        }
        compose.onNodeWithText("Group header").assertIsDisplayed()
        touch { up() }
        advance()
        compose.runOnIdle { assertEquals(listOf(Move("a0", "a1", true)), moves) }
    }

    @Test fun changingMovePermissionDuringDragPreventsStaleCommit() {
        show()
        begin("a", 1.4f)
        compose.runOnIdle { restriction = { _, _ -> false } }
        advance()
        touch { up() }
        advance()
        compose.runOnIdle {
            assertEquals(source, state.keys)
            assertTrue(moves.isEmpty())
        }
    }

    @Test fun accessibilityActionsMoveNeighboursAndRespectBoundariesAndDisabledHandles() {
        source = listOf("a0", "a1", "b0", "b1")
        restriction = { first, second -> first.first() == second.first() }
        show(headerBefore = "b0")
        assertEquals(listOf("Move down"), actions("a0"))
        assertEquals(listOf("Move up"), actions("a1"))
        assertEquals(listOf("Move down"), actions("b0"))
        assertEquals(listOf("Move up"), actions("b1"))
        performAction("a0", "Move down")
        performAction("a1", "Move up")
        compose.runOnIdle {
            assertEquals(listOf(Move("a0", "a1", true), Move("a1", "a0", false)), moves)
            handleEnabled = false
        }
        advance()
        handle("a0").assertIsNotEnabled()
        assertTrue(actions("a0").isEmpty())
        begin("a0", 1.4f)
        touch { up() }
        advance()
        compose.runOnIdle { assertEquals(2, moves.size) }
    }

    @Test fun holdingStillAtBottomAutoScrollsAndCancellationStopsScrolling() {
        source = (0 until 30).map(Int::toString)
        show(height = 256)
        begin("0", 3.2f)
        val before = scrollPosition()
        advance(800)
        compose.runOnIdle {
            assertTrue(scrollPosition() > before + rowHeight)
            assertTrue(state.keys.indexOf("0") > 3)
            assertTrue(moves.isEmpty())
        }
        touch { cancel() }
        advance()
        val cancelledAt = scrollPosition()
        advance(400)
        compose.runOnIdle {
            assertEquals(cancelledAt, scrollPosition(), 0.1f)
            assertEquals(source, state.keys)
            assertTrue(moves.isEmpty())
        }
    }

    @Test fun holdingStillAtTopAutoScrollsAndDropCommitsScrolledTarget() {
        source = (0 until 30).map(Int::toString)
        show(height = 256, firstIndex = 8)
        begin("9", -1.4f)
        val before = scrollPosition()
        advance(800)
        compose.runOnIdle {
            assertTrue(scrollPosition() < before - rowHeight)
            assertTrue(state.keys.indexOf("9") < 8)
            assertTrue(moves.isEmpty())
        }
        val target = source[state.keys.indexOf("9")]
        touch { up() }
        advance()
        compose.runOnIdle { assertEquals(listOf(Move("9", target, false)), moves) }
        val droppedAt = scrollPosition()
        advance(400)
        compose.runOnIdle { assertEquals(droppedAt, scrollPosition(), 0.1f) }
    }

    @Test fun disposingTheListCancelsAutoScrollAndDoesNotCommit() {
        source = (0 until 30).map(Int::toString)
        show(height = 256)
        begin("0", 3.2f)
        advance(100)
        compose.runOnIdle { visible = false }
        advance()
        val disposedAt = scrollPosition()
        advance(400)
        compose.runOnIdle {
            assertEquals(disposedAt, scrollPosition(), 0.1f)
            assertTrue(moves.isEmpty())
        }
    }

    @Test fun previewAndCancellationPreserveFirstVisibleIndexAndOffset() {
        source = (0 until 12).map(Int::toString)
        show(firstIndex = 2, firstOffset = 12)
        begin("2", 1.4f)
        compose.runOnIdle {
            assertEquals(2, list.firstVisibleItemIndex)
            assertEquals(12, list.firstVisibleItemScrollOffset)
        }
        touch { cancel() }
        advance()
        compose.runOnIdle {
            assertEquals(2, list.firstVisibleItemIndex)
            assertEquals(12, list.firstVisibleItemScrollOffset)
            assertTrue(moves.isEmpty())
        }
    }

    private fun show(
        height: Int = 360,
        headerBefore: String? = null,
        firstIndex: Int = 0,
        firstOffset: Int = 0
    ) {
        compose.setContent {
            if (visible) {
                list = rememberLazyListState(firstIndex, firstOffset)
                val version = callbackVersion
                val titles = source.associateWith { "Song $it" }
                val predicate = restriction
                state = rememberSongReorderState(list, source, enabled, canMove = { from, to ->
                    titles.getValue(from)
                    titles.getValue(to)
                    predicate(from, to)
                }) { from, to, after ->
                    moves += Move(from, to, after, version)
                }
                val rows = state.keys.map { it to titles.getValue(it) }
                LazyColumn(Modifier.width(300.dp).height(height.dp).testTag("songs"), state = list) {
                    rows.forEach { (key, title) ->
                        if (key == headerBefore) item(key = "header") {
                            Text("Group header", Modifier.height(48.dp))
                        }
                        item(key = key) {
                            Row(
                                Modifier.fillMaxWidth().height(64.dp).songReorderItem(state, key)
                                    .testTag("row-$key"),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SongDragHandle(state, key, title, handleEnabled)
                                Text(title)
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        rowHeight = compose.onNodeWithTag("row-${source[firstIndex]}").fetchSemanticsNode().size.height.toFloat()
        compose.mainClock.autoAdvance = false
    }

    private fun handle(key: String) = compose.onNodeWithContentDescription("Drag to reorder Song $key")

    private fun rowTop(key: String) =
        compose.onNodeWithTag("row-$key").fetchSemanticsNode().boundsInRoot.top

    private fun begin(key: String, rows: Float) {
        val origin = compose.onNodeWithTag("songs").fetchSemanticsNode().boundsInRoot.topLeft
        val point = handle(key).fetchSemanticsNode().boundsInRoot.center - origin
        touch {
            down(point)
            moveBy(Offset(0f, rowHeight * rows))
        }
        advance()
    }

    private fun touch(block: TouchInjectionScope.() -> Unit) {
        compose.onNodeWithTag("songs").performTouchInput(block)
    }

    private fun advance(millis: Long = 48) {
        compose.runOnIdle { Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(millis)
        compose.waitForIdle()
    }

    private fun scrollPosition() = list.firstVisibleItemIndex * rowHeight + list.firstVisibleItemScrollOffset

    private fun actions(key: String): List<String> =
        handle(key).fetchSemanticsNode().config[SemanticsActions.CustomActions].map { it.label }

    private fun performAction(key: String, label: String) {
        val action = handle(key).fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == label }
        compose.runOnIdle { assertTrue(action.action()) }
    }
}
