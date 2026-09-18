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
        void onResult(String message, String releaseUrl);
    }

    private static final String RELEASE_URL =
            "https://api.github.com/repos/skystream006/ssMusic_Player/releases/latest";

    static void check(Context context, Executor executor, Listener listener) {
        executor.execute(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(RELEASE_URL).openConnection();
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                JSONObject release = new JSONObject(ServerClientRead.read(connection.getInputStream()));
                String tag = release.optString("tag_name");
                String current = context.getPackageManager().getPackageInfo(
                        context.getPackageName(), 0).versionName;
                listener.onResult(tag.equals("v" + current) ? "You are up to date." :
                        "Update " + tag + " is available.", release.optString("html_url"));
            } catch (Exception exception) {
                listener.onResult("Unable to check for updates.", null);
            }
        });
    }

    static void openRelease(Context context, String url) {
        context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }
}
