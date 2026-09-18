package com.skystream.ssmusicplayer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import javax.net.ssl.HttpsURLConnection;

/** One cancellable, bounded HTTPS client per activity-owned updater. */
final class UpdateClient {
    private volatile boolean cancelled;
    private volatile HttpsURLConnection active;

    static final class NoReleaseException extends IOException {
        NoReleaseException() {
            super("No published release");
        }
    }

    void cancel() {
        cancelled = true;
        HttpsURLConnection connection = active;
        if (connection != null) connection.disconnect();
    }

    String latestRelease() throws IOException {
        long deadline = deadline(45);
        HttpsURLConnection connection = open(new URL(UpdatePolicy.LATEST_URL), true, deadline);
        try (InputStream input = connection.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            copy(input, output, UpdatePolicy.MAX_METADATA_BYTES, contentLength(connection),
                    () -> check(deadline));
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            close(connection);
        }
    }

    File download(String address, long expectedSize, File directory) throws IOException {
        validateSize(expectedSize);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Cannot create update directory");
        }
        long deadline = deadline(180);
        HttpsURLConnection connection = open(new URL(address), false, deadline);
        try {
            long length = contentLength(connection);
            if (length != -1 && length != expectedSize) {
                throw new IOException("Unexpected content length");
            }
            try (InputStream input = connection.getInputStream()) {
                return saveAtomically(input, directory, expectedSize, () -> check(deadline));
            }
        } finally {
            close(connection);
        }
    }

    private HttpsURLConnection open(URL url, boolean metadata, long deadline) throws IOException {
        for (int redirects = 0; redirects <= 5; redirects++) {
            check(deadline);
            if (!UpdatePolicy.isAllowedUrl(url, metadata, redirects == 0)) {
                throw new IOException("Untrusted update URL");
            }
            HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
            active = connection;
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setRequestProperty("User-Agent", "ssMusic-Player-Android-Updater");
            connection.setRequestProperty("Accept", metadata
                    ? "application/vnd.github+json" : "application/octet-stream");
            connection.setRequestProperty("Accept-Encoding", "identity");
            try {
                check(deadline);
                int status = connection.getResponseCode();
                check(deadline);
                if (status == 200) {
                    long length = contentLength(connection);
                    if (length > (metadata ? UpdatePolicy.MAX_METADATA_BYTES
                            : UpdatePolicy.MAX_APK_BYTES)) {
                        throw new IOException("Response too large");
                    }
                    return connection;
                }
                if (metadata && status == 404) throw new NoReleaseException();
                if (status == 301 || status == 302 || status == 303
                        || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Missing redirect");
                    url = new URL(url, location);
                } else {
                    throw new IOException("Update HTTP status " + status);
                }
            } catch (IOException | RuntimeException e) {
                close(connection);
                throw e;
            }
            close(connection);
        }
        throw new IOException("Too many redirects");
    }

    private void close(HttpsURLConnection connection) {
        connection.disconnect();
        if (active == connection) active = null;
    }

    private static long contentLength(HttpsURLConnection connection) throws IOException {
        String value = connection.getHeaderField("Content-Length");
        if (value == null) return -1;
        try {
            long length = Long.parseLong(value);
            if (length < 0) throw new NumberFormatException();
            return length;
        } catch (NumberFormatException e) {
            throw new IOException("Invalid content length", e);
        }
    }

    private static long deadline(int seconds) {
        return System.nanoTime() + seconds * 1_000_000_000L;
    }

    private void check(long deadline) throws InterruptedIOException {
        if (cancelled || Thread.currentThread().isInterrupted()
                || System.nanoTime() > deadline) {
            throw new InterruptedIOException("Update cancelled or timed out");
        }
    }

    interface Check {
        void run() throws IOException;
    }

    static void copy(InputStream input, OutputStream output, long limit, long expectedSize,
            Check check) throws IOException {
        byte[] buffer = new byte[16384];
        long total = 0;
        while (true) {
            check.run();
            int count = input.read(buffer);
            check.run();
            if (count == -1) break;
            total += count;
            if (total > limit || (expectedSize >= 0 && total > expectedSize)) {
                throw new IOException("Response too large");
            }
            output.write(buffer, 0, count);
        }
        if (expectedSize >= 0 && total != expectedSize) {
            throw new IOException("Truncated response");
        }
    }

    static File saveAtomically(InputStream input, File directory, long expectedSize, Check check)
            throws IOException {
        validateSize(expectedSize);
        File target = new File(directory, UUID.randomUUID() + ".apk");
        File partial = new File(directory, target.getName() + ".part");
        boolean complete = false;
        try {
            try (FileOutputStream output = new FileOutputStream(partial)) {
                copy(input, output, UpdatePolicy.MAX_APK_BYTES, expectedSize, check);
                output.getFD().sync();
            }
            check.run();
            if (!partial.renameTo(target)) throw new IOException("Cannot finalize APK");
            complete = true;
            return target;
        } finally {
            if (!complete) partial.delete();
        }
    }

    private static void validateSize(long expectedSize) throws IOException {
        if (expectedSize <= 0 || expectedSize > UpdatePolicy.MAX_APK_BYTES) {
            throw new IOException("Invalid APK size");
        }
    }

    static void cleanStaleFiles(File directory, File keep, long now) {
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            // Leave recent files alone: a previous activity or the installer may still use them.
            if (!file.equals(keep) && file.isFile()
                    && file.getName().matches("[0-9a-fA-F-]{36}\\.apk(?:\\.part)?")
                    && now - file.lastModified() > 24L * 60 * 60 * 1000) {
                file.delete();
            }
        }
    }
}
