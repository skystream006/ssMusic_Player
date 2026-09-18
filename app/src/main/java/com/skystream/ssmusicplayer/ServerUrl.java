package com.skystream.ssmusicplayer;

import java.net.URI;
import java.net.URISyntaxException;

final class ServerUrl {
    private ServerUrl() {
    }

    static String normalize(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Enter a valid HTTPS server URL.");
        }
        try {
            URI uri = new URI(value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("Enter an HTTPS server URL.");
            }
            return new URI("https", null, uri.getHost(), uri.getPort(),
                    trimTrailingSlash(uri.getPath()), null, null).toString();
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Enter a valid HTTPS server URL.", exception);
        }
    }

    private static String trimTrailingSlash(String path) {
        if (path == null || path.equals("/") || path.isEmpty()) {
            return "";
        }
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }
}
