# ssMusic Player

Android client for an HTTPS-hosted ssYTDLP Server. Configure the server URL from
the app, use **Open server** to complete browser-based server login, then refresh
the library. Tap a song to add it to the server queue or long-press and drag songs
to reorder that queue. Downloads are submitted through the server.

The native controls use the server's `/api/library`, `/api/queue`, and
`/api/download` endpoints; **Open server** exposes the complete server web UI for
server features not represented by the native controls.