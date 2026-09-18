package com.skystream.ssmusicplayer;

final class VersionComparator {
    private VersionComparator() {
    }

    static int compare(String remoteTag, String current) {
        String[] remoteParts = remoteTag.replaceFirst("^[vV]", "").split("\\.");
        String[] currentParts = current.replaceFirst("^[vV]", "").split("\\.");
        for (int index = 0; index < Math.max(remoteParts.length, currentParts.length); index++) {
            int remote = index < remoteParts.length ? parsePart(remoteParts[index]) : 0;
            int local = index < currentParts.length ? parsePart(currentParts[index]) : 0;
            if (remote != local) {
                return Integer.compare(remote, local);
            }
        }
        return 0;
    }

    private static int parsePart(String part) {
        int end = 0;
        while (end < part.length() && Character.isDigit(part.charAt(end))) {
            end++;
        }
        try {
            return end == 0 ? 0 : Integer.parseInt(part.substring(0, end));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }
}
