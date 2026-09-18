package com.skystream.ssmusicplayer;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

final class UpdatePolicy {
    static final String LATEST_URL =
            "https://api.github.com/repos/skystream006/ssMusic_Player/releases/latest";
    static final long MAX_APK_BYTES = 100L * 1024 * 1024;
    static final int MAX_METADATA_BYTES = 1024 * 1024;
    private static final String DOWNLOAD_PATH =
            "/skystream006/ssMusic_Player/releases/download/";

    private UpdatePolicy() {}

    static boolean isAllowedUrl(URL url, boolean metadata, boolean initial) {
        try {
            URI uri = url.toURI();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || uri.getFragment() != null || uri.getHost() == null
                    || !uri.normalize().getRawPath().equals(uri.getRawPath())) {
                return false;
            }
            if (metadata) return LATEST_URL.equals(url.toExternalForm());
            String host = uri.getHost();
            if ("github.com".equalsIgnoreCase(host)) {
                String path = uri.getRawPath();
                // Reject encoded separators/dot segments as well as literal traversal.
                return path.startsWith(DOWNLOAD_PATH)
                        && path.substring(DOWNLOAD_PATH.length())
                                .matches("[a-zA-Z0-9_.-]+/[a-zA-Z0-9_.-]+\\.apk")
                        && uri.getRawQuery() == null;
            }
            return !initial && ("release-assets.githubusercontent.com".equalsIgnoreCase(host)
                    || "objects.githubusercontent.com".equalsIgnoreCase(host)
                    || "github-releases.githubusercontent.com".equalsIgnoreCase(host));
        } catch (URISyntaxException | IllegalArgumentException e) {
            return false;
        }
    }

    static boolean archiveMatches(String installedPackage, long installedCode,
            String installedVersion, String archivePackage, long archiveCode,
            String archiveVersion, String releaseVersion) {
        try {
            return installedPackage != null && installedPackage.equals(archivePackage)
                    && archiveCode > installedCode
                    && VersionComparator.compare(archiveVersion, installedVersion) > 0
                    && VersionComparator.compare(archiveVersion, releaseVersion) == 0;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
