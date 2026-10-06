@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.ui.text.ExperimentalTextApi::class)

package com.ssytdlp.app

import android.content.Intent
import android.content.res.Configuration
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
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
internal fun isLandscape(): Boolean =
    LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

@Composable
fun MusicTheme(preferences: Preferences = Preferences(), waveAppearance: Boolean = true,
    skin: AppSkin? = null, content: @Composable () -> Unit) {
    val theme = preferences.effectiveTheme
    val accent = when (theme) {
        "midnight" -> Color(0xFF5F7FF0)
        "royal-purple" -> Color(0xFF7139C6)
        "gold" -> Color(0xFF8C6600)
        "pink" -> Color(0xFFBF3D78)
        else -> Color(0xFF227452)
    }
    val dark = preferences.mode == "dark" || (preferences.mode == null && theme in listOf("midnight", "black"))
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
    ) else if (theme == "black") serverBlackColorScheme(dark)
    else if (dark) darkColorScheme(primary = accent.copy(alpha = 1f), secondary = Color(0xFFF3B6AA),
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
    CompositionLocalProvider(LocalWaveAppearance provides waveAppearance, LocalAppSkin provides skin) {
        MaterialTheme(colorScheme = colors, typography = typography,
            shapes = Shapes(extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(6.dp),
                medium = RoundedCornerShape(8.dp), large = RoundedCornerShape(8.dp), extraLarge = RoundedCornerShape(8.dp)),
            content = content)
    }
}

private fun serverBlackColorScheme(dark: Boolean): ColorScheme {
    val accent = if (dark) Color(0xFFF5D442) else Color(0xFF806000)
    val paper = if (dark) Color(0xFF0E0E0E) else Color(0xFFF5F5F5)
    val surface = if (dark) Color(0xFF181818) else Color.White
    val raised = if (dark) Color(0xFF262626) else Color(0xFFEAEAEA)
    val field = if (dark) Color(0xFF141414) else Color(0xFFFAFAFA)
    val ink = if (dark) Color(0xFFF0F0EE) else Color(0xFF202124)
    val muted = if (dark) Color(0xFFADADAD) else Color(0xFF686868)
    val line = if (dark) Color(0xFF373737) else Color(0xFFD8D8D8)
    val onAccent = if (dark) Color(0xFF141414) else Color.White
    val danger = if (dark) Color(0xFFFF9AAB) else Color(0xFFB03250)
    val selection = lerp(surface, accent, 0.12f)
    return (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = accent, onPrimary = onAccent,
        primaryContainer = selection, onPrimaryContainer = accent, inversePrimary = if (dark) Color(0xFF806000) else Color(0xFFF5D442),
        secondary = accent, onSecondary = onAccent,
        secondaryContainer = selection, onSecondaryContainer = accent,
        tertiary = accent, onTertiary = onAccent,
        tertiaryContainer = selection, onTertiaryContainer = accent,
        background = paper, onBackground = ink,
        surface = surface, onSurface = ink, surfaceTint = accent,
        surfaceDim = if (dark) paper else raised, surfaceBright = if (dark) raised else surface,
        surfaceContainerLowest = paper, surfaceContainerLow = field,
        surfaceContainer = surface, surfaceContainerHigh = raised, surfaceContainerHighest = raised,
        surfaceVariant = raised, onSurfaceVariant = muted,
        inverseSurface = if (dark) Color(0xFFF5F5F5) else Color(0xFF181818),
        inverseOnSurface = if (dark) Color(0xFF202124) else Color(0xFFF0F0EE),
        outline = line, outlineVariant = line,
        error = danger, onError = onAccent,
        errorContainer = lerp(surface, danger, 0.09f), onErrorContainer = danger
    )
}

@Composable
fun MusicApp(model: MusicViewModel, requestNotifications: () -> Unit) {
    UpdateNotification()
    val landscape = isLandscape()
    val serverOrigin by model.serverOrigin.collectAsStateWithLifecycle()
    val account by model.sessions.account.collectAsStateWithLifecycle()
    val playback by model.playback.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var screen by rememberSaveable { mutableIntStateOf(0) }
    var settingsReturnScreen by rememberSaveable { mutableIntStateOf(0) }
    var libraryBrowser by rememberSaveable(screen) { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(account?.user?.isShared, screen) {
        if (account?.user?.isShared == true && screen == 3) screen = 2
    }
    LaunchedEffect(lifecycle, account, screen) {
        if (account != null && screen in 0..1) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
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
    val openSettings: () -> Unit = {
        if (screen < 2) settingsReturnScreen = screen
        screen = 2
    }
    val playLibrary: (Int) -> Unit = { index ->
        requestNotifications()
        model.playback.play(model.library.tracks.files, index)
    }
    CompositionLocalProvider(LocalMetadataArtworkOwner provides account?.let(::MetadataOwner)) {
      MusicTheme(model.preferences, waveAppearance = model.waveAppearance, skin = model.skin.takeIf { model.skinsEnabled }) {
        SystemBarAppearance()
        SkinBackground(Modifier.fillMaxSize()) {
            Scaffold(containerColor = appBackgroundColor(), contentColor = MaterialTheme.colorScheme.onBackground,
                snackbarHost = { SnackbarHost(snackbar) }, topBar = {
                if (account != null) {
                    if (screen == 0) LibraryTopBar(model.library, !model.busy,
                        onBrowse = { libraryBrowser = true }, onSearch = model::search,
                        onRefresh = model::refresh, onSettings = openSettings, showBrowser = !landscape)
                    else MusicTopBar(account!!.user.name, !model.busy, model::refresh,
                        onSettings = openSettings, onBack = back)
                }
            }, bottomBar = {
                if (account != null) {
                    if (landscape && playback.track != null && screen != 1) {
                        Row(Modifier.windowInsetsPadding(WindowInsets.navigationBars), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                PlayerDock(playback, model, { screen = 1 }, requestNotifications, download)
                            }
                            Column(Modifier.width(160.dp)) { MusicNavigation(screen) { screen = it } }
                        }
                    } else Column {
                        if (playback.track != null && screen != 1) PlayerDock(playback, model, { screen = 1 }, requestNotifications, download)
                        MusicNavigation(screen) { screen = it }
                    }
                }
            }) { padding ->
                when {
                    serverOrigin == null -> ServerSetupScreen(model, Modifier.padding(padding))
                    account == null -> LoginScreen(model, Modifier.padding(padding))
                    else -> Column(Modifier.padding(padding).fillMaxSize()) {
                        if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        when (screen) {
                            0 -> {
                                LibraryScreen(model, playback, playLibrary, download)
                                if (libraryBrowser && !landscape && model.playlistEditTarget == null) {
                                    LibraryBrowser(model) { libraryBrowser = false }
                                }
                            }
                            1 -> NowPlayingScreen(model, playback, download)
                            2 -> SettingsScreen(model, download, onJobs = { screen = 3 })
                            3 -> if (account?.user?.isShared != true) JobsScreen(model, requestNotifications, download)
                        }
                    }
                }
            }
            PlaybackEdgeLighting(account != null && playback.playing, Modifier.matchParentSize(),
                enabled = model.edgeLightingEnabled, style = model.edgeLightingStyle)
            PlaylistEditor(model)
        }
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
fun LibraryScreen(model: MusicViewModel, playback: PlaybackState, onPlay: (Int) -> Unit, download: (String, String) -> Unit) {
    val account by model.sessions.account.collectAsStateWithLifecycle()
    var ratingTrack by remember { mutableStateOf<Track?>(null) }
    val songs: @Composable () -> Unit = {
        LibraryContent(model.library, playback, onPlay = onPlay, onPage = model::page,
            artwork = { rememberTrackArtwork(it, model.api, account) },
            onRating = { ratingTrack = it }) { track, index -> TrackMenu(model, track, index, download) }
    }
    if (isLandscape()) Row(Modifier.fillMaxSize()) {
        LibraryBrowserPane(model, Modifier.weight(0.35f).fillMaxHeight().testTag("library-playlists-pane"))
        Box(Modifier.weight(0.65f).fillMaxHeight().testTag("library-songs-pane")) { songs() }
    } else songs()
    ratingTrack?.let { track -> MetadataDialog(model, track, ratingOnly = true) { ratingTrack = null } }
}

@Composable
fun LibraryContent(state: LibraryState, playback: PlaybackState, onPlay: (Int) -> Unit,
    onPage: (Int) -> Unit, onRating: (Track) -> Unit = {},
    artwork: @Composable (Track) -> ImageBitmap? = { null },
    trackActions: @Composable (Track, Int) -> Unit) {
    val groups = remember(state.tracks.files) {
        state.tracks.files.withIndex().partition { !it.value.name.startsWith("[NoVocals]/", ignoreCase = true) }
    }
    var showNoVocals by rememberSaveable(state.selectedId, state.search, state.page) { mutableStateOf(false) }
    val row: @Composable (IndexedValue<Track>) -> Unit = { (index, track) ->
        TrackRow(track.copy(rating = state.rating(track)), active = playback.track?.key == track.key,
            enabled = playback.connected && !state.loading, transcription = state.transcription(track),
            artwork = { artwork(track.copy(artworkUrl = state.artworkUrl(track))) },
            onRatingClick = { onRating(track) }, onClick = { onPlay(index) }) {
            trackActions(track, index)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (state.search.isEmpty()) "TRACKS" else "SEARCH RESULTS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${state.page} / ${state.tracks.totalPages}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
            if (state.tracks.files.isEmpty() && !state.loading) item {
                EmptyState(Icons.Rounded.LibraryMusic, if (state.search.isNotBlank()) "No matching music" else "Your library is empty")
            }
            items(groups.first, key = { it.value.key }) { row(it) }
            if (groups.second.isNotEmpty()) {
                item(key = "no-vocals-header") {
                    ListItem(headlineContent = { Text("[NoVocals] (${groups.second.size})") },
                        trailingContent = { Icon(if (showNoVocals) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            if (showNoVocals) "Collapse NoVocals" else "Expand NoVocals") },
                        modifier = Modifier.clickable(role = Role.Button) { showNoVocals = !showNoVocals })
                }
                if (showNoVocals) items(groups.second, key = { it.value.key }) { row(it) }
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
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LibraryBrowserPane(model, Modifier.fillMaxHeight(0.85f), dismiss)
    }
}

@Composable
private fun LibraryBrowserPane(model: MusicViewModel, modifier: Modifier = Modifier, onSelect: () -> Unit = {}) {
    val library = model.library.library
    var parent by rememberSaveable { mutableStateOf<String?>(null) }
    var create by remember { mutableStateOf(false) }
    LazyColumn(modifier) {
        item {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (parent != null) ToolButton(Icons.AutoMirrored.Rounded.ArrowBack, "Parent folder") { parent = library.entries.find { it.id == parent }?.parentId }
            Text(library.entries.find { it.id == parent }?.name ?: "Library", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 2)
            ToolButton(Icons.Rounded.CreateNewFolder, "Create folder") { create = true }
        }
        }
        item {
        ListItem(headlineContent = { Text(if (parent == null) "All Music" else "All music in this folder") },
            supportingContent = { if (parent == null) Text("${library.songCount} tracks") }, leadingContent = { Icon(Icons.Rounded.LibraryMusic, null) },
            modifier = Modifier.clickable { model.selectLibrary(parent); onSelect() })
        HorizontalDivider()
        }
        items(library.entries.filter { it.parentId == parent }, key = { it.id }) { entry ->
        val playlist = library.playlists.find { it.id == entry.id }
        ListItem(headlineContent = { Text(playlist?.playlistTitle?.ifBlank { entry.name } ?: entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(if (entry.type == "folder") "Folder"
                else "${playlist?.songCount ?: 0} tracks" + if (playlist?.isPrivate == true) " · Private" else "") },
            leadingContent = { Icon(if (entry.type == "folder") Icons.Rounded.Folder else Icons.AutoMirrored.Rounded.QueueMusic, null) },
            trailingContent = { EntryMenu(model, entry) }, modifier = Modifier.clickable {
                if (entry.type == "folder") parent = entry.id else { model.selectLibrary(entry.id); onSelect() }
            })
        }
    }
    if (create) NameDialog("New folder", "", { create = false }) { name ->
        model.createFolder(name, parent)
        create = false
    }
}

@Composable
fun EntryMenu(model: MusicViewModel, entry: LibraryEntry) {
    val account by model.sessions.account.collectAsStateWithLifecycle()
    val playlist = model.library.library.playlists.find { it.id == entry.id }
    var open by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    var move by remember { mutableStateOf(false) }
    var share by remember(entry.id, account?.origin, account?.user?.id, account?.session) { mutableStateOf(false) }
    val canEdit = entry.type == "playlist" && playlist != null &&
        account?.user?.let { !it.isShared && it.id.isNotBlank() } == true
    Box {
        ToolButton(Icons.Rounded.MoreVert, "Options for ${entry.name}", enabled = !model.busy) { open = true }
        DropdownMenu(open, { open = false }) {
            if (canEdit) DropdownMenuItem(text = { Text("Edit playlist") },
                leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                enabled = !model.busy && playlist?.active == false,
                onClick = { open = false; model.openPlaylistEditor(entry.id) })
            if (entry.type == "playlist" && playlist?.canChangePrivacy(account?.user) == true) {
                DropdownMenuItem(text = { Text(if (playlist.isPrivate) "Make playlist public" else "Make playlist private") },
                    leadingIcon = { Icon(if (playlist.isPrivate) Icons.Rounded.Lock else Icons.Rounded.LockOpen, null) },
                    enabled = !model.busy && !playlist.active, onClick = {
                        open = false
                        model.setPlaylistPrivate(playlist, !playlist.isPrivate)
                    })
            }
            if (entry.type == "playlist" && playlist?.canShare(account?.user) == true) {
                DropdownMenuItem(text = { Text("Share Playlist") },
                    leadingIcon = { Icon(Icons.Rounded.Share, null) },
                    enabled = !model.busy && !playlist.isPrivate && playlist.songCount > 0,
                    onClick = { open = false; share = true })
            }
            if (entry.type == "folder") DropdownMenuItem(text = { Text("Rename") }, onClick = { open = false; rename = true }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
            DropdownMenuItem(text = { Text("Move to folder") }, onClick = { open = false; move = true }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.DriveFileMove, null) })
            if (entry.type == "folder") DropdownMenuItem(text = { Text("Remove folder") }, onClick = { open = false; remove = true }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) })
        }
    }
    if (rename) NameDialog("Rename folder", entry.name, { rename = false }) { model.folder(it, entry); rename = false }
    if (remove) ConfirmDialog("Remove folder?", "Contents will move to its parent folder.", { remove = false }) { model.folder(entry.name, entry, true); remove = false }
    if (move) DestinationDialog("Move to folder", listOf(null to "Library root") + model.library.library.entries
        .filter { it.type == "folder" && it.id != entry.id }.map { it.id to it.name }, { move = false }) { model.moveEntry(entry, it); move = false }
    if (share && entry.type == "playlist" && playlist?.canShare(account?.user) == true) {
        SharePlaylistDialog(model, playlist) { share = false }
    }
}

@Composable
fun TrackRow(track: Track, active: Boolean = false, enabled: Boolean = true, transcription: Transcription? = null,
    artwork: @Composable () -> ImageBitmap? = { null },
    onRatingClick: () -> Unit = {}, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 240.dp
        Row(Modifier.fillMaxWidth().background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick).heightIn(min = 78.dp)
            .padding(start = if (compact) 8.dp else 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (!compact) TrackArtwork(track, active, artwork())
            Column(Modifier.weight(1f).padding(start = if (compact) 4.dp else 14.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(track.displayTitle, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                Text(track.displayArtist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TranscriptionStatus(transcription, iconOnly = true)
            Row(Modifier.clickable(role = Role.Button, onClickLabel = "Rate song", onClick = onRatingClick)
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp).padding(horizontal = 4.dp).clearAndSetSemantics {
                contentDescription = "Rating: ${track.rating} out of 5"
            }, verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
                Icon(if (track.rating > 0) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                if (!compact) Text("${track.rating}/5", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            trailing()
        }
    }
}

@Composable
fun ToolButton(icon: ImageVector, label: String, enabled: Boolean = true, modifier: Modifier = Modifier,
    onClick: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(), tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconButton(onClick = onClick, enabled = enabled, modifier = modifier) { Icon(icon, label) }
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