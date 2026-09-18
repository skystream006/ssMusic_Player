package com.skystream.ssmusicplayer;

import org.json.JSONObject;

public final class Song {
    public final String id;
    public final String title;
    public final String artist;

    public Song(String id, String title, String artist) {
        this.id = id;
        this.title = title;
        this.artist = artist;
    }

    public static Song fromJson(JSONObject value) {
        return new Song(value.optString("id", value.optString("url")),
                value.optString("title", "Untitled"),
                value.optString("artist", value.optString("uploader")));
    }

    @Override
    public String toString() {
        return artist.isEmpty() ? title : title + "\n" + artist;
    }
}
