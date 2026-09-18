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
}
