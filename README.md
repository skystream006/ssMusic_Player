# ssMusic Player for Android

A native Kotlin / Jetpack Compose client for the ssYTDLP server in the parent
repository. Android 8.0 (API 26) or newer; targets Android 16 (API 36). The server
still owns media storage, yt-dlp downloads, transcoding, transcription, and
backups. This is not a WebView wrapper or a replacement server.

## Build and Install

Open this folder as a project in Android Studio, select a Java 17 Gradle JDK,
install Android SDK Platform 36, and let Gradle sync. Alternatively, with Java 17
on `JAVA_HOME` and the SDK on `ANDROID_HOME`:

Also install JDK 21 for app unit tests: the bundled AVIF decoder contains Java 21
bytecode. Gradle runs those tests on JDK 21 while compilation still targets Java 17.

```powershell
cd ssMusicPlayer
.\gradlew.bat :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

On macOS/Linux use `sh ./gradlew` with the same tasks. The checked-in Gradle 8.13
wrapper verifies its distribution's SHA-256 checksum.

Build from a full Git checkout (`git fetch --unshallow` if needed). As in ssMusic,
versions advance with first-parent commits: version name `1.0.<commit count>` and
version code `<commit count> + 1`. Rebuilding the same commit keeps its version.

On Windows, install or update the JDKs with WinGet:

```powershell
winget install --id EclipseAdoptium.Temurin.26.JDK --exact --source winget
winget install --id EclipseAdoptium.Temurin.17.JDK --exact --source winget
winget install --id EclipseAdoptium.Temurin.21.JDK --exact --source winget
```

Keep `JAVA_HOME` and Android Studio's **Settings > Build, Execution, Deployment >
Build Tools > Gradle > Gradle JDK** pointed at JDK 26, not the Java 17 toolchain.
Restart Android Studio and terminals after changing environment variables.

The installable development APK is `app/build/outputs/apk/debug/app-debug.apk`.
Install it through Android Studio or, with a connected test device:

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug build is signed with the repository's checked-in debug key
(`app/debug.keystore`), so every debug build — local, PR/CI, or otherwise —
shares the same signing certificate. `applicationId` is `com.ssytdlp.app`.
Changing it also requires updating the server's fixed app callback contract.

### APK updates

Like ssMusic, the player checks GitHub's latest release at startup and notifies
you when a newer version is available. Open **Updates and diagnostics** on the
server-setup or sign-in screen, or **Settings > App updates** after sign-in.
Select **Check for updates**, then **Download and install** and confirm the
download. No server connection or sign-in is required for updates.

The download shows progress and can be cancelled. The app checks the package,
version and signing certificate before handing the APK to Android. If prompted,
allow **Install unknown apps** for ssMusic Player, then return to the app to
confirm installation. Android still asks for final approval; a compatible update
preserves local settings and sign-in. If the process is killed, check and download
again rather than trusting an unfinished download.

For the first installation, download `ssMusic-Player-v<version>.apk` from this
repository's **Releases** and open it on the phone. Extract Actions artifact ZIPs
before opening an APK. On Samsung devices, device policy or Auto Blocker may
prevent sideloading even when the APK is valid; follow the phone's installation
message and your device administrator's policy.

### Publishing update-compatible releases

Run **Actions > Manual Android Release > Run workflow** to build latest `main`,
verify the signed APK, and publish one universal APK as the latest `v1.0.N`
release. The in-app updater reads this repository's latest release, not Actions
artifacts. Re-running a published version leaves its release unchanged; publish
a newer commit for a new update.

After a PR merges into `main`, **PR Release Reminder** posts a comment linking
to **Manual Android Release**. Follow the link and select **Run workflow** on
`main` when ready; the reminder does not start a release. Releases build the
latest `main`, which may include merges made after the reminder. Closed,
unmerged PRs receive no reminder, and re-running the reminder workflow does
not post a duplicate comment.

Like ssMusic, this project bundles a debug signing key
(`app/debug.keystore`, checked into the repository) that the release workflow
uses by default. Because the same key signs debug CI builds, manual releases,
and local `assembleDebug`/`assembleRelease` builds, any of those APKs can
update any other with no signing secrets to configure.

For a stronger production key instead of the bundled debug key, configure these
optional repository Actions secrets before running the release workflow:

| Secret | Value |
| --- | --- |
| `APK_SIGNING_KEYSTORE_BASE64` | Base64-encoded keystore containing the permanent app signing key |
| `APK_SIGNING_STORE_PASSWORD` | Keystore password |
| `APK_SIGNING_KEY_ALIAS` | Signing key alias |
| `APK_SIGNING_KEY_PASSWORD` | Signing key password |

Configure all four together, or leave all four unset to use the bundled debug
key; a partial set fails the workflow. Use the key that signed your existing
distributed APK, and keep a secure backup. Never commit a distribution
keystore or its passwords. The workflow verifies the APK signature before
uploading or publishing. Local release builds use the same configuration
through `APK_SIGNING_STORE_FILE` and the three password/alias environment
variables above; unset `APK_SIGNING_STORE_FILE` to build with the bundled
debug key instead.

Switching between the bundled debug key and a distribution key changes the
app's signing certificate. If an update reports a signature mismatch, obtain an
APK signed with the same key as the currently installed app; do not uninstall
to bypass it, as that removes local app data. Increasing the version alone
cannot fix a signing-key mismatch.

## Connect and Sign In

1. Start the existing ssYTDLP server. Its API and `/app-login` must be available
   on the same HTTPS origin, without an additional reverse-proxy login wall.
2. On first launch, enter your server's hostname (for example
   `music.example.com`) on the **Set up your server** screen. A bare hostname is
   promoted to an HTTPS origin automatically; you can also type a full
   `https://host[:port]` address. The value is validated and saved on-device;
   there is no build-time or bundled server address. Use **Change** on the
   sign-in screen to reset it and enter a different server.
3. Select **Sign in with passkey**. A browser Custom Tab opens the existing
   server login page and performs a fresh passkey confirmation. Registration and
   approval work exactly as in the web application.
4. The browser returns to the app. If automatic navigation is blocked, use
   **Return to app** on the server page. Closing the browser does not sign in;
   **Cancel sign-in** in the app discards the pending request.

The configured server address is stored only on the device that entered it;
only this one value is used by passkey sign-in. Rebuilding the app is never
required to change servers. Stored sessions and unfinished logins for other
origins are cleared automatically if the configured server changes.
The server's secondary web login remains available and unchanged.

Both Android and the browser must resolve the hostname and trust its HTTPS
certificate. The app accepts the system trust store and explicitly installed
user CA certificates to support private self-hosted servers. A private server
certificate must have the correct hostname/SAN and a usable trust chain. Never
bypass certificate validation. Nonstandard HTTPS ports are supported. An Android
IP such as `10.0.2.2` is not a valid passkey RP hostname; configure DNS instead.

No Digital Asset Links configuration or Android passkey registration is needed:
the existing web-origin passkey runs in the external browser, not in a WebView
or a different native credential namespace. A compatible browser and credential
provider with access to the existing passkey are required.

### Authentication Details

- Independent 32-byte random state and verifier values, S256 PKCE, and the exact
  callback `com.ssytdlp.app:/oauth/callback`.
- The verifier, state, original server, and timestamp survive process recreation
  in AES-GCM-encrypted private preferences using an Android Keystore key.
- Strict callback scheme, authority, raw path, fragment, state, age, duplicate
  parameter, and authorization-code checks. Valid callbacks consume the pending
  request before exchange; duplicate/replayed callbacks are not exchanged again.
- Code exchange only at the saved server's `/api/auth/app/token`. The verifier
  never goes in a browser URL. There is no password, PAT shortcut, or embedded
  browser login.
- Bearer sessions are encrypted, excluded from cloud/device-transfer backups,
  and never placed in URLs, log output, media metadata, or browser cookies.
- API requests, streams, imports, and exports use the same authenticated client.
  It refuses cross-origin resources, cleartext URLs, and HTTP redirects.
- Expired sessions and HTTP 401 clear the account and stop playback. There is no
  refresh token. Sign out revokes only the app session; it does not clear the
  independent browser login. Offline sign-out clears this device even if server
  revocation cannot finish.

The custom URI scheme can be claimed by other installed apps. PKCE prevents
another app from exchanging an intercepted code without the original verifier;
it does not prove an installed app's identity. Authorize only sign-ins you start.

## Native Features

| Area | Implemented |
| --- | --- |
| Library | All Music, server-side search and 50-track pages, folder navigation, playlist counts, linked tracks, cached 192px AVIF audio/video thumbnails in Library and Queue (including Android 8+, with legacy JPEG/PNG/WebP support) |
| Organization | Create/rename/remove/move folders, move playlists, add/move/remove song memberships, reorder complete unfiltered playlists |
| Playback | Authenticated audio/video streams, persistent mini-player, full player, seek, next/previous, queue editing, shuffle, repeat, instrumental companions |
| Lyrics and tags | Album artwork, expandable full-screen SYLT/USLT lyrics, timestamp-highlighted seekable SYLT lyrics, plain USLT lyrics, editing both lyric formats and MP3 tags/ratings, transcription requests and locking |
| Jobs | Add audio/video YouTube jobs, metadata-only jobs, status polling, search, owner filter, rerun, rename, delete, contributors, add existing files to playlists, save ZIPs |
| Import | Android document picker for media files or iTunes XML + ZIP; server-local XML + ZIP imports; server upload limits enforced while streaming |
| Backup | Android/iTunes exports, progress, download latest ZIP, daily/weekly UTC schedules |
| Settings | Updates at the top, Blue Wave appearance, edge-lighting toggle, optional server-synced theme and light/dark mode, account expiry, server media count, collapsible device/debugging sections, browser account/admin access |

Drag the bottom mini-player's progress slider to seek without leaving Library.
Artwork, song information, and playback controls stay on one row even on narrow
foldable cover screens; long titles and artist names scroll automatically.
Its previous, play/pause, next, and repeat controls work without opening Now Playing.
Swipe left across the mini-player's song row for the next song, or right for the
previous song. Taps still open Now Playing or use the playback buttons; dragging
the separate progress slider only seeks within the song.
The shuffle button beside the mini-player's progress slider toggles random queue
playback without changing the current song. Shuffle and repeat are also available
in Now Playing. Tap repeat once from off to repeat the current song (the **1**
icon), again to repeat the entire queue, and again to turn repeat off.
Now Playing's three-dot **More song actions** menu offers **Edit metadata** and **Transcribe lyrics** for MP3
files you can edit, plus **Save file** to download the current song to a location
chosen with Android's document picker. **Transcribe lyrics** opens the same
transcription options as the Library menu, including no-vocals generation for
locked songs, and is disabled while the service is inactive or an action is busy.
These actions remain available on the Player, Lyrics, and Queue tabs.
The mini-player has the same **More song actions** menu beside its progress slider,
including Replace File, Share Media, and read-only metadata for shared accounts
where permitted, without opening Now Playing or changing playback.
Track ratings remain visible even at zero; tap the star beside a track's menu
or queue controls to open **Rate song**. Saving uses the existing MP3 editing
permissions. Tap the selected star again to clear a rating, then **Save**.

Shared accounts can choose **View song metadata** (the info icon) from a Library
song's options or the Now Playing three-dot menu. For audio in their granted libraries,
including non-MP3 and `[NoVocals]/` files, the dialog displays title, artist, album,
genre, year, artwork, rating, and transcription-lock status read-only. It has a
**Close** button and no save, rating, artwork, or lock editing controls. Tapping a
track's rating also shows its value read-only; existing MP3 editing permissions
remain unchanged, and the server still enforces library grants.

Choose **Replace File** from a Library song's options or the Now Playing three-dot menu
to upload one non-empty audio file in the same format, up to 512 MB. Selecting
a file does not upload it: confirm **Replace File** to overwrite the song.
Convert other formats first; renaming the extension does not convert the audio.
This requires a server with the replacement API and job owner, contributor, or
administrator permission; Shared accounts and active jobs cannot use it.

Replacement overwrites audio and embedded tags, artwork, ratings, and lyrics
in every linked playlist. The server filename, playlist membership/order,
transcription lock, and other files (including NoVocals versions) stay intact.
Library and queued copies refresh, old transcription status is cleared, and
the current song reloads from the beginning without resuming paused playback.
Keep the app open during upload. If interrupted, refresh before retrying:
the server may already have completed the replacement.

**Transcribe lyrics** opens the server-compatible **Transcribe Song** options:
auto-detect or a supported language, Multilingual, a no-vocals karaoke version,
Viet Lyrics Fallback (locks the language to Vietnamese), and optional supplied
lyrics in Prompt, Align (default), or Correct mode. All toggles start off; supplied lyrics
must be nonblank and at most 100,000 characters.
**Generate NoVocals Only** hides those options and sends the boolean
`NoVocalsOnly: true` without language or lyrics fields. It generates an
accompaniment without transcribing or replacing existing lyrics.

For non-Shared accounts, transcription is enabled only when the server reports
the service as Active. Hover or long-press the disabled action for the explanation;
use the app's Refresh button after the service becomes available. Shared accounts
remain read-only and do not query the health endpoint.

The lock/unlock button at the top right of **Transcribe Song** hides all transcription
options when changing the lock; confirm **Lock transcription** or **Unlock transcription**
to save without sending a transcription request. Locked songs retain **Transcribe lyrics**
and offer **Generate NoVocals Only**, preserving the lock unless you explicitly unlock.
You can also change the lock in **Edit song / rating**, then **Save**. Locking does not
prevent manual lyric editing.

While viewing lyrics, permitted MP3 editors can choose **Edit lyrics** in the
**Now Playing** bar's three-dot menu to edit both SYLT timestamps/text and USLT text, including
clearing either format. This action is hidden on the Player and Queue tabs.
On all screen sizes, **More song actions** groups editing, transcription, Replace File,
Share Media, and Save file without crowding the header.
Saving updates the displayed lyrics; canceling leaves them unchanged.

Library and queue tracks show an icon-only transcription status to the left of
the rating: request sent, AI transcription, Lyrics included, failed, interrupted,
or unknown (an unrecognized server status). Tracks without a transcription record
show no status. AI transcription and Lyrics included share the subtitles icon.
The request-sent icon spins while the request is pending, including in Now Playing.
Tap the icon for the status label, request/finish times, saved language and options, and any error;
tap X or outside the popup to close it. Supplied lyrics are never shown in the
popup. Status is read from each server track and refreshes every 10 seconds
while Library or Now Playing is visible, including queued songs from other pages,
and after a transcription request.

Library mutations use the server's compact, version-checked endpoints. Conflicts
refresh the library and require retrying the intended action. Ownership and
contributor restrictions are preserved; the server remains authoritative.
Removing a last song link can delete the physical file, and deleting a job
deletes it for all users. Both require confirmation. When removing a link deletes
the file, the completion message names the deleted song.

Playback queues initially contain the displayed page; **Add to queue** can add
tracks from other pages. Browsing another page or tab does not replace playback.
The selected playlist or folder, queue order, current song and playback position,
shuffle, and repeat are saved on this device. Reopening after the app has stopped
restores the queue paused; press Play to continue from the saved timestamp.
Position is checkpointed every 30 seconds while playing and on seeks, pauses, queue
changes, leaving the foreground, and service shutdown. Reopening while background playback continues does
not interrupt it. Sign-out, session expiry, or switching accounts/servers clears
the saved state; a removed playlist falls back to All Music.

When no app activity is resumed (including screen lock), library, job, health,
settings, and update checks stop. Non-playback reads are canceled and deferred
until the app resumes; new mutations wait for the foreground. In-flight mutations
and imports are canceled on backgrounding, never replayed, and report that server
changes may already have completed; refresh before retrying. Song streams and song information remain available
for background playback. UI position polling, edge animation, and PCM metering
stop in the background; metering also stays off when edge lighting is disabled
and no audio visualizer is displayed.
These changes reduce background network traffic, CPU work, and storage writes
without stopping the playback service.
Tap the mini-player's artwork or song information to expand the dedicated
**Now Playing** page from the mini-player. The toolbar or system **Back** button
collapses it back to **Library**. The transition respects Android's animation
duration setting. There is no bottom navigation bar in portrait or landscape.
The Library top bar contains a content-width playlist/folder selector and a
**Search** icon. Tap Search to expand and focus the input; closing search clears
the filter. There is no top-bar play button; tap a song to play the page.
On each library page, `[NoVocals]/` tracks appear after originals in a collapsed
**[NoVocals]** section. Expanding it keeps playback and track actions associated
with the original page indices.
Choose **Edit playlist** from a playlist's three-dot menu to change its name,
privacy, or folder location. Owners and administrators can rename ordinary
playlists; only owners can change privacy. Contributors can change location in
their own library, but cannot rename or change privacy. Protected Individual
Songs/Videos keep their fixed names. Shared accounts cannot edit, and active
downloads must finish first. Changes are applied only with **Save changes**;
sharing is disabled until edits are saved or discarded. Saves use separate server
requests: if one fails, the dialog stays open with an error and reloads the
library before retrying, since earlier changes may already have succeeded.
In the playlist/folder browser, open a playlist's three-dot menu and choose
**Share Playlist**, then **Generate public link** and **Copy link**. Owners,
contributors, and administrators can share non-private playlists; shared accounts
cannot. Anyone with the link can listen, read lyrics and metadata, and download
eligible public audio without signing in, but cannot edit it. Private and
unshareable songs are excluded, and the link reflects current playlist contents.
This requires a server with playlist-sharing support.
Open **Settings** from the upper-right gear, to the right of **Refresh**, and
select **Jobs** there to add downloads or manage server jobs.
In **Now Playing**, the Player, Lyrics, and Queue tabs retain all playback controls.
The header shows the current song name on every tab, with its playlist or queue
underneath; long song names scroll without crowding the three-dot menu.
On the audio **Player** tab, use the **Audio visualizer** switch to replace album
artwork with visuals driven by the decoded music. While enabled, the dropdown
offers **Waveform**, **Amplitude bars**, and **Radial pulse**. Switching back
restores the artwork and hides the dropdown. Your choices are saved on this device
across navigation, song changes, rotation, and app restarts; video playback keeps
its video display.
Visuals stop animating when paused, buffering, or in the background, and respect
disabled system animations. They work independently of edge lighting, require no
microphone permission, and do not change the sound. Outputs that bypass decoded
PCM processing show a stationary visual instead.
On the full-size **Player** tab, swipe left for the next song or right for the previous song;
the artwork or visualizer follows your finger and settles back when the gesture ends.
Lyrics and Queue gestures never skip songs. When both lyric sources are available,
tap the selected **Lyrics** tab again to alternate between **SYLT** (synchronized)
and **USLT** (plain) lyrics. Each new song starts with SYLT when available.
Tap the **Expand lyrics to full screen** overlay in the upper-right corner of the
lyrics section to hide the app navigation and playback controls while reading.
The overlay does not reserve a separate toolbar row.
Use the **SYLT/USLT** button to switch available sources; synchronized
lyrics still follow playback and support tap-to-seek. Tap **Exit full screen lyrics**
or press Android Back to return to the Lyrics tab without interrupting playback.
Pinch in or out on either lyrics display to resize the text (75%–200%).
The size is remembered on this device across songs, tab changes, and app restarts.
The playback position slider still seeks within the current song.
Organization remains shared with the web client; selecting **Server theme** also
uses the web client's saved colors and light/dark preference. Advanced server
administration and passkey enrollment use the existing browser settings UI;
they may require a separate browser passkey login.

There is no offline library/cache, whole-device audio scan, casting, Android Auto
library browser, automatic playback after process death, or automatic background
upload/download retry. Saved files and export ZIPs go to the document location
the user chooses, not an app-managed offline library. Media uses device-supported
Media3 codecs. Imports and file saves should stay in the foreground; rotation
is supported, process termination cancels them. Canceling a request does not
guarantee cancellation of a server mutation already in progress.

## Blue Wave Appearance

The Android app defaults to the reference-inspired Blue Wave appearance: near-black
surfaces, cyan controls, original blue-ribbon bitmap artwork, Space Grotesk headings,
and Manrope body text. Real album covers remain unchanged. **Settings > Appearance**
can switch back to **Server theme** without overwriting the web client's preferences.
The choice is stored on this device. Authentication still uses the primary server.
Server theme choices are Midnight blue, Royal purple, Gold, Green, Pink, and Black.
Porcelain is no longer available; saved Porcelain selections use Green in the app
without changing light/dark mode. The server preference is updated only when you
change the theme or mode.
The server's **Black** theme uses neutral charcoal surfaces with yellow accents in
dark mode, and gray/white surfaces with darker gold accents in light mode.

**Settings > Appearance > Skins** adds optional illustrated backgrounds alongside
the color themes. Skins are off by default. The selected skin name appears beside
the toggle, even when skins are off. Turn skins on and tap the name to open a
scrollable preview dialog, then select a skin to apply it and close the dialog:

- **Cherry Blossom Sunset** — flowing blossoms and windblown petals over a sunset.
- **Starry City Sunset** — a star-filled sky above a city skyline and sunset.
- **Ocean Wave** — a whale, dolphin, and anchovies swimming through a wave at sunset.
- **Ocean Moonlight** — a bright moon above the ocean horizon, reflected on the water.
- **Galaxy** — three different galaxies amid a field of stars.
- **Tropical** — a coconut palm in front of a beach sunset.
- **Mechanics** — interlocking gears and metal mechanisms.

All are original bundled vector images, available offline without downloads.
The toggle and selected skin are remembered on this device; turning skins off
restores the normal background without forgetting the selection. Skins work with
Blue Wave and every server color theme in light or dark mode, without changing
server preferences or album covers. A theme-colored overlay keeps text readable.

**Settings > Appearance > Edge lighting** is enabled by default unless previously
turned off; saved preferences are preserved. When enabled, it
expands alphabetized, horizontal choices that wrap on smaller screens:
**Audio waveform** (a border shaped by decoded PCM audio samples),
**Circulating** (a smooth two-highlight gradient completing a lap every 12 seconds),
**Circulating Waveform** (the circulating highlights along an audio waveform border),
**Oscillation** (audio-reactive ripples), and **Vibration** (a rapidly pulsing
border, not phone vibration). **Circulating Waveform** is the default style;
previously saved style choices are preserved. The switch and selected style
are remembered on this device, including when lighting is turned off.

While music is playing, the selected effect follows the app and Now Playing page
edges in the current theme's accent color. It does not change the sound or record
the microphone. Lighting stops when paused, buffering, or in the background;
disabling system animations leaves a stationary glow. Audio outputs that bypass
PCM processing still show a glow; the two circulating styles retain moving highlights.

In Settings, **Jobs** sits directly below **Appearance**. App update buttons are
arranged side by side. **Library backup** and **Server** start collapsed and can
be expanded by tapping their headings.
Shared accounts hide **Jobs** and **Library backup** using the server's user role
from app sign-in and `/api/auth/me`. User details refresh at startup and while
Settings is open; Shared accounts do not poll backup or health endpoints.

Device settings start collapsed when notifications are allowed and battery use is
unrestricted, and refresh when returning from Android settings. Debug logging
starts collapsed unless enabled. Either section can be expanded manually.

Artwork and fonts ship in the APK and work offline. Both fonts use the SIL Open Font
License; their notices are bundled under `app/src/main/assets/font-licenses`.
The original ribbon assets can be regenerated on Windows with PowerShell 7.6:

```powershell
./scripts/render-wave-assets.ps1
```

Native UI tests render login, library, and player previews into
`app/build/outputs/ui-previews`. They include an 800dp tablet and a 320dp phone
at 150% text size, and check visible artwork, text, and control bounds. The previews
use sample track metadata, not account data.

## Song and Playlist Privacy

Owners can choose **Make song private** / **Make song public** from song options
in Library, Now Playing, or the mini-player. Playlist options in the library
browser provide **Make playlist private** / **Make playlist public**, including
Individual Songs and Individual Videos. Contributors, shared accounts, and other
administrators cannot change privacy.

Private source playlists restrict their songs too; inherited privacy must be
changed on the source playlist or original song. Making a playlist public again
does not clear individually private songs. The Individual Songs/Videos setting
only hides that library playlist, not its source files. Private songs cannot
generate public share links, and existing links stop working while the source is
private. Previously downloaded copies cannot be recalled. Requires a server with
privacy support (ssMusic_Server PR #21).

## Permissions and Background Playback

| Permission | Purpose |
| --- | --- |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Server API and streaming |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Media3 foreground playback service |
| `WAKE_LOCK` | Keep CPU/network active only while Media3 needs them for playback |
| `POST_NOTIFICATIONS` | Requested on the first playback action on Android 13+ |

Notification denial does not block music. Media-session notifications are exempt
from Android's general notification permission requirement; the runtime request
also gives users an explicit notification choice. Settings links to the Android
notification page after denial.

The player lives in `MediaSessionService`, not the activity. Media3 supplies
foreground/media notifications, lock-screen and Bluetooth/headset transport
controls, audio focus, and pause-on-headphone-disconnect behavior. Music continues
when navigating, locking the screen, or dismissing the activity while playing.
Pause/stop, sign-out, Android's explicit **Stop** action, force-stop, and process
termination are respected. Hardware volume keys adjust media volume.

There is no separate Android runtime permission named "background play". The
declared `mediaPlayback` foreground-service type is the supported mechanism.
The app does not request battery-optimization exemption, microphone, contacts,
Bluetooth scanning, broad storage access, or `READ_MEDIA_AUDIO` because it neither
records nor scans the device library. The Storage Access Framework grants access
only to explicitly selected documents. Some manufacturers add battery controls;
the app links to Android app settings without automatically requesting unrestricted
battery access.

The exported media service accepts only its own app and Android-trusted media
controllers. Queue insertion is restricted to the app and resource URLs are
validated again inside the service. Lint's exported-service and user-CA warnings
are deliberate, reviewed self-hosted media-app tradeoffs, not disabled TLS checks.

## Metadata artwork performance

Now Playing and the mini-player still use the image embedded in the song metadata,
not the Library/Queue AVIF thumbnail. Decoded images are shared in a memory-only
16 MiB cache (including retained Base64 keys), with bounded concurrent work.
The requested resolution follows the view's pixel size, capped at the existing
1024-pixel limit. Larger cached images can serve smaller views; opening the full
Player upgrades a small cached image when necessary. Switching tabs no longer
requires decoding an already cached image again.

Recent typed metadata is retained for up to five minutes in a 16-entry,
8 MiB estimated-size cache, keyed by account/session and song/artwork revisions.
After the current metadata is ready and buffering ends, a foreground-only,
350 ms delayed request can warm the next song's metadata. It never walks the
whole queue, skips shuffle and repeat-one, and cancels when the queue, account,
current song, or foreground state changes. Edits, replacements, and observed
artwork/transcription revisions invalidate affected metadata.

Artwork caches are cleared before a new account/session is published. Changed
image content has a different cache key, and eviction never recycles images that
may still be displayed. Existing encoded-size and AVIF allocation limits remain
in effect.

To compare loading stages, enable **Settings > Debug logging > Full**, clear the
log, and separately try a first play, returning to a recently played song, and
opening the full Player from the mini-player. Logs contain only event labels,
timestamps, and numeric durations (`duration_us`), never song names, artwork,
URLs, or credentials:

- `METADATA_DOWNLOAD`: request start through complete response-body download.
- `METADATA_JSON` / `METADATA_CONVERTED`: JSON parsing / typed metadata conversion.
- `METADATA_CACHE_HIT`: metadata reused without another request.
- `ARTWORK_DECODED`: Base64 conversion and image decoding.
- `ARTWORK_CACHE_HIT`: an existing decoded image reused.
- `ARTWORK_DISPLAYED`: artwork input becoming available in the view through its
  first draw (including decode waiting), not a hardware presentation timestamp.

Compare equivalent songs/devices; metadata prefetch can also emit request timings.
No fixed latency improvement is assumed. If uncached `METADATA_DOWNLOAD` dominates,
a separate binary endpoint for the embedded metadata image would avoid the
Base64/lyrics payload overhead. That requires investigation in the server
repository; this client does not invent an endpoint or replace metadata artwork
with thumbnails.

## Verification

```powershell
.\gradlew.bat :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
.\gradlew.bat :app:assembleDebugAndroidTest
# Requires a connected emulator/device:
.\gradlew.bat :app:connectedDebugAndroidTest
```

Local tests cover RFC 7636 PKCE, callback tampering/replay prerequisites, URL
boundaries, response models, HTTPS bearer/exchange/logout behavior, expiry/401,
redirect rejection, binary downloads, narrow-screen track actions, stable
playlist identity, and declared permissions. Native Compose tests use Robolectric.
The editable-dialog test is an instrumentation test: Windows Robolectric stalled
in dialog text-layout idling, so that case needs a real Android runtime.

Before distributing, validate on Android 13 and Android 15/16, plus the oldest
Android version you support:

- Existing passkey login, cancellation, registration/approval, app process death
  while the browser is open, duplicate/invalid callbacks, logout and expiry.
- Notification allow/deny, locked-screen playback, activity dismissal, headset
  unplug, Bluetooth controls, audio-focus interruption, and Android's Stop action.
- Narrow screens, large fonts, rotation, editable dialogs, video rendering,
  seeking, lyrics, queue changes, and real album artwork.
- Imports, expired document grants, canceled transfers, large exports, library
  version conflicts, permissions for contributors, and private-CA trust.

No physical device/emulator was connected during initial implementation; real
passkey-provider behavior, foreground-service behavior on devices, and the
instrumentation test still need that verification.