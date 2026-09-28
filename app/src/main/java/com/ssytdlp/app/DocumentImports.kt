package com.ssytdlp.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.BufferedSink

data class SelectedDocument(val uri: Uri, val name: String, val size: Long?)

fun selectedDocument(context: Context, uri: Uri): SelectedDocument {
    require(uri.scheme == "content") { "Choose a document from the Android file picker." }
    return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        require(cursor.moveToFirst()) { "The selected document is unavailable." }
        val name = cursor.getString(0)?.substringAfterLast('/')?.substringAfterLast('\\') ?: "media"
        require(name.isNotBlank() && name.none { it.code < 32 || it.code == 127 }) { "Invalid document name." }
        SelectedDocument(uri, name, if (cursor.isNull(1)) null else cursor.getLong(1).takeIf { it >= 0 })
    } ?: throw IOException("Cannot access the selected document.")
}

suspend fun buildImportBody(context: Context, uris: List<Uri>, title: String, playlistId: String?, itunes: Boolean): RequestBody = withContext(Dispatchers.IO) {
    require(uris.isNotEmpty() && uris.size <= 1000) { "Choose between 1 and 1,000 media files." }
    val documents = uris.map { selectedDocument(context, it) }
    val totalLimit = 2L * 1024 * 1024 * 1024
    require(documents.sumOf { it.size ?: 0 } <= totalLimit) { "Total upload must be at most 2 GB." }
    if (itunes) require(documents.size == 2 && documents[0].name.endsWith(".xml", true) && documents[1].name.endsWith(".zip", true)) {
        "Choose an iTunes XML library and its media ZIP archive."
    }
    else require(playlistId != null || title.trim().isNotEmpty()) { "Enter a playlist name or choose an existing playlist." }
    var bytesSent = 0L
    val builder = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("mode", if (itunes) "itunes" else "files")
    if (!itunes) {
        builder.addFormDataPart("createNew", (playlistId == null).toString())
        if (playlistId == null) builder.addFormDataPart("playlistTitle", title.trim()) else builder.addFormDataPart("playlistId", playlistId)
    }
    documents.forEachIndexed { index, document ->
        val field = if (itunes) if (index == 0) "xml" else "media" else "files"
        val limit = when (field) { "xml" -> 20L * 1024 * 1024; "media" -> totalLimit; else -> 512L * 1024 * 1024 }
        require(document.size == null || document.size <= limit) { "${document.name} exceeds the server's file size limit." }
        builder.addFormDataPart(field, document.name, object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = document.size ?: -1L
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                val input = context.contentResolver.openInputStream(document.uri) ?: throw IOException("Cannot read ${document.name}.")
                input.use {
                    val buffer = ByteArray(64 * 1024)
                    var fileBytes = 0L
                    while (true) {
                        val count = it.read(buffer)
                        if (count < 0) break
                        fileBytes += count
                        bytesSent += count
                        if (fileBytes > limit || bytesSent > totalLimit) throw IOException("Import exceeds the server upload limits.")
                        sink.write(buffer, 0, count)
                    }
                }
            }
        })
    }
    builder.build()
}