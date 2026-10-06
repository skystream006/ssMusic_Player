package com.ssytdlp.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun PlayerScreenTransition(screen: Int, hasPlayer: Boolean,
    content: @Composable (screen: Int, pageModifier: Modifier, dockModifier: Modifier) -> Unit) {
    SharedTransitionLayout(Modifier.fillMaxSize()) {
        val transition = updateTransition(screen, label = "Player navigation")
        val expandingPlayer = transition.currentState == 1 || transition.targetState == 1
        transition.AnimatedContent(
            modifier = Modifier.fillMaxSize(),
            transitionSpec = { (EnterTransition.None togetherWith ExitTransition.None).using(null) }
        ) { visibleScreen ->
            val player = if (hasPlayer) Modifier.sharedBounds(
                sharedContentState = rememberSharedContentState("player"),
                animatedVisibilityScope = this,
                boundsTransform = { _, _ ->
                    if (expandingPlayer) tween(350, easing = FastOutSlowInEasing) else snap()
                },
                enter = fadeIn(if (expandingPlayer) tween(220, delayMillis = 80) else snap()),
                exit = fadeOut(if (expandingPlayer) tween(90) else snap()),
                resizeMode = SharedTransitionScope.ResizeMode.ScaleToBounds(ContentScale.FillBounds)
            ) else Modifier
            content(visibleScreen, if (visibleScreen == 1) player else Modifier,
                if (visibleScreen != 1) player else Modifier)
        }
    }
}
