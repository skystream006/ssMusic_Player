package com.skystream.ssmusicplayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class ServerUrlTest {
    @Test
    public void normalizesHttpsUrl() {
        assertEquals("https://server.example/api",
                ServerUrl.normalize(" https://server.example/api/ "));
    }

    @Test
    public void rejectsInsecureAndCredentialUrls() {
        assertThrows(IllegalArgumentException.class,
                () -> ServerUrl.normalize("http://server.example"));
        assertThrows(IllegalArgumentException.class,
                () -> ServerUrl.normalize("https://user@server.example"));
    }

    @Test
    public void comparesReleaseVersionsNumerically() {
        assertEquals(1, UpdateChecker.compareVersions("v0.10.0", "0.9.9"));
        assertEquals(0, UpdateChecker.compareVersions("v1.0", "1.0.0"));
        assertEquals(-1, UpdateChecker.compareVersions("v1.2.3", "1.3.0"));
    }
}
