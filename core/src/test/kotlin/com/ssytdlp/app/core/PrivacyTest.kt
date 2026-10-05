package com.ssytdlp.app.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class PrivacyTest {
    @Test fun `legacy payloads default to public without inventing privacy ownership`() {
        val track = ApiJson.decodeFromString<Track>("""{"name":"song.mp3"}""")
        val job = ApiJson.decodeFromString<Job>("""{"id":"source"}""")
        val entry = ApiJson.decodeFromString<LibraryEntry>("""{"id":"folder","type":"folder"}""")
        val playlist = ApiJson.decodeFromString<LibraryPlaylist>("""{"id":"playlist"}""")

        assertFalse(track.isPrivate)
        assertNull(track.sourceJob)
        assertEquals(FilePrivacy(false, false), track.privacy())
        assertFalse(job.isPrivate)
        assertNull(job.privateFiles)
        assertFalse(entry.isPrivate)
        assertFalse(playlist.isPrivate)
        assertNull(playlist.initiatedBy)
        assertEquals("", playlist.status)
        assertFalse(job.canChangePrivacy(User("admin", role = "admin")))
        assertFalse(playlist.canChangePrivacy(User("admin", role = "admin")))
    }

    @Test fun `null privacy fields retain backwards compatible defaults`() {
        val track = ApiJson.decodeFromString<Track>(
            """{"name":"song.mp3","private":null,"sourceJob":null}""")
        val job = ApiJson.decodeFromString<Job>(
            """{"id":"source","private":null,"privateFiles":null}""")
        val entry = ApiJson.decodeFromString<LibraryEntry>(
            """{"id":"folder","type":"folder","private":null}""")
        val playlist = ApiJson.decodeFromString<LibraryPlaylist>(
            """{"id":"playlist","private":null,"initiatedBy":null,"status":null}""")

        assertFalse(track.isPrivate)
        assertNull(track.sourceJob)
        assertFalse(job.isPrivate)
        assertNull(job.privateFiles)
        assertFalse(entry.isPrivate)
        assertFalse(playlist.isPrivate)
        assertNull(playlist.initiatedBy)
        assertEquals("", playlist.status)
    }

    @Test fun `private flags decode and encode as server boolean fields for every media model`() {
        listOf(false, true).forEach { privateFlag ->
            val track = ApiJson.decodeFromString<Track>(
                """{"name":"song.mp3","private":$privateFlag}""")
            val job = ApiJson.decodeFromString<Job>(
                """{"id":"source","private":$privateFlag}""")
            val entry = ApiJson.decodeFromString<LibraryEntry>(
                """{"id":"folder","type":"folder","private":$privateFlag}""")
            val playlist = ApiJson.decodeFromString<LibraryPlaylist>(
                """{"id":"playlist","private":$privateFlag}""")

            assertEquals(privateFlag, track.isPrivate)
            assertEquals(privateFlag, job.isPrivate)
            assertEquals(privateFlag, entry.isPrivate)
            assertEquals(privateFlag, playlist.isPrivate)
            assertPrivateRoundTrip(track, privateFlag)
            assertPrivateRoundTrip(job, privateFlag)
            assertPrivateRoundTrip(entry, privateFlag)
            assertPrivateRoundTrip(playlist, privateFlag)
        }
    }

    @Test fun `source jobs retain owner and explicit file privacy through queue serialization`() {
        val track = ApiJson.decodeFromString<Track>("""{
            "jobId":"source","name":"folder/song.mp3","private":true,
            "sourceJob":{"id":"source","private":false,"privateFiles":["folder/song.mp3"],
                "initiatedBy":{"id":"owner"},"contributors":[{"id":"contributor"}]}
        }""")
        val expectedJob = Job("source", initiatedBy = User("owner"),
            contributors = listOf(User("contributor")), privateFiles = listOf("folder/song.mp3"))

        assertEquals(expectedJob, track.sourceJob)
        assertEquals(FilePrivacy(true, false), track.privacy())
        assertPrivateRoundTrip(track, true)
        assertPrivateRoundTrip(expectedJob, false)
        val legacy = ApiJson.decodeFromString<Track>(
            """{"name":"song.mp3","sourceJob":{"id":"legacy"}}""")
        assertEquals(Job("legacy"), legacy.sourceJob)
        assertEquals(FilePrivacy(false, false), legacy.privacy())
    }

    @Test fun `privacy changes require the owner even when admins and contributors can modify jobs`() {
        val owner = User("owner")
        val contributor = User("contributor")
        val admin = User("other-admin", role = "admin")
        val job = Job("source", initiatedBy = owner, contributors = listOf(contributor))
        val playlist = LibraryPlaylist("playlist", initiatedBy = owner)

        assertTrue(job.canModify(admin))
        assertTrue(job.canModify(contributor))
        listOf(owner, owner.copy(role = "admin")).forEach { user ->
            assertTrue(job.canChangePrivacy(user))
            assertTrue(playlist.canChangePrivacy(user))
        }
        val denied = listOf(null, admin, contributor, User("other"), User(""), User(" \t"),
            User("shared-listener", role = "shared", sharedUserIds = listOf(owner.id))) +
            listOf("shared", "Shared", "SHARED").map { owner.copy(role = it) }
        denied.forEach { user ->
            assertFalse("Job must deny $user", job.canChangePrivacy(user))
            assertFalse("Playlist must deny $user", playlist.canChangePrivacy(user))
        }
    }

    @Test fun `missing or blank owner IDs cannot grant privacy permissions`() {
        listOf(null, User(""), User(" \t")).forEach { owner ->
            val job = Job("source", initiatedBy = owner)
            val playlist = LibraryPlaylist("playlist", initiatedBy = owner)
            listOf(null, User(""), User(" \t"), User("owner"), User("admin", role = "admin"))
                .forEach { user ->
                    assertFalse("Job owner=$owner user=$user", job.canChangePrivacy(user))
                    assertFalse("Playlist owner=$owner user=$user", playlist.canChangePrivacy(user))
                }
        }
    }

    @Test fun `synthetic library playlists authorize initiatedBy rather than their ID or a backing job`() {
        val library = ApiJson.decodeFromString<Library>("""{"playlists":[{
            "id":"library-playlist","playlistTitle":"Library","jobId":null,"private":true,
            "initiatedBy":{"id":"library-owner"},"status":"completed"
        }]}""")
        val playlist = library.playlists.single()

        assertTrue(library.jobs.isEmpty())
        assertNull(playlist.jobId)
        assertTrue(playlist.isPrivate)
        assertEquals(User("library-owner"), playlist.initiatedBy)
        assertTrue(playlist.canChangePrivacy(User("library-owner")))
        assertFalse(playlist.canChangePrivacy(User(playlist.id)))
        assertFalse(playlist.canChangePrivacy(User("source-owner", role = "admin")))
        assertFalse(playlist.canChangePrivacy(User("library-owner", role = "shared")))
        assertPrivateRoundTrip(playlist, true)
    }

    @Test fun `track flags without source jobs are effective but not inherited privacy`() {
        listOf(false, true).forEach { privateFlag ->
            val track = Track(name = "song.mp3", isPrivate = privateFlag)
            assertEquals(FilePrivacy(privateFlag, false), track.privacy())
            assertEquals(FilePrivacy(privateFlag, false), track.privacy(null))
        }
    }

    @Test fun `public jobs distinguish explicit file privacy from inherited server flags`() {
        val cases = listOf(
            PrivacyCase(false, null, FilePrivacy(false, false)),
            PrivacyCase(true, null, FilePrivacy(true, false)),
            PrivacyCase(false, emptyList(), FilePrivacy(false, false)),
            PrivacyCase(true, emptyList(), FilePrivacy(true, true)),
            PrivacyCase(false, listOf("song.mp3"), FilePrivacy(true, false)),
            PrivacyCase(true, listOf("song.mp3"), FilePrivacy(true, false)),
            PrivacyCase(false, listOf("other.mp3"), FilePrivacy(false, false)),
            PrivacyCase(true, listOf("other.mp3"), FilePrivacy(true, true))
        )
        cases.forEach { case ->
            val job = Job("source", privateFiles = case.privateFiles)
            val track = Track(name = "song.mp3", isPrivate = case.trackPrivate, sourceJob = job)
            assertEquals(case.toString(), case.expected, track.privacy())
            assertEquals(case.toString(), case.expected, track.copy(sourceJob = null).privacy(job))
        }
    }

    @Test fun `private jobs always confer inherited privacy regardless of explicit file flags`() {
        listOf(false, true).forEach { trackPrivate ->
            listOf(null, emptyList(), listOf("song.mp3"), listOf("other.mp3")).forEach { files ->
                val job = Job("source", isPrivate = true, privateFiles = files)
                val track = Track(name = "song.mp3", isPrivate = trackPrivate, sourceJob = job)
                assertEquals("track.private=$trackPrivate privateFiles=$files",
                    FilePrivacy(true, true), track.privacy())
            }
        }
    }

    @Test fun `explicit job arguments override source jobs including an explicit null`() {
        val privateJob = Job("source", isPrivate = true)
        val publicJob = Job("public", privateFiles = emptyList())
        val track = Track(name = "song.mp3", sourceJob = privateJob)

        assertEquals(FilePrivacy(true, true), track.privacy())
        assertEquals(FilePrivacy(false, false), track.privacy(publicJob))
        assertEquals(FilePrivacy(false, false), track.privacy(null))
        assertEquals(FilePrivacy(true, true), track.copy(sourceJob = publicJob).privacy(privateJob))
        assertEquals(FilePrivacy(true, false), track.copy(isPrivate = true).privacy(null))
    }

    @Test fun `companions inherit server privacy unless their own exact filename is explicitly private`() {
        val track = ApiJson.decodeFromString<Track>("""{
            "name":"song.mp3","private":true,
            "sourceJob":{"id":"source","private":false,"privateFiles":["song.mp3"]},
            "noVocalsVersion":{"name":"[NoVocals]/song.mp3","private":true}
        }""")
        val job = track.sourceJob!!
        val companion = track.noVocalsVersion!!

        assertEquals(FilePrivacy(true, false), track.privacy())
        assertEquals(FilePrivacy(true, true), companion.privacy(job))
        assertEquals(FilePrivacy(false, false), companion.copy(isPrivate = false).privacy(job))
        val explicitCompanion = job.copy(privateFiles = listOf(track.name, companion.name))
        assertEquals(FilePrivacy(true, false), companion.privacy(explicitCompanion))
        assertEquals(FilePrivacy(true, false),
            companion.copy(isPrivate = false).privacy(explicitCompanion))
        assertEquals(FilePrivacy(true, true),
            companion.privacy(explicitCompanion.copy(isPrivate = true)))
        assertPrivateRoundTrip(track, true)
    }

    private data class PrivacyCase(
        val trackPrivate: Boolean,
        val privateFiles: List<String>?,
        val expected: FilePrivacy
    )

    private inline fun <reified T> assertPrivateRoundTrip(value: T, privateFlag: Boolean) {
        val encoded = ApiJson.encodeToString(value)
        val json = ApiJson.parseToJsonElement(encoded).jsonObject
        assertEquals(JsonPrimitive(privateFlag), json["private"])
        assertFalse(json.containsKey("isPrivate"))
        assertEquals(value, ApiJson.decodeFromString<T>(encoded))
    }
}
