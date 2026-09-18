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
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(RELEASE_URL).openConnection();
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(20_000);
                JSONObject release = new JSONObject(StreamReader.readUtf8(connection.getInputStream()));
                String tag = release.optString("tag_name");
                String current = context.getPackageManager().getPackageInfo(
                        context.getPackageName(), 0).versionName;
                boolean updateAvailable = VersionComparator.compare(tag, current) > 0;
                listener.onResult(updateAvailable ? "Update " + tag + " is available." :
                        "You are up to date.", updateAvailable ? release.optString("html_url") : null,
                        updateAvailable);
            } catch (Exception exception) {
                listener.onResult("Unable to check for updates.", null, false);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    static void openRelease(Context context, String url) {
        context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }

}
