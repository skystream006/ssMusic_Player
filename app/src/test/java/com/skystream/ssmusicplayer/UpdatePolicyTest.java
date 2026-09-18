package com.skystream.ssmusicplayer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.net.URL;

public class UpdatePolicyTest {
    private static final String ASSET =
            "https://github.com/skystream006/ssMusic_Player/releases/download/v1.2/player.apk";
    private static final String PACKAGE = "com.skystream.ssmusicplayer";

    @Test
    public void metadataIsRestrictedToExactRepositoryEndpoint() throws Exception {
        assertTrue(UpdatePolicy.isAllowedUrl(new URL(UpdatePolicy.LATEST_URL), true, true));
        assertTrue(UpdatePolicy.isAllowedUrl(new URL(UpdatePolicy.LATEST_URL), true, false));
        String[] rejected = {ASSET, UpdatePolicy.LATEST_URL + "?anything=1",
                UpdatePolicy.LATEST_URL.replace("ssMusic_Player", "ssMusic"),
                UpdatePolicy.LATEST_URL.replace("https:", "http:"),
                UpdatePolicy.LATEST_URL.replace("api.github.com", "api.github.com.evil.example")};
        for (String url : rejected) {
            assertFalse(url, UpdatePolicy.isAllowedUrl(new URL(url), true, true));
            assertFalse(url, UpdatePolicy.isAllowedUrl(new URL(url), true, false));
        }
    }

    @Test
    public void initialApkMustBeAnAssetInThisRepository() throws Exception {
        assertTrue(UpdatePolicy.isAllowedUrl(new URL(ASSET), false, true));
        assertTrue(UpdatePolicy.isAllowedUrl(new URL(ASSET.replace("github.com",
                "github.com:443")), false, true));
        String[] rejected = {ASSET.replace("https:", "http:"),
                ASSET.replace("github.com", "github.com.evil.example"),
                ASSET.replace("github.com", "evil@github.com"),
                ASSET.replace("github.com", "github.com:8443"),
                ASSET.replace("ssMusic_Player", "ssMusic"),
                ASSET.replace("releases/download", "blob"),
                ASSET.replace("v1.2", ".."), ASSET.replace("v1.2", "%2e%2e"),
                ASSET.replace("player.apk", "%2fother.apk"),
                ASSET.replace("player.apk", "nested/player.apk"),
                ASSET.replace(".apk", ".zip"), ASSET + "?redirect=evil", ASSET + "#fragment"};
        for (String url : rejected) {
            assertFalse(url, UpdatePolicy.isAllowedUrl(new URL(url), false, true));
            assertFalse(url, UpdatePolicy.isAllowedUrl(new URL(url), false, false));
        }
    }

    @Test
    public void onlyTrustedHttpsCdnRedirectsAreAllowed() throws Exception {
        String[] hosts = {"release-assets.githubusercontent.com", "objects.githubusercontent.com",
                "github-releases.githubusercontent.com"};
        for (String host : hosts) {
            URL url = new URL("https://" + host + "/asset?signature=example");
            assertTrue(UpdatePolicy.isAllowedUrl(url, false, false));
            assertFalse(UpdatePolicy.isAllowedUrl(url, false, true));
            assertFalse(UpdatePolicy.isAllowedUrl(url, true, false));
            assertFalse(UpdatePolicy.isAllowedUrl(
                    new URL("http://" + host + "/asset"), false, false));
            assertFalse(UpdatePolicy.isAllowedUrl(
                    new URL("https://" + host + ".evil.example/asset"), false, false));
        }
        assertFalse(UpdatePolicy.isAllowedUrl(
                new URL("https://raw.githubusercontent.com/file.apk"), false, false));
    }

    @Test
    public void archiveMustBeSamePackageAndStrictlyNewerInBothVersions() {
        assertTrue(matches(PACKAGE, 2, "1.1", "v1.1.0"));
        assertFalse(matches("com.skystream.ssmusic", 2, "1.1", "1.1"));
        assertFalse(matches(PACKAGE, 1, "1.1", "1.1"));
        assertFalse(matches(PACKAGE, 0, "1.1", "1.1"));
        assertFalse(matches(PACKAGE, 2, "1.0", "1.0"));
        assertFalse(matches(PACKAGE, 2, "0.9", "0.9"));
        assertFalse(matches(PACKAGE, 2, "1.1", "1.2"));
        assertFalse(matches(PACKAGE, 2, "1.1-beta", "1.1-beta"));
        assertFalse(matches(PACKAGE, 2, null, "1.1"));
        assertFalse(matches(PACKAGE, 2, "1.1", null));
        assertFalse(UpdatePolicy.archiveMatches(null, 1, "1", PACKAGE, 2, "2", "2"));
        assertTrue(UpdatePolicy.archiveMatches(PACKAGE, 4294967296L, "1",
                PACKAGE, 4294967297L, "2", "2"));
    }

    private static boolean matches(String name, long code, String version, String release) {
        return UpdatePolicy.archiveMatches(PACKAGE, 1, "1.0", name, code, version, release);
    }
}
