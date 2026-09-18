# ssMusic Player

Android client for an HTTPS-hosted ssYTDLP Server. Configure the server URL from
the app, use **Open server** to complete browser-based server login, then refresh
the library. Tap a song to add it to the server queue or long-press and drag songs
to reorder that queue. Downloads are submitted through the server.

The native controls use the server's `/api/library`, `/api/queue`, and
`/api/download` endpoints; **Open server** exposes the complete server web UI for
server features not represented by the native controls.

## App updates

Like ssMusic, the player checks for a newer stable GitHub release on launch without
automatically downloading or installing it. Tap **Updates** to check manually,
download the newer release APK into app-private storage, and open Android's
installer. On Android 8+, allow installation from **ssMusic Player** when prompted,
then return to the app. If you cancel or deny permission, tap **Updates** to retry.
Installation always requires Android's confirmation.

Updates come from `skystream006/ssMusic_Player`, not the ssMusic app or your music
server. A release must contain exactly one APK for `com.skystream.ssmusicplayer`,
with a numeric version matching its release tag (for example, `v0.2.0`) and both
`versionName` and `versionCode` higher than the installed app. Increase both fields
in `app/build.gradle` before publishing a new release.

Android also requires the same signing identity as the installed app. The current
workflows build debug APKs using runner-generated keys; APKs from separate runs
are not guaranteed to update each other. Distribute updates signed with the same
securely managed key; the updater does not bypass signature checks.