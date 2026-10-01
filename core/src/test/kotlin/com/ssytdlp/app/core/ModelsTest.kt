package com.ssytdlp.app.core

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class ModelsTest {
    @Test fun `preferences default to Green and resolve legacy Porcelain without changing mode`() {
        assertEquals("green", Preferences().theme)
        assertEquals("green", ApiJson.decodeFromString<Preferences>("{}").effectiveTheme)
        listOf(null, "light", "dark").forEach { mode ->
            val preferences = ApiJson.decodeFromString<Preferences>(
                ApiJson.encodeToString(Preferences(theme = "light", mode = mode)))
            assertEquals("green", preferences.effectiveTheme)
            assertEquals(mode, preferences.mode)
        }
        assertEquals("""{"theme":"green","mode":null}""", ApiJson.encodeToString(Preferences()))
    }

    @Test fun `remaining server themes and modes are preserved`() {
        listOf("midnight", "royal-purple", "gold", "green", "pink", "black").forEach { theme ->
            listOf(null, "light", "dark").forEach { mode ->
                val preferences = ApiJson.decodeFromString<Preferences>(
                    ApiJson.encodeToString(Preferences(theme, mode)))
                assertEquals(theme, preferences.effectiveTheme)
                assertEquals(mode, preferences.mode)
            }
        }
    }

    @Test fun `login and current user responses retain shared account details`() {
        val user = """{"id":"listener","name":"Listener","role":"shared","status":"approved","sharedUserIds":["owner"],"createdAt":"2026-09-30"}"""
        val login = ApiJson.decodeFromString<LoginResponse>(
            """{"user":$user,"session":{"token":"test-session","expiresAt":"2099-01-01T00:00:00Z","tokenType":"Bearer"}}""")
        val current = ApiJson.decodeFromString<UserResponse>("""{"user":$user}""")
        assertEquals(current.user, login.user)
        assertEquals("shared", current.user.role)
        assertEquals("approved", current.user.status)
        assertEquals(listOf("owner"), current.user.sharedUserIds)
        assertTrue(current.user.isShared)
        val account = Account("https://music.example", login.user, login.session)
        assertEquals(account, ApiJson.decodeFromString<Account>(ApiJson.encodeToString(account)))
    }

    @Test fun `shared role matching accepts server and legacy casing without restricting other roles`() {
        listOf("shared", "Shared", "SHARED").forEach { assertTrue(User(role = it).isShared) }
        listOf("user", "admin").forEach { assertFalse(User(role = it).isShared) }
        val legacy = ApiJson.decodeFromString<User>("""{"id":"listener","role":"user"}""")
        assertEquals(emptyList<String>(), legacy.sharedUserIds)
        assertFalse(legacy.isShared)
    }

    @Test fun `resource URLs preserve escaped nested file names and query versions`() {
        assertEquals("https://music.example.com:8443/api/jobs/123/stream/%5BNoVocals%5D%2FSong.mp3?v=42",
            ServerResource.resolve("https://music.example.com:8443", "/api/jobs/123/stream/%5BNoVocals%5D%2FSong.mp3?v=42"))
    }

    @Test fun `resource URLs reject cross origin cleartext and non API locations`() {
        listOf("https://evil.example/api/music", "//evil.example/api/music", "http://music.example.com/api/music",
            "/app-login", "/api/../app-login", "https://user@music.example.com/api/music", "/api/music#fragment").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { ServerResource.resolve("https://music.example.com", path) }
        }
    }

    @Test fun `track identity matches server JSON song keys`() {
        assertEquals("[\"job-id\",\"[NoVocals]/A \\\"song\\\".mp3\"]", Track("job-id", "[NoVocals]/A \"song\".mp3").key)
    }

    @Test fun `artist display never falls back to the playlist title`() {
        val track = Track(name = "song.mp3", playlistTitle = "My playlist")
        assertEquals("Unknown artist", track.displayArtist)
        assertEquals("Unknown artist", track.copy(artist = " ").displayArtist)
        assertEquals("Northbound", track.copy(artist = "Northbound").displayArtist)
        assertEquals("My playlist", track.playlistTitle)
    }

    @Test fun `models read nullable metadata and additional server properties`() {
        val page = ApiJson.decodeFromString<TrackPage>("""{"files":[{"name":"song.mp3","title":null,"playlistTitle":null,"streamUrl":"/api/test","extra":true}],"page":2,"totalPages":4,"version":9}""")
        assertEquals("song", page.files.single().displayTitle)
        assertEquals(2, page.page)
        assertEquals(9L, page.version)
    }

    @Test fun `track locks and lyric edit permissions decode with safe legacy defaults`() {
        val page = ApiJson.decodeFromString<TrackPage>("""{"files":[
            {"name":"locked.mp3","transcriptionLocked":true},
            {"name":"unlocked.mp3","transcriptionLocked":false},
            {"name":"legacy.mp3"}
        ]}""")
        assertTrue(page.files[0].transcriptionLocked)
        assertFalse(page.files[1].transcriptionLocked)
        assertFalse(page.files[2].transcriptionLocked)
        val metadata = ApiJson.decodeFromString<SongMetadata>(
            """{"transcriptionLocked":true,"canEdit":true,"sylt":[{"time":1.234,"text":"Line"}],"uslt":"Plain"}""")
        assertTrue(metadata.transcriptionLocked)
        assertTrue(metadata.canEdit)
        assertEquals(1.234, metadata.sylt.single().time, 0.0)
        assertEquals("Plain", metadata.uslt)
        assertFalse(ApiJson.decodeFromString<SongMetadata>("{}").canEdit)
        assertFalse(ApiJson.decodeFromString<SongMetadata>("{}").transcriptionLocked)
    }

    @Test fun `track pages decode inline transcription details from compact library responses`() {
        val page = ApiJson.decodeFromString<TrackPage>("""{"files":[
            {"jobId":"source","name":"folder/song.mp3","transcription":{
                "status":"transcribed","requestedAt":"2026-09-28T12:00:00Z",
                "completedAt":"2026-09-28T12:01:00Z","lyricsIncluded":true,
                "options":{"language":"vi","Multilingual":false,"NoVocals":true,
                    "VietLyricsFallback":true,"lyrics_mode":"align"}}},
            {"jobId":"source","name":"failed.mp3","transcription":{"status":"failed","error":"Service unavailable"}},
            {"jobId":"source","name":"missing.mp3"},
            {"jobId":"source","name":"null.mp3","transcription":null}
        ]}""")
        val done = page.files.first().transcription!!
        assertEquals("transcribed", done.status)
        assertEquals("2026-09-28T12:00:00Z", done.requestedAt)
        assertEquals("2026-09-28T12:01:00Z", done.completedAt)
        assertTrue(done.lyricsIncluded)
        assertEquals(SavedTranscriptionOptions("vi", false, true, true, "align"), done.options)
        assertEquals("Service unavailable", page.files[1].transcription!!.error)
        assertNull(page.files[2].transcription)
        assertNull(page.files[3].transcription)
    }

    @Test fun `library reads filename keyed transcription records and saved request options`() {
        val library = ApiJson.decodeFromString<Library>("""{"jobs":[{"id":"source","transcriptions":{
            "folder/song.mp3":{"status":"transcribed","requestedAt":"2026-09-28T12:00:00Z",
                "completedAt":"2026-09-28T12:01:00Z","lyricsIncluded":true,
                "options":{"language":"vi","Multilingual":false,"NoVocals":true,
                    "VietLyricsFallback":true,"lyrics_mode":"align"},"noVocalsName":"extra.mp3"},
            "failed.mp3":{"status":"failed","error":"Service unavailable"},
            "restart.mp3":{"status":"interrupted"},
            "pending.mp3":{"status":"sent"}
        }}]}""")
        val records = library.jobs.single().transcriptions
        val done = records.getValue("folder/song.mp3")
        assertEquals("transcribed", done.status)
        assertEquals("2026-09-28T12:00:00Z", done.requestedAt)
        assertEquals("2026-09-28T12:01:00Z", done.completedAt)
        assertTrue(done.lyricsIncluded)
        assertEquals(SavedTranscriptionOptions("vi", false, true, true, "align"), done.options)
        assertEquals("Service unavailable", records.getValue("failed.mp3").error)
        assertEquals("interrupted", records.getValue("restart.mp3").status)
        assertEquals("sent", records.getValue("pending.mp3").status)
    }

    @Test fun `legacy transcription records preserve absent options and tri state booleans`() {
        assertTrue(ApiJson.decodeFromString<Job>("""{"id":"old"}""").transcriptions.isEmpty())
        assertTrue(ApiJson.decodeFromString<Job>("""{"id":"old","transcriptions":null}""").transcriptions.isEmpty())
        val legacy = ApiJson.decodeFromString<Transcription>("""{"status":"transcribed"}""")
        assertFalse(legacy.lyricsIncluded)
        assertNull(legacy.options)
        val defaults = ApiJson.decodeFromString<Transcription>("""{"status":"sent","options":{}}""")
        assertEquals(SavedTranscriptionOptions(), defaults.options)
        assertNull(defaults.options!!.multilingual)
        val explicitFalse = ApiJson.decodeFromString<Transcription>("""{"options":{"Multilingual":false,"NoVocals":null}}""")
        assertEquals(false, explicitFalse.options!!.multilingual)
        assertNull(explicitFalse.options!!.noVocals)
        assertNull(explicitFalse.options!!.noVocalsOnly)
    }

    @Test fun `saved no vocals only mode decodes from track and job records and survives persistence`() {
        val record = """{"status":"transcribed","options":{"NoVocalsOnly":true}}"""
        val track = ApiJson.decodeFromString<Track>("""{"name":"song.mp3","transcription":$record}""")
        val job = ApiJson.decodeFromString<Job>("""{"id":"source","transcriptions":{"song.mp3":$record}}""")
        assertEquals(job.transcriptions.getValue("song.mp3"), track.transcription)
        assertEquals(SavedTranscriptionOptions(noVocalsOnly = true), track.transcription!!.options)
        assertEquals(track, ApiJson.decodeFromString<Track>(ApiJson.encodeToString(track)))
        assertEquals(false, ApiJson.decodeFromString<SavedTranscriptionOptions>("""{"NoVocalsOnly":false}""").noVocalsOnly)
        assertNull(ApiJson.decodeFromString<SavedTranscriptionOptions>("""{"NoVocalsOnly":null}""").noVocalsOnly)
    }
}
