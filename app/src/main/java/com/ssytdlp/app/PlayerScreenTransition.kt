package com.ssytdlp.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun PlayerScreenTransition(screen: Int, expanded: Boolean, hasPlayer: Boolean,
    content: @Composable (screen: Int, pageModifier: Modifier, dock: @Composable (@Composable () -> Unit) -> Unit) -> Unit) {
    SharedTransitionLayout(Modifier.fillMaxSize()) {
        val transition = updateTransition(expanded && hasPlayer, label = "Player navigation")
        val covered = transition.currentState || transition.targetState
        val hidden = covered && !transition.isRunning && transition.targetState
        val savedPages = rememberSaveableStateHolder()
        var dockHeight by rememberSaveable { mutableIntStateOf(0) }
        RetainedPage(covered, Modifier.fillMaxSize().drawWithContent { if (!hidden) drawContent() }) {
            savedPages.SaveableStateProvider(screen) {
                content(screen, Modifier) { dockContent ->
                    // Keep the library's viewport unchanged after the dock finishes its exit.
                    Box(Modifier.heightIn(min = with(LocalDensity.current) { dockHeight.toDp() })) {
                        transition.AnimatedVisibility(
                            visible = { !it }, enter = EnterTransition.None, exit = ExitTransition.None
                        ) {
                            Box(Modifier.onSizeChanged { dockHeight = it.height }.sharedBounds(
                                sharedContentState = rememberSharedContentState("player"),
                                animatedVisibilityScope = this,
                                boundsTransform = { _, _ -> tween(350, easing = FastOutSlowInEasing) },
                                enter = fadeIn(tween(220, delayMillis = 80)),
                                exit = fadeOut(tween(90)),
                                resizeMode = SharedTransitionScope.ResizeMode.ScaleToBounds(ContentScale.FillBounds)
                            )) { dockContent() }
                        }
                    }
                }
            }
        }
        transition.AnimatedVisibility(
            visible = { it }, enter = EnterTransition.None, exit = ExitTransition.None
        ) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent().changes.forEach { it.consume() }
                    }
                })
                content(1, Modifier.sharedBounds(
                    sharedContentState = rememberSharedContentState("player"),
                    animatedVisibilityScope = this@AnimatedVisibility,
                    boundsTransform = { _, _ -> tween(350, easing = FastOutSlowInEasing) },
                    enter = fadeIn(tween(220, delayMillis = 80)),
                    exit = fadeOut(tween(90)),
                    resizeMode = SharedTransitionScope.ResizeMode.ScaleToBounds(ContentScale.FillBounds)
                )) { it() }
            }
        }
    }
}

@Composable
private fun RetainedPage(covered: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    val focus = LocalFocusManager.current
    LaunchedEffect(covered) { if (covered) focus.clearFocus(force = true) }
    val parent = LocalLifecycleOwner.current
    val owner = remember(parent) { RetainedPageLifecycle() }
    DisposableEffect(parent, covered) {
        fun update() {
            owner.registry.currentState = if (covered)
                minOf(parent.lifecycle.currentState, Lifecycle.State.CREATED) else parent.lifecycle.currentState
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        parent.lifecycle.addObserver(observer)
        update()
        onDispose { parent.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(owner) {
        onDispose { owner.registry.currentState = Lifecycle.State.DESTROYED }
    }
    CompositionLocalProvider(LocalLifecycleOwner provides owner) {
        Box(modifier
            .then(if (covered) Modifier.clearAndSetSemantics {} else Modifier)
            .focusProperties { onEnter = { if (covered) cancelFocusChange() } }
            .focusGroup()
            .onPreviewKeyEvent { covered }
            .pointerInput(covered) {
                if (covered) awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }) { content() }
    }
}

private class RetainedPageLifecycle : LifecycleOwner {
    val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
}
