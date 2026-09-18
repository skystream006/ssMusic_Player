package com.skystream.ssmusicplayer;

import java.math.BigInteger;

final class VersionComparator {
    private VersionComparator() {
    }

    static int compare(String remoteTag, String current) {
        String[] remoteParts = components(remoteTag);
        String[] currentParts = components(current);
        for (int index = 0; index < Math.max(remoteParts.length, currentParts.length); index++) {
            BigInteger remote = index < remoteParts.length
                    ? new BigInteger(remoteParts[index]) : BigInteger.ZERO;
            BigInteger local = index < currentParts.length
                    ? new BigInteger(currentParts[index]) : BigInteger.ZERO;
            int comparison = remote.compareTo(local);
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    static String displayVersion(String version) {
        components(version);
        return version.replaceFirst("^[vV]", "");
    }

    private static String[] components(String version) {
        if (version == null || version.length() > 128
                || !version.matches("[vV]?[0-9]+(?:\\.[0-9]+)*")) {
            throw new IllegalArgumentException("Not a stable numeric version");
        }
        return version.replaceFirst("^[vV]", "").split("\\.");
    }
}
