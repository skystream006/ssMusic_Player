package com.skystream.ssmusicplayer;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executor;

final class UpdateChecker {
    interface Listener {
        void onResult(String message, String releaseUrl, boolean updateAvailable);
    }

    private static final String RELEASE_URL =
            "https://api.github.com/repos/skystream006/ssMusic_Player/releases/latest";

    static void check(Context context, Executor executor, Listener listener) {
        executor.execute(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(RELEASE_URL).openConnection();
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                JSONObject release = new JSONObject(StreamReader.readUtf8(connection.getInputStream()));
                String tag = release.optString("tag_name");
                String current = context.getPackageManager().getPackageInfo(
                        context.getPackageName(), 0).versionName;
                boolean updateAvailable = compareVersions(tag, current) > 0;
                listener.onResult(updateAvailable ? "Update " + tag + " is available." :
                        "You are up to date.", updateAvailable ? release.optString("html_url") : null,
                        updateAvailable);
            } catch (Exception exception) {
                listener.onResult("Unable to check for updates.", null, false);
            }
        });
    }

    static void openRelease(Context context, String url) {
        context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }

    static int compareVersions(String remoteTag, String current) {
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
        try {
            return Integer.parseInt(part.replaceAll("[^0-9].*$", ""));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }
}
