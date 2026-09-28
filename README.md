# ssMusic Player for Android

A native Kotlin / Jetpack Compose client for the ssYTDLP server in the parent
repository. Android 8.0 (API 26) or newer; targets Android 16 (API 36). The server
still owns media storage, yt-dlp downloads, transcoding, transcription, and
backups. This is not a WebView wrapper or a replacement server.

## Build and Install

Open this folder as a project in Android Studio, select a Java 17 Gradle JDK,
install Android SDK Platform 36, and let Gradle sync. Alternatively, with Java 17
on `JAVA_HOME` and the SDK on `ANDROID_HOME`:

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
```

Keep `JAVA_HOME` and Android Studio's **Settings > Build, Execution, Deployment >
Build Tools > Gradle > Gradle JDK** pointed at JDK 26, not the Java 17 toolchain.
Restart Android Studio and terminals after changing environment variables.

The installable development APK is `app/build/outputs/apk/debug/app-debug.apk`.
Install it through Android Studio or, with a connected test device:

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug build is signed with the local Android debug key. For distribution,
use Android Studio's **Generate Signed Bundle / APK** and your own signing key;
no release signing secrets are stored in this project. `applicationId` is
`com.ssytdlp.app`. Changing it also requires updating the server's fixed app
callback contract.

## Connect and Sign In

1. Start the existing ssYTDLP server. Its API and `/app-login` must be available
   on the same HTTPS origin, without an additional reverse-proxy login wall.
2. The app is bound to the primary `PASSKEY_RP_ID` and matching `PASSKEY_ORIGIN`
  at build time. With this repository's configuration it displays
  `https://buytdlp.duckdns.org`. The server address is read-only; secondary
  passkey settings are never used by the Android app.
3. Select **Sign in with passkey**. A browser Custom Tab opens the existing
   server login page and performs a fresh passkey confirmation. Registration and
   approval work exactly as in the web application.
4. The browser returns to the app. If automatic navigation is blocked, use
   **Return to app** on the server page. Closing the browser does not sign in;
   **Cancel sign-in** in the app discards the pending request.

The build reads the primary values from the parent `.env`. Gradle properties
(`-PPASSKEY_RP_ID=...`, `-PPASSKEY_ORIGIN=...`) or matching environment variables
can supply them for standalone/CI builds, in that precedence order. If the origin
is omitted, it defaults to `https://<PASSKEY_RP_ID>`. The origin's hostname must
match the primary RP ID, and only these two public values are embedded in the APK.
Rebuild after changing them. Stored sessions and unfinished logins for other
origins are cleared when the app starts, requiring a fresh primary passkey login.
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
| Library | All Music, server-side search and 50-track pages, folder navigation, playlist counts, linked tracks |
| Organization | Create/rename/remove/move folders, move playlists, add/move/remove song memberships, reorder complete unfiltered playlists |
| Playback | Authenticated audio/video streams, persistent mini-player, full player, seek, next/previous, queue editing, shuffle, repeat, instrumental companions |
| Lyrics and tags | Album artwork, timestamp-highlighted seekable SYLT lyrics, plain USLT lyrics, MP3 title/artist/album/genre/year/rating editing, transcription requests |
| Downloads | Add audio/video YouTube jobs, metadata-only jobs, status polling, search, owner filter, rerun, rename, delete, contributors, add existing files to playlists, save ZIPs |
| Import | Android document picker for media files or iTunes XML + ZIP; server-local XML + ZIP imports; server upload limits enforced while streaming |
| Backup | Android/iTunes exports, progress, download latest ZIP, daily/weekly UTC schedules |
| Settings | Blue Wave appearance, optional server-synced theme and light/dark mode, account expiry, server media count, notification/battery settings, browser account/admin access |

Library mutations use the server's compact, version-checked endpoints. Conflicts
refresh the library and require retrying the intended action. Ownership and
contributor restrictions are preserved; the server remains authoritative.
Removing a last song link can delete the physical file, and deleting a job
deletes it for all users. Both require confirmation.

Playback queues initially contain the displayed page; **Add to queue** can add
tracks from other pages. Browsing another page or tab does not replace playback.
Organization remains shared with the web client; selecting **Server theme** also
uses the web client's saved colors and light/dark preference. Advanced server
administration and passkey enrollment use the existing browser settings UI;
they may require a separate browser passkey login.

There is no offline library/cache, whole-device audio scan, casting, Android Auto
library browser, playback resumption after process death, or automatic background
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