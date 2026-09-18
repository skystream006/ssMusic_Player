package com.skystream.ssmusicplayer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class ServerClient {
    private final String baseUrl;

    ServerClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    List<Song> loadSongs() throws Exception {
        JSONObject response = request("GET", "/api/library", null);
        JSONArray songs = response.optJSONArray("songs");
        if (songs == null) {
            songs = response.optJSONArray("items");
        }
        List<Song> result = new ArrayList<>();
        if (songs != null) {
            for (int index = 0; index < songs.length(); index++) {
                result.add(Song.fromJson(songs.getJSONObject(index)));
            }
        }
        return result;
    }

    void queue(Song song) throws Exception {
        JSONObject body = new JSONObject();
        body.put("id", song.id);
        request("POST", "/api/queue", body);
    }

    void reorder(List<Song> songs) throws Exception {
        JSONArray ids = new JSONArray();
        for (Song song : songs) {
            ids.put(song.id);
        }
        JSONObject body = new JSONObject();
        body.put("ids", ids);
        request("PUT", "/api/queue", body);
    }

    void download(String mediaUrl) throws Exception {
        JSONObject body = new JSONObject();
        body.put("url", mediaUrl);
        request("POST", "/api/download", body);
    }

    private JSONObject request(String method, String path, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(20_000);
        connection.setRequestProperty("Accept", "application/json");
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String response = read(stream);
        if (status >= 400) {
            throw new IllegalStateException("Server returned " + status + ": " + response);
        }
        return response.isEmpty() ? new JSONObject() : new JSONObject(response);
    }

    private static String read(InputStream stream) throws Exception {
        if (stream == null) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(stream, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                result.append(line);
            }
        }
        return result.toString();
    }
}
