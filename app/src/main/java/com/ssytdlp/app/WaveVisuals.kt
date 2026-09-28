@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ssytdlp.app

import android.app.Activity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

@Composable
fun SystemBarAppearance() {
    val view = LocalView.current
    val light = MaterialTheme.colorScheme.background.luminance() > 0.5f
    SideEffect {
        val activity = view.context as? Activity
        if (activity != null) WindowCompat.getInsetsController(activity.window, view).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }
}

@Composable
fun WaveBackdrop(modifier: Modifier = Modifier, opacity: Float = 1f) {
    if (!LocalWaveAppearance.current) return
    val background = MaterialTheme.colorScheme.background
    Box(modifier) {
        Image(painterResource(R.drawable.wave_ribbon), contentDescription = null, modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Crop, alpha = opacity)
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            0f to background.copy(alpha = 0.15f), 0.72f to Color.Transparent, 1f to background)))
    }
}

@Composable
fun MusicTopBar(userName: String, refreshEnabled: Boolean, onRefresh: () -> Unit) {
    TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background), title = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.GraphicEq, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary)
            Column {
                Text("ssMusic", style = MaterialTheme.typography.titleLarge)
                Text("$userName's Music", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }, actions = { ToolButton(Icons.Rounded.Refresh, "Refresh", enabled = refreshEnabled, onClick = onRefresh) })
}

@Composable
fun MusicNavigation(selected: Int, onSelect: (Int) -> Unit) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
        listOf("Library" to Icons.Rounded.LibraryMusic, "Downloads" to Icons.Rounded.Download, "Settings" to Icons.Rounded.Settings)
            .forEachIndexed { index, (label, icon) ->
                NavigationBarItem(selected = selected == index, onClick = { onSelect(index) }, icon = { Icon(icon, label, Modifier.size(22.dp)) },
                    label = { Text(label) }, colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.09f),
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant, unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant))
            }
    }
}

@Composable
fun LibraryHeading(title: String, count: Int, folder: Boolean, onBrowse: () -> Unit, playEnabled: Boolean, onPlay: () -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        WaveBackdrop(Modifier.matchParentSize(), opacity = 0.7f)
        Row(Modifier.fillMaxWidth().heightIn(min = 152.dp).padding(horizontal = 24.dp, vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Row(Modifier.clickable(onClick = onBrowse).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (folder) Icons.Rounded.FolderOpen else Icons.Rounded.LibraryMusic, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("LIBRARY", Modifier.padding(start = 8.dp, end = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Icon(Icons.Rounded.ExpandMore, "Browse library", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("$count tracks", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp))
            }
            FilledIconButton(onClick = onPlay, enabled = playEnabled, modifier = Modifier.size(56.dp), shape = CircleShape) {
                Icon(Icons.Rounded.PlayArrow, "Play this page", Modifier.size(30.dp))
            }
        }
    }
}

@Composable
fun LoginContent(server: String, signingIn: Boolean, pending: Boolean, modifier: Modifier = Modifier,
    onSignIn: () -> Unit, onCancel: () -> Unit, onRegister: () -> Unit, onAppSettings: (() -> Unit)? = null) {
    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val compactHeight = maxHeight < 500.dp
        val artworkHeight = if (compactHeight) 180.dp else 300.dp
        WaveBackdrop(Modifier.fillMaxWidth().height(artworkHeight).align(Alignment.TopCenter))
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.GraphicEq, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("ssMusic", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(if (compactHeight) 72.dp else 184.dp))
                Text("ssMusic Player", style = MaterialTheme.typography.displayMedium)
                Spacer(Modifier.height(32.dp))
                Row(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(8.dp)).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.Lock, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("SERVER", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(server.removePrefix("https://"), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(20.dp))
                Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(8.dp), enabled = server.isNotBlank() && !signingIn) {
                    if (signingIn) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.Fingerprint, null, Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(if (signingIn) "Signing in..." else "Sign in with passkey")
                }
                if (pending && !signingIn) TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Cancel sign-in") }
                TextButton(onClick = onRegister, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp),
                    enabled = server.isNotBlank() && !signingIn) { Text("Create an account", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (onAppSettings != null) TextButton(onClick = onAppSettings, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Updates and diagnostics")
                }
            }
        }
    }
}