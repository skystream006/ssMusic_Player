@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.ui.text.ExperimentalTextApi::class)

package com.ssytdlp.app

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.ssytdlp.app.core.*
import kotlinx.coroutines.delay

val LocalWaveAppearance = staticCompositionLocalOf { true }

@Composable
fun MusicTheme(preferences: Preferences = Preferences(), waveAppearance: Boolean = true, content: @Composable () -> Unit) {
    val accent = when (preferences.theme) {
        "midnight" -> Color(0xFF5F7FF0)
        "royal-purple" -> Color(0xFF7139C6)
        "gold" -> Color(0xFF8C6600)
        "green" -> Color(0xFF227452)
        "pink" -> Color(0xFFBF3D78)
        "black" -> Color(0xFF276449)
        else -> Color(0xFF376D60)
    }
    val dark = preferences.mode == "dark" || (preferences.mode == null && preferences.theme in listOf("midnight", "black"))
    val colors = if (waveAppearance) darkColorScheme(
        primary = Color(0xFF68DEFF), onPrimary = Color(0xFF00212D),
        primaryContainer = Color(0xFF1049A2), onPrimaryContainer = Color(0xFFF0F6FF),
        secondary = Color(0xFFB5C9E8), onSecondary = Color(0xFF162336),
        secondaryContainer = Color(0xFF192D46), onSecondaryContainer = Color(0xFFD8E7FF),
        tertiary = Color(0xFFB9D2C8), onTertiary = Color(0xFF132820),
        background = Color(0xFF030508), onBackground = Color(0xFFEDF3FC),
        surface = Color(0xFF080C12), onSurface = Color(0xFFEDF3FC),
        surfaceDim = Color(0xFF06090E), surfaceBright = Color(0xFF252E3A),
        surfaceContainerLowest = Color(0xFF030508), surfaceContainerLow = Color(0xFF0B1018),
        surfaceContainer = Color(0xFF101720), surfaceContainerHigh = Color(0xFF17212D),
        surfaceContainerHighest = Color(0xFF202D3E),
        surfaceVariant = Color(0xFF1A2636), onSurfaceVariant = Color(0xFFA5B6CC),
        outline = Color(0xFF55667D), outlineVariant = Color(0xFF253347)
    ) else if (dark) darkColorScheme(primary = accent.copy(alpha = 1f), secondary = Color(0xFFF3B6AA),
        background = Color(0xFF141817), surface = Color(0xFF191E1C), onPrimary = Color.White)
    else lightColorScheme(primary = accent, secondary = Color(0xFF9F4C3B), background = Color(0xFFF6F8F6), surface = Color(0xFFF6F8F6))
    fun bundledFont(resource: Int) = FontFamily(
        Font(resource, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
        Font(resource, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
        Font(resource, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600)))
    )
    val display = remember { bundledFont(R.font.space_grotesk) }
    val body = remember { bundledFont(R.font.manrope) }
    fun textStyle(family: FontFamily, size: Int, lineHeight: Int, weight: FontWeight = FontWeight.Normal) =
        TextStyle(fontFamily = family, fontSize = size.sp, lineHeight = lineHeight.sp, fontWeight = weight, letterSpacing = 0.sp)
    val typography = Typography(
        displayLarge = textStyle(display, 44, 50, FontWeight.SemiBold),
        displayMedium = textStyle(display, 38, 44, FontWeight.SemiBold),
        displaySmall = textStyle(display, 34, 40, FontWeight.SemiBold),
        headlineLarge = textStyle(display, 32, 38, FontWeight.SemiBold),
        headlineMedium = textStyle(display, 28, 34, FontWeight.SemiBold),
        headlineSmall = textStyle(display, 24, 30, FontWeight.Medium),
        titleLarge = textStyle(display, 22, 28, FontWeight.Medium),
        titleMedium = textStyle(body, 16, 23, FontWeight.SemiBold),
        titleSmall = textStyle(body, 14, 20, FontWeight.SemiBold),
        bodyLarge = textStyle(body, 16, 24), bodyMedium = textStyle(body, 14, 21), bodySmall = textStyle(body, 12, 18),
        labelLarge = textStyle(body, 13, 18, FontWeight.SemiBold),
        labelMedium = textStyle(body, 12, 17, FontWeight.Medium), labelSmall = textStyle(body, 11, 16, FontWeight.Medium)
    )
    CompositionLocalProvider(LocalWaveAppearance provides waveAppearance) {
        MaterialTheme(colorScheme = colors, typography = typography,
            shapes = Shapes(extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(6.dp),
                medium = RoundedCornerShape(8.dp), large = RoundedCornerShape(8.dp), extraLarge = RoundedCornerShape(8.dp)),
            content = content)
    }
}

@Composable
fun MusicApp(model: MusicViewModel, requestNotifications: () -> Unit) {
    UpdateNotification()
    val serverOrigin by model.serverOrigin.collectAsStateWithLifecycle()
    val account by model.sessions.account.collectAsStateWithLifecycle()
    val playback by model.playback.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var screen by rememberSaveable { mutableIntStateOf(0) }
    var settingsReturnScreen by rememberSaveable { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(account?.user?.isShared, screen) {
        if (account?.user?.isShared == true && screen == 3) screen = 2
    }
    LaunchedEffect(lifecycle, account, screen) {
        if (account != null && screen in 0..1) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { model.pollTranscriptions(); delay(10_000) }
        }
    }
    val back: () -> Unit = {
        screen = when (screen) {
            3 -> 2
            2 -> settingsReturnScreen
            else -> 0
        }
    }
    BackHandler(enabled = account != null && screen != 0, onBack = back)
    var downloadPath by rememberSaveable { mutableStateOf<String?>(null) }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) downloadPath?.let { model.saveDownload(it, uri) }
        downloadPath = null
    }
    val download: (String, String) -> Unit = { path, filename -> downloadPath = path; saveFile.launch(filename.substringAfterLast('/')) }
    LaunchedEffect(model.notice) {
        val message = model.notice ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        if (model.notice == message) model.message(null)
    }
    MusicTheme(model.preferences, waveAppearance = model.waveAppearance) {
        SystemBarAppearance()
        Box(Modifier.fillMaxSize()) {
            Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snackbar) }, topBar = {
                if (account != null) MusicTopBar(account!!.user.name, !model.busy, model::refresh,
                    onSettings = {
                        if (screen < 2) settingsReturnScreen = screen
                        screen = 2
                    }, onBack = if (screen != 0) back else null)
            }, bottomBar = {
                if (account != null) Column {
                    if (playback.track != null && screen != 1) PlayerDock(playback, model, { screen = 1 }, requestNotifications)
                    MusicNavigation(screen) { screen = it }
                }
            }) { padding ->
                when {
                    serverOrigin == null -> ServerSetupScreen(model, Modifier.padding(padding))
                    account == null -> LoginScreen(model, Modifier.padding(padding))
                    else -> Column(Modifier.padding(padding).fillMaxSize()) {
                        if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        when (screen) {
                            0 -> LibraryScreen(model, playback, requestNotifications, download)
                            1 -> NowPlayingScreen(model, playback)
                            2 -> SettingsScreen(model, download, onJobs = { screen = 3 })
                            3 -> if (account?.user?.isShared != true) JobsScreen(model, requestNotifications, download)
                        }
                    }
                }
            }
            PlaybackEdgeLighting(account != null && playback.playing, Modifier.matchParentSize(),
                enabled = model.edgeLightingEnabled, style = model.edgeLightingStyle)
        }
    }
}

@Composable
fun ServerSetupScreen(model: MusicViewModel, modifier: Modifier = Modifier) {
    var value by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var appSettings by rememberSaveable { mutableStateOf(false) }
    ServerSetupContent(value, error, false, modifier, onValueChange = { value = it; error = null },
        onContinue = { error = model.setServerOrigin(value) }, onAppSettings = { appSettings = true })
    if (appSettings) AppSettingsSheet { appSettings = false }
}

@Composable
fun LoginScreen(model: MusicViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val server by model.serverOrigin.collectAsStateWithLifecycle()
    val serverOrigin = server ?: return
    var pending by rememberSaveable { mutableStateOf(model.sessions.pending != null) }
    var appSettings by rememberSaveable { mutableStateOf(false) }
    LoginContent(serverOrigin, model.signingIn, pending, modifier, onSignIn = {
            val url = model.beginLogin()
            if (url != null) {
                try { CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url)); pending = true }
                catch (_: Exception) { model.cancelLogin(); model.message("Install a browser with passkey support to sign in.") }
            }
        }, onCancel = { model.cancelLogin(); pending = false }, onRegister = {
            try {
                val origin = AuthProtocol.normalizeOrigin(serverOrigin)
                CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(origin))
            } catch (error: Exception) { model.message(error.message ?: "Unable to open registration.") }
        }, onAppSettings = { appSettings = true }, onChangeServer = { model.changeServer() })
    if (appSettings) AppSettingsSheet { appSettings = false }
}

@Composable
private fun AppSettingsSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Updates and diagnostics", style = MaterialTheme.typography.titleLarge)
            UpdateSettings()
            HorizontalDivider()
            DebugLogSettings()
        }
    }
}

@Composable
fun LibraryScreen(model: MusicViewModel, playback: PlaybackState, requestNotifications: () -> Unit, download: (String, String) -> Unit) {
    var browser by rememberSaveable { mutableStateOf(false) }
    LibraryContent(model.library, playback, onBrowse = { browser = true }, onPlay = { index ->
        requestNotifications()
        model.playback.play(model.library.tracks.files, index)
    }, onSearch = model::search, onPage = model::page) { track, index -> TrackMenu(model, track, index, download) }
    if (browser) LibraryBrowser(model) { browser = false }
}

@Composable
fun LibraryContent(state: LibraryState, playback: PlaybackState, onBrowse: () -> Unit, onPlay: (Int) -> Unit,
    onSearch: (String) -> Unit, onPage: (Int) -> Unit, trackActions: @Composable (Track, Int) -> Unit) {
    val selected = state.library.entries.find { it.id == state.selectedId }
    val title = selected?.let { entry -> state.library.playlists.find { it.id == entry.id }?.playlistTitle?.ifBlank { null } ?: entry.name } ?: "All Music"
    Column(Modifier.fillMaxSize()) {
        LibraryHeading(title, state.tracks.total, selected?.type == "folder", onBrowse,
            playEnabled = state.tracks.files.isNotEmpty() && playback.connected && !state.loading, onPlay = { onPlay(0) })
        OutlinedTextField(state.search, onSearch, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            placeholder = { Text("Search your music", style = MaterialTheme.typography.bodyMedium) }, singleLine = true,
            shape = RoundedCornerShape(8.dp), colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
            leadingIcon = { Icon(Icons.Rounded.Search, null) }, trailingIcon = {
                if (state.search.isNotEmpty()) ToolButton(Icons.Rounded.Close, "Clear search") { onSearch("") }
            })
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (state.search.isEmpty()) "TRACKS" else "SEARCH RESULTS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${state.page} / ${state.tracks.totalPages}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
            if (state.tracks.files.isEmpty() && !state.loading) item {
                EmptyState(Icons.Rounded.LibraryMusic, if (state.search.isNotBlank()) "No matching music" else "Your library is empty")
            }
            itemsIndexed(state.tracks.files, key = { _, track -> track.key }) { index, track ->
                TrackRow(track, active = playback.track?.key == track.key, enabled = playback.connected && !state.loading,
                    transcription = state.transcription(track), onClick = {
                    onPlay(index)
                }) {
                    trackActions(track, index)
                }
            }
        }
        if (state.tracks.totalPages > 1) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            ToolButton(Icons.AutoMirrored.Rounded.ArrowBack, "Previous page", enabled = !state.loading && state.page > 1) { onPage(state.page - 1) }
            Text("Page ${state.page}", style = MaterialTheme.typography.labelLarge)
            ToolButton(Icons.AutoMirrored.Rounded.ArrowForward, "Next page", enabled = !state.loading && state.page < state.tracks.totalPages) { onPage(state.page + 1) }
        }
    }
}

@Composable
fun LibraryBrowser(model: MusicViewModel, dismiss: () -> Unit) {
    val library = model.library.library
    var parent by remember { mutableStateOf<String?>(null) }
    var create by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight(0.85f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (parent != null) ToolButton(Icons.AutoMirrored.Rounded.ArrowBack, "Parent folder") { parent = library.entries.find { it.id == parent }?.parentId }
                Text(library.entries.find { it.id == parent }?.name ?: "Library", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 2)
                ToolButton(Icons.Rounded.CreateNewFolder, "Create folder") { create = true }
            }
            ListItem(headlineContent = { Text(if (parent == null) "All Music" else "All music in this folder") },
                supportingContent = { if (parent == null) Text("${library.songCount} tracks") }, leadingContent = { Icon(Icons.Rounded.LibraryMusic, null) },
                modifier = Modifier.clickable { model.selectLibrary(parent); dismiss() })
            HorizontalDivider()
            LazyColumn {
                items(library.entries.filter { it.parentId == parent }, key = { it.id }) { entry ->
                    val playlist = library.playlists.find { it.id == entry.id }
                    ListItem(headlineContent = { Text(playlist?.playlistTitle?.ifBlank { entry.name } ?: entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(if (entry.type == "folder") "Folder" else "${playlist?.songCount ?: 0} tracks") },
                        leadingContent = { Icon(if (entry.type == "folder") Icons.Rounded.Folder else Icons.AutoMirrored.Rounded.QueueMusic, null) },
                        trailingContent = { EntryMenu(model, entry) }, modifier = Modifier.clickable {
                            if (entry.type == "folder") parent = entry.id else { model.selectLibrary(entry.id); dismiss() }
                        })
                }
            }
        }
    }
    if (create) NameDialog("New folder", "", { create = false }) { name ->
        model.createFolder(name, parent)
        create = false
    }
}

@Composable
fun EntryMenu(model: MusicViewModel, entry: LibraryEntry) {
    var open by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    var move by remember { mutableStateOf(false) }
    Box {
        ToolButton(Icons.Rounded.MoreVert, "Options for ${entry.name}", enabled = !model.busy) { open = true }
        DropdownMenu(open, { open = false }) {
            if (entry.type == "folder") DropdownMenuItem(text = { Text("Rename") }, onClick = { open = false; rename = true }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
            DropdownMenuItem(text = { Text("Move to folder") }, onClick = { open = false; move = true }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.DriveFileMove, null) })
            if (entry.type == "folder") DropdownMenuItem(text = { Text("Remove folder") }, onClick = { open = false; remove = true }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) })
        }
    }
    if (rename) NameDialog("Rename folder", entry.name, { rename = false }) { model.folder(it, entry); rename = false }
    if (remove) ConfirmDialog("Remove folder?", "Contents will move to its parent folder.", { remove = false }) { model.folder(entry.name, entry, true); remove = false }
    if (move) DestinationDialog("Move to folder", listOf(null to "Library root") + model.library.library.entries
        .filter { it.type == "folder" && it.id != entry.id }.map { it.id to it.name }, { move = false }) { model.moveEntry(entry, it); move = false }
}

@Composable
fun TrackRow(track: Track, active: Boolean = false, enabled: Boolean = true, transcription: Transcription? = null,
    onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent)
        .clickable(enabled = enabled, onClick = onClick).heightIn(min = 78.dp).padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center) {
            Icon(if (active) Icons.Rounded.GraphicEq else if (track.mediaType == "video") Icons.Rounded.Movie else Icons.Rounded.MusicNote,
                null, Modifier.size(22.dp), tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(track.displayTitle, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            Text(track.displayArtist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TranscriptionStatus(transcription)
        }
        if (track.rating > 0) Row(Modifier.padding(horizontal = 4.dp).clearAndSetSemantics {
            contentDescription = "Rating: ${track.rating} out of 5"
        }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(Icons.Rounded.Star, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Text("${track.rating}/5", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        trailing()
    }
}

@Composable
fun ToolButton(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(), tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconButton(onClick = onClick, enabled = enabled) { Icon(icon, label) }
    }
}

@Composable
fun EmptyState(icon: ImageVector, text: String) {
    Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun NameDialog(title: String, initial: String, dismiss: () -> Unit, confirm: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = {
        OutlinedTextField(name, { name = it.take(200) }, modifier = Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
    }, confirmButton = { TextButton(onClick = { confirm(name.trim()) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
fun ConfirmDialog(title: String, message: String, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(message) },
        confirmButton = { TextButton(onClick = confirm) { Text("Confirm") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
fun DestinationDialog(title: String, options: List<Pair<String?, String>>, dismiss: () -> Unit, select: (String?) -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = {
        LazyColumn(Modifier.heightIn(max = 360.dp)) {
            items(options, key = { it.first ?: "root" }) { (id, name) -> ListItem(headlineContent = { Text(name) }, modifier = Modifier.clickable { select(id) }) }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}