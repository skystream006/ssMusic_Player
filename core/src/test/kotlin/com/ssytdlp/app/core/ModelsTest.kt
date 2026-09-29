package com.ssytdlp.app.core

import org.junit.Assert.*
import org.junit.Test

class ModelsTest {
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
    }
}
