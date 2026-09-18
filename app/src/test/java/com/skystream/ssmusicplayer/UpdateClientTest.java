package com.skystream.ssmusicplayer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public class UpdateClientTest {
    private final File directory = new File("build/update-tests", UUID.randomUUID().toString());

    @Before
    public void createDirectory() {
        assertTrue(directory.mkdirs());
    }

    @After
    public void removeDirectory() throws IOException {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) Files.delete(file.toPath());
        }
        Files.deleteIfExists(directory.toPath());
    }

    @Test
    public void boundedCopyAcceptsExactLimitAndUnknownLength() throws Exception {
        byte[] bytes = new byte[UpdatePolicy.MAX_METADATA_BYTES];
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        UpdateClient.copy(new ByteArrayInputStream(bytes), output, bytes.length, -1, () -> {});
        assertArrayEquals(bytes, output.toByteArray());
    }

    @Test
    public void copyRejectsOversizeAndTruncatedResponses() {
        assertThrows(IOException.class, () -> UpdateClient.copy(
                new ByteArrayInputStream(new byte[5]), new ByteArrayOutputStream(),
                4, -1, () -> {}));
        assertThrows(IOException.class, () -> UpdateClient.copy(
                new ByteArrayInputStream(new byte[5]), new ByteArrayOutputStream(),
                10, 4, () -> {}));
        assertThrows(IOException.class, () -> UpdateClient.copy(
                new ByteArrayInputStream(new byte[3]), new ByteArrayOutputStream(),
                10, 4, () -> {}));
    }

    @Test
    public void successfulSavePublishesOnlyCompleteUniqueApks() throws Exception {
        byte[] bytes = {1, 2, 3, 4};
        File first = UpdateClient.saveAtomically(new ByteArrayInputStream(bytes), directory,
                bytes.length, () -> {
                    for (File file : directory.listFiles()) {
                        assertTrue(file.getName().endsWith(".part"));
                    }
                });
        assertTrue(first.getName().matches("[0-9a-f-]{36}\\.apk"));
        assertArrayEquals(bytes, Files.readAllBytes(first.toPath()));
        File second = UpdateClient.saveAtomically(new ByteArrayInputStream(bytes), directory,
                bytes.length, () -> {});
        assertFalse(first.equals(second));
        assertEquals(2, directory.listFiles().length);
    }

    @Test
    public void failedSavesLeaveNoPartialOrCompleteFiles() {
        assertThrows(IOException.class, () -> UpdateClient.saveAtomically(
                new ByteArrayInputStream(new byte[3]), directory, 4, () -> {}));
        assertEquals(0, directory.listFiles().length);
        assertThrows(IOException.class, () -> UpdateClient.saveAtomically(
                new ByteArrayInputStream(new byte[5]), directory, 4, () -> {}));
        assertEquals(0, directory.listFiles().length);
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("Disconnected");
            }
        };
        assertThrows(IOException.class, () -> UpdateClient.saveAtomically(
                broken, directory, 4, () -> {}));
        assertEquals(0, directory.listFiles().length);
    }

    @Test
    public void cancellationBeforeDuringAndAfterCopyNeverPublishesAnApk() {
        for (int cancelAt : new int[]{1, 2, 5}) {
            AtomicInteger checks = new AtomicInteger();
            assertThrows(InterruptedIOException.class, () -> UpdateClient.saveAtomically(
                    new ByteArrayInputStream(new byte[4]), directory, 4, () -> {
                        if (checks.incrementAndGet() == cancelAt) {
                            throw new InterruptedIOException("Cancelled");
                        }
                    }));
            assertEquals(0, directory.listFiles().length);
        }
    }

    @Test
    public void invalidDeclaredSizesAreRejectedBeforeWriting() {
        for (long size : new long[]{-1, 0, UpdatePolicy.MAX_APK_BYTES + 1}) {
            assertThrows(IOException.class, () -> UpdateClient.saveAtomically(
                    new ByteArrayInputStream(new byte[0]), directory, size, () -> {}));
        }
        assertEquals(0, directory.listFiles().length);
    }

    @Test
    public void failedAtomicRenameRemovesPartialFile() {
        AtomicInteger checks = new AtomicInteger();
        assertThrows(IOException.class, () -> UpdateClient.saveAtomically(
                new ByteArrayInputStream(new byte[4]), directory, 4, () -> {
                    if (checks.incrementAndGet() == 5) {
                        File partial = directory.listFiles()[0];
                        File target = new File(directory, partial.getName().replace(".part", ""));
                        assertTrue(target.mkdir());
                    }
                }));
        assertEquals(1, directory.listFiles().length);
        assertTrue(directory.listFiles()[0].isDirectory());
    }

    @Test
    public void cancelledClientAndUntrustedUrlFailBeforeConnecting() {
        UpdateClient cancelled = new UpdateClient();
        cancelled.cancel();
        assertThrows(InterruptedIOException.class, cancelled::latestRelease);
        assertThrows(InterruptedIOException.class, () -> cancelled.download(
                "https://github.com/skystream006/ssMusic_Player/releases/download/v2/app.apk",
                4, directory));
        assertThrows(IOException.class, () -> new UpdateClient().download(
                "https://example.com/app.apk", 4, directory));
        assertEquals(0, directory.listFiles().length);
    }

    @Test
    public void cleanupRemovesOnlyStaleUpdaterFilesAndPreservesPendingApk() throws Exception {
        long now = System.currentTimeMillis();
        long old = now - 48L * 60 * 60 * 1000;
        File stale = file(".apk", old);
        File partial = file(".apk.part", old);
        File pending = file(".apk", old);
        File recent = file(".apk", now);
        File other = new File(directory, "other-file");
        assertTrue(other.createNewFile());
        assertTrue(other.setLastModified(old));
        UpdateClient.cleanStaleFiles(directory, pending, now);
        assertFalse(stale.exists());
        assertFalse(partial.exists());
        assertTrue(pending.exists());
        assertTrue(recent.exists());
        assertTrue(other.exists());
    }

    private File file(String suffix, long modified) throws IOException {
        File file = new File(directory, UUID.randomUUID() + suffix);
        assertTrue(file.createNewFile());
        assertTrue(file.setLastModified(modified));
        return file;
    }
}
