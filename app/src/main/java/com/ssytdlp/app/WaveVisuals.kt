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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import androidx.compose.ui.semantics.Role
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
fun MusicTopBar(userName: String, refreshEnabled: Boolean, onRefresh: () -> Unit,
    onSettings: () -> Unit, onBack: (() -> Unit)? = null) {
    TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        navigationIcon = {
            if (onBack != null) ToolButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onBack)
        }, title = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.GraphicEq, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary)
            Column {
                Text("ssMusic", style = MaterialTheme.typography.titleLarge)
                Text("$userName's Music", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }, actions = {
        ToolButton(Icons.Rounded.Refresh, "Refresh", enabled = refreshEnabled, onClick = onRefresh)
        ToolButton(Icons.Rounded.Settings, "Settings", onClick = onSettings)
    })
}

@Composable
fun MusicNavigation(selected: Int, onSelect: (Int) -> Unit) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
        listOf("Library" to Icons.Rounded.LibraryMusic, "Now Playing" to Icons.Rounded.GraphicEq)
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
fun LibraryTopBar(state: LibraryState, playback: PlaybackState, refreshEnabled: Boolean,
    onBrowse: () -> Unit, onSearch: (String) -> Unit, onPlay: (Int) -> Unit,
    onRefresh: () -> Unit, onSettings: () -> Unit) {
    val selected = state.library.entries.find { it.id == state.selectedId }
    val title = selected?.let { entry -> state.library.playlists.find { it.id == entry.id }?.playlistTitle?.ifBlank { null } ?: entry.name } ?: "All Music"
    Column {
        TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            title = {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onBrowse),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${state.tracks.total} tracks", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.Rounded.ExpandMore, "Browse library", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }, actions = {
                FilledIconButton(onClick = { onPlay(0) },
                    enabled = state.tracks.files.isNotEmpty() && playback.connected && !state.loading, shape = CircleShape) {
                    Icon(Icons.Rounded.PlayArrow, "Play this page")
                }
                ToolButton(Icons.Rounded.Refresh, "Refresh", enabled = refreshEnabled, onClick = onRefresh)
                ToolButton(Icons.Rounded.Settings, "Settings", onClick = onSettings)
            })
        OutlinedTextField(state.search, onSearch, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            placeholder = { Text("Search your music", style = MaterialTheme.typography.bodyMedium) }, singleLine = true,
            shape = RoundedCornerShape(8.dp), colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
            leadingIcon = { Icon(Icons.Rounded.Search, null) }, trailingIcon = {
                if (state.search.isNotEmpty()) ToolButton(Icons.Rounded.Close, "Clear search") { onSearch("") }
            })
    }
}

@Composable
fun LoginContent(server: String, signingIn: Boolean, pending: Boolean, modifier: Modifier = Modifier,
    onSignIn: () -> Unit, onCancel: () -> Unit, onRegister: () -> Unit, onAppSettings: (() -> Unit)? = null,
    onChangeServer: (() -> Unit)? = null) {
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
                    if (onChangeServer != null && !signingIn) TextButton(onClick = onChangeServer) { Text("Change") }
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

@Composable
fun ServerSetupContent(value: String, error: String?, busy: Boolean, modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit, onContinue: () -> Unit, onAppSettings: () -> Unit = {}) {
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
                Text("Set up your server", style = MaterialTheme.typography.displayMedium)
                Spacer(Modifier.height(16.dp))
                Text("Enter your ssYTDLP server's hostname. Passkey sign-in on this device is bound to this address.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(value, onValueChange, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Server address") }, placeholder = { Text("music.example.com") }, singleLine = true,
                    isError = error != null, supportingText = error?.let { message -> { Text(message) } }, enabled = !busy)
                Spacer(Modifier.height(20.dp))
                Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    shape = RoundedCornerShape(8.dp), enabled = value.isNotBlank() && !busy) { Text("Continue") }
                TextButton(onClick = onAppSettings, modifier = Modifier.fillMaxWidth()) { Text("Updates and diagnostics") }
            }
        }
    }
}