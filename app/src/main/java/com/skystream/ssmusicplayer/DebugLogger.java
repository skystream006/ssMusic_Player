package com.skystream.ssmusicplayer;

import android.content.Context;
import android.util.Log;

final class DebugLogger {
    private static final String TAG = "ssMusicPlayer";

    private DebugLogger() {
    }

    static void debug(Context context, String message) {
        if (context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean("debug_logging", false)) {
            Log.d(TAG, message);
        }
    }
}
