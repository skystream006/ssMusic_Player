package com.ssytdlp.app

import com.ssytdlp.app.core.Job
import com.ssytdlp.app.core.FilePrivacy
import com.ssytdlp.app.core.Library
import com.ssytdlp.app.core.LibraryEntry
import com.ssytdlp.app.core.LibraryPlaylist
import com.ssytdlp.app.core.Track
import com.ssytdlp.app.core.TrackPage
import com.ssytdlp.app.core.Transcription
import org.junit.Assert.*
import org.junit.Test

class LibraryStateTest {
    private val track = Track(jobId = "source", name = "folder/song.mp3")
    private val sent = Transcription(status = "sent")
    private val done = Transcription(status = "transcribed", lyricsIncluded = true)

    @Test fun `restored playlist or folder remains selected after the library loads`() {
        val library = Library(entries = listOf(LibraryEntry("playlist", "playlist"), LibraryEntry("folder", "folder")))
        for (selectedId in listOf("playlist", "folder", null)) {
            val restored = LibraryState(selectedId = selectedId).withLibrary(library)
            assertEquals(selectedId, restored.selectedId)
            assertEquals(library, restored.library)
        }
    }

    @Test fun `deleted saved selection falls back to all music`() {
        val restored = LibraryState(selectedId = "removed").withLibrary(Library())
        assertNull(restored.selectedId)
    }

    @Test fun `ratings refresh queued snapshots and retain cleared ratings across pages`() {
        val queued = track.copy(rating = 4)
        assertEquals(4, LibraryState().rating(queued))
        val refreshed = LibraryState().withTrackPage(TrackPage(files = listOf(track.copy(rating = 2))))
        assertEquals(2, refreshed.rating(queued))
        val cleared = refreshed.withTrackPage(TrackPage(files = listOf(track.copy(rating = 0))))
        val otherPage = cleared.withTrackPage(TrackPage(files = listOf(Track("other", track.name, rating = 5))))
        assertEquals(0, otherPage.rating(queued))
        assertEquals(5, otherPage.rating(Track("other", track.name)))
    }

    @Test fun `artwork revisions and removals override queued snapshots across pages and replacements`() {
        val queued = track.copy(artworkUrl = "/api/jobs/source/artwork/song.mp3?v=1")
        val updated = queued.copy(artworkUrl = "/api/jobs/source/artwork/song.mp3?v=2")
        assertEquals(queued.artworkUrl, LibraryState().artworkUrl(queued))
        val refreshed = LibraryState().withTrackPage(TrackPage(files = listOf(updated)))
        assertEquals(updated.artworkUrl, refreshed.artworkUrl(queued))
        val nextPage = refreshed.withTrackPage(TrackPage(files = listOf(Track("other", track.name))))
        assertEquals(updated.artworkUrl, nextPage.artworkUrl(queued))
        assertNull(nextPage.artworkUrl(Track("other", track.name)))
        assertNull(nextPage.withTrackPage(TrackPage(files = listOf(track))).artworkUrl(queued))
        assertNull(nextPage.withReplacedFile(track).artworkUrl(queued))
        assertEquals(updated.artworkUrl, LibraryState().withReplacedFile(updated).artworkUrl(queued))
    }

    @Test fun `inline records take precedence over legacy job summaries`() {
        val state = LibraryState(library = Library(jobs = listOf(
            Job(id = track.jobId, transcriptions = mapOf(track.name to sent)))))
        assertEquals(done, state.transcription(track.copy(transcription = done)))
        assertEquals(sent, state.transcription(track))
        assertNull(state.transcription(track.copy(name = "missing.mp3")))
    }

    @Test fun `lock refresh overrides stale tracks and keeps explicit unlocks across pages`() {
        val locked = track.copy(transcriptionLocked = true)
        assertTrue(LibraryState().transcriptionLocked(locked))
        val refreshed = LibraryState().withTrackPage(TrackPage(files = listOf(locked)))
        assertTrue(refreshed.transcriptionLocked(track))
        val unlocked = refreshed.withTrackPage(TrackPage(files = listOf(track)))
        assertFalse(unlocked.transcriptionLocked(locked))
        val nextPage = unlocked.withTrackPage(TrackPage(files = listOf(Track("other", track.name))))
        assertFalse(nextPage.transcriptionLocked(locked))
        assertFalse(nextPage.transcriptionLocked(Track("other", track.name)))
    }

    @Test fun `page refresh updates stale queued tracks without changing selection or pending status`() {
        val queued = track.copy(transcription = sent)
        val initial = LibraryState(library = Library(version = 7), selectedId = "playlist", search = "song", page = 2,
            pendingTranscriptions = mapOf(track.key to sent))
        val result = TrackPage(files = listOf(track.copy(transcription = done)), page = 2, version = 9)
        val refreshed = initial.withTrackPage(result)
        assertEquals("playlist", refreshed.selectedId)
        assertEquals("song", refreshed.search)
        assertEquals(2, refreshed.page)
        assertEquals(7L, refreshed.library.version)
        assertEquals(result, refreshed.tracks)
        assertEquals(sent, refreshed.transcription(queued))
        assertEquals(done, refreshed.copy(pendingTranscriptions = emptyMap()).transcription(queued))
    }

    @Test fun `cached queue statuses survive browsing another page and refreshing compact summaries`() {
        val refreshed = LibraryState().withTrackPage(TrackPage(files = listOf(track.copy(transcription = done))))
        val otherPage = refreshed.withTrackPage(TrackPage(files = listOf(Track("other", track.name)), page = 2))
            .copy(library = Library(jobs = listOf(Job(id = track.jobId))))
        assertEquals(done, otherPage.transcription(track))
        assertNull(otherPage.transcription(Track("other", track.name)))
    }

    @Test fun `job detail polling updates queued songs whose jobs are absent from the library`() {
        val stale = track.copy(transcription = sent)
        val other = Track("other", track.name, transcription = done)
        val state = LibraryState(selectedId = "another-playlist").withTranscriptions(
            Job(id = track.jobId, transcriptions = mapOf(track.name to done)), listOf(stale, other))
        assertEquals(done, state.transcription(stale))
        assertEquals(done, state.transcription(other))
        assertTrue(state.library.jobs.isEmpty())
        assertEquals("another-playlist", state.selectedId)
    }

    @Test fun `missing refreshed records clear stale inline and cached statuses`() {
        val stale = track.copy(transcription = done)
        val initial = LibraryState().withTrackPage(TrackPage(files = listOf(stale)))
        val pageRefresh = initial.withTrackPage(TrackPage(files = listOf(track)))
        assertNull(pageRefresh.transcription(stale))
        val jobRefresh = initial.withTranscriptions(Job(id = track.jobId), listOf(stale))
        assertNull(jobRefresh.transcription(stale))
    }

    @Test fun `job detail updates retain pending request precedence until the request finishes`() {
        val initial = LibraryState(pendingTranscriptions = mapOf(track.key to sent))
        val failed = Transcription(status = "failed", error = "Service unavailable")
        val refreshed = initial.withTranscriptions(
            Job(id = track.jobId, transcriptions = mapOf(track.name to failed)), listOf(track))
        assertEquals(sent, refreshed.transcription(track))
        assertEquals(failed, refreshed.copy(pendingTranscriptions = emptyMap()).transcription(track))
    }

    @Test fun `replacement clears stale rating and transcription while keeping membership and companions`() {
        val companion = track.copy(name = "[NoVocals]/song.mp3")
        val original = track.copy(playlistId = "playlist", playlistTitle = "Playlist", rating = 5,
            transcription = done, transcriptionLocked = true, noVocalsVersion = companion)
        val other = original.copy(jobId = "other")
        val initial = LibraryState(selectedId = "playlist", search = "old", page = 2,
            tracks = TrackPage(files = listOf(original, other)),
            pendingTranscriptions = mapOf(original.key to sent),
            transcriptions = mapOf(original.key to done), ratings = mapOf(original.key to 5))
        val file = track.copy(title = "Replacement", streamUrl = "/api/stream/song.mp3?v=2", transcriptionLocked = true)
        val refreshed = initial.withReplacedFile(file)
        val updated = refreshed.tracks.files.first()
        assertEquals(file.streamUrl, updated.streamUrl)
        assertEquals("Replacement", updated.title)
        assertEquals(original.key, updated.key)
        assertEquals("playlist", updated.playlistId)
        assertEquals("Playlist", updated.playlistTitle)
        assertEquals(companion, updated.noVocalsVersion)
        assertTrue(refreshed.transcriptionLocked(original))
        assertEquals(0, refreshed.rating(original))
        assertNull(refreshed.transcription(original))
        assertFalse(refreshed.pendingTranscriptions.containsKey(original.key))
        assertEquals(other, refreshed.tracks.files.last())
        assertEquals("playlist", refreshed.selectedId)
        assertEquals("old", refreshed.search)
        assertEquals(2, refreshed.page)
    }

    @Test fun `replacing a no vocals file refreshes nested references but not original audio`() {
        val companion = track.copy(name = "[NoVocals]/song.mp3", streamUrl = "/api/stream/karaoke?v=1")
        val original = track.copy(noVocalsVersion = companion, streamUrl = "/api/stream/original?v=1")
        val replacement = companion.copy(title = "New instrumental", streamUrl = "/api/stream/karaoke?v=2")
        val updated = original.withReplacedFile(replacement)
        assertEquals(original.streamUrl, updated.streamUrl)
        assertEquals(replacement, updated.noVocalsVersion)
        assertEquals(replacement, companion.withReplacedFile(replacement))
    }

    @Test fun `privacy refresh overrides stale queued snapshots across pages and source jobs`() {
        val privateJob = Job(track.jobId, privateFiles = listOf(track.name))
        val queued = track.copy(isPrivate = true, sourceJob = privateJob)
        val publicJob = privateJob.copy(privateFiles = emptyList())
        val refreshed = LibraryState().withTrackPage(TrackPage(files = listOf(
            track.copy(sourceJob = publicJob))))
        val other = track.copy(jobId = "other", isPrivate = true,
            sourceJob = Job("other", privateFiles = listOf(track.name)))
        val nextPage = refreshed.withTrackPage(TrackPage(files = listOf(other), page = 2))
            .withLibrary(Library(version = 9))

        assertEquals(false, nextPage.filePrivacy[queued.key])
        assertEquals(publicJob, nextPage.privacyJob(queued))
        assertEquals(FilePrivacy(false, false), nextPage.privacy(queued))
        assertEquals(FilePrivacy(true, false), nextPage.privacy(other))
        assertEquals(2, nextPage.page)
        assertEquals(9L, nextPage.library.version)
    }

    @Test fun `catalog refresh cannot confirm stale queued file privacy from an older job version`() {
        val privateJob = Job(track.jobId, isPrivate = true, privateFiles = emptyList(), updatedAt = "v1")
        val queued = track.copy(isPrivate = true, sourceJob = privateJob)
        val confirmed = LibraryState().withTrackPage(TrackPage(files = listOf(queued)))
        assertFalse(confirmed.needsPrivacyRefresh(privateJob, listOf(queued)))

        val publicJob = privateJob.copy(isPrivate = false, updatedAt = "v2")
        val catalogRefresh = confirmed.withLibrary(Library(jobs = listOf(publicJob)))
        assertEquals(publicJob, catalogRefresh.privacyJob(queued))
        assertEquals(true, catalogRefresh.filePrivacy[queued.key])
        assertTrue(catalogRefresh.needsPrivacyRefresh(publicJob, listOf(queued)))

        val other = track.copy(name = "other.mp3", sourceJob = publicJob)
        val otherPage = catalogRefresh.withTrackPage(TrackPage(files = listOf(other), page = 2))
        assertEquals(true, otherPage.filePrivacy[queued.key])
        assertEquals(false, otherPage.filePrivacy[other.key])
        assertTrue(otherPage.needsPrivacyRefresh(publicJob, listOf(queued)))
        assertTrue(otherPage.needsPrivacyRefresh(publicJob, listOf(queued, other)))
        assertFalse(otherPage.needsPrivacyRefresh(publicJob, listOf(other)))

        val filesRefresh = otherPage.withPrivacyFiles(listOf(Track(name = track.name)), publicJob)
        assertEquals(false, filesRefresh.filePrivacy[queued.key])
        assertEquals(FilePrivacy(false, false), filesRefresh.privacy(queued))
        assertFalse(filesRefresh.needsPrivacyRefresh(publicJob, listOf(queued, other)))
    }

    @Test fun `privacy files refresh no vocals references without replacing the visible page`() {
        val job = Job(track.jobId, privateFiles = listOf(track.name))
        val companion = track.copy(name = "[NoVocals]/folder/song.mp3", isPrivate = true, sourceJob = job)
        val original = track.copy(isPrivate = true, sourceJob = job, noVocalsVersion = companion)
        val state = LibraryState(selectedId = "playlist", search = "other", page = 2,
            tracks = TrackPage(files = listOf(Track("other", "other.mp3")), page = 2))
        val privateState = state.withPrivacyFiles(listOf(original))
        assertEquals(FilePrivacy(true, false), privateState.privacy(track))
        assertEquals(FilePrivacy(true, true), privateState.privacy(companion.copy(isPrivate = false)))
        assertEquals(true, privateState.filePrivacy[companion.key])

        val publicJob = job.copy(privateFiles = emptyList())
        val publicState = privateState.withPrivacyFiles(listOf(
            original.copy(isPrivate = false, sourceJob = publicJob,
                noVocalsVersion = companion.copy(isPrivate = false, sourceJob = publicJob))))
        assertEquals(FilePrivacy(false, false), publicState.privacy(original))
        assertEquals(FilePrivacy(false, false), publicState.privacy(companion))
        assertEquals(false, publicState.filePrivacy[companion.key])
        assertEquals(publicJob, publicState.privacyJobs[track.jobId])
        assertEquals(state.tracks, publicState.tracks)
        assertEquals(state.selectedId, publicState.selectedId)
        assertEquals(state.search, publicState.search)
        assertEquals(state.page, publicState.page)
    }

    @Test fun `source playlist privacy is inherited while explicit songs remain private after public refresh`() {
        val explicit = track.copy(name = "explicit.mp3")
        val privateJob = Job(track.jobId, isPrivate = true, privateFiles = listOf(explicit.name))
        val state = LibraryState().withLibrary(Library(jobs = listOf(privateJob)))
        assertEquals(FilePrivacy(true, true), state.privacy(track))
        assertEquals(FilePrivacy(true, true), state.privacy(explicit))

        val publicJob = privateJob.copy(isPrivate = false)
        val refreshed = state.withLibrary(Library(jobs = listOf(publicJob))).withTrackPage(
            TrackPage(files = listOf(track.copy(sourceJob = publicJob),
                explicit.copy(isPrivate = true, sourceJob = publicJob))))
        assertEquals(publicJob, refreshed.privacyJob(track.copy(sourceJob = privateJob)))
        assertEquals(FilePrivacy(false, false), refreshed.privacy(track.copy(isPrivate = true)))
        assertEquals(FilePrivacy(true, false), refreshed.privacy(explicit))
    }

    @Test fun `synthetic playlist privacy does not make its linked songs private`() {
        val job = Job(track.jobId, privateFiles = emptyList())
        val linked = track.copy(playlistId = "synthetic", sourceJob = job)
        val state = LibraryState().withTrackPage(TrackPage(files = listOf(linked)))
            .withLibrary(Library(
                entries = listOf(LibraryEntry("synthetic", "playlist", protected = true, isPrivate = true)),
                playlists = listOf(LibraryPlaylist("synthetic", isPrivate = true)),
                jobs = listOf(job)))
        assertTrue(state.library.playlists.single().isPrivate)
        assertEquals(FilePrivacy(false, false), state.privacy(linked))
        assertEquals(false, state.filePrivacy[linked.key])
        assertEquals(job, state.privacyJobs[track.jobId])
    }
}
