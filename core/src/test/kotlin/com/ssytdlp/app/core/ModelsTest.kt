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

    @Test fun `models read nullable metadata and additional server properties`() {
        val page = ApiJson.decodeFromString<TrackPage>("""{"files":[{"name":"song.mp3","title":null,"playlistTitle":null,"streamUrl":"/api/test","extra":true}],"page":2,"totalPages":4,"version":9}""")
        assertEquals("song", page.files.single().displayTitle)
        assertEquals(2, page.page)
        assertEquals(9L, page.version)
    }
}
