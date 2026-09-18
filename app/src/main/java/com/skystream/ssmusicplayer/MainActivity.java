package com.skystream.ssmusicplayer;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends AppCompatActivity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<Song> songs = new ArrayList<>();
    private ArrayAdapter<Song> adapter;
    private String serverUrl;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        serverUrl = getSharedPreferences("settings", MODE_PRIVATE).getString("server_url", "");
        showLibrary();
    }

    private void showLibrary() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(32, 32, 32, 32);

        TextView title = new TextView(this);
        title.setText("ssMusic Player");
        title.setTextSize(24);
        page.addView(title);

        LinearLayout actions = new LinearLayout(this);
        Button connect = button("Server");
        connect.setOnClickListener(view -> configureServer());
        Button browser = button("Open server");
        browser.setOnClickListener(view -> openServer());
        Button refresh = button("Refresh");
        refresh.setOnClickListener(view -> loadSongs());
        Button download = button("Download");
        download.setOnClickListener(view -> requestDownload());
        actions.addView(connect);
        actions.addView(browser);
        actions.addView(refresh);
        actions.addView(download);
        page.addView(actions);

        ListView list = new ListView(this);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_2,
                android.R.id.text1, songs);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> queueSong(songs.get(position)));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            view.startDragAndDrop(null, new View.DragShadowBuilder(view), position, 0);
            return true;
        });
        list.setOnDragListener((view, event) -> onSongDrag(event));
        page.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout footer = new LinearLayout(this);
        Button settings = button("Settings");
        settings.setOnClickListener(view -> showSettings());
        Button updates = button("Updates");
        updates.setOnClickListener(view -> checkForUpdates());
        footer.addView(settings);
        footer.addView(updates);
        page.addView(footer);
        setContentView(page);
        if (!serverUrl.isEmpty()) {
            loadSongs();
        }
    }

    private boolean onSongDrag(DragEvent event) {
        if (event.getAction() != DragEvent.ACTION_DROP) {
            return true;
        }
        int from = (Integer) event.getLocalState();
        int to = Math.min(songs.size() - 1, Math.max(0,
                (int) (event.getY() / Math.max(1, ((ListView) event.getView()).getHeight() / songs.size()))));
        if (from != to) {
            Collections.swap(songs, from, to);
            adapter.notifyDataSetChanged();
            executor.execute(() -> {
                try {
                    new ServerClient(serverUrl).reorder(songs);
                } catch (Exception exception) {
                    showMessage("Could not save queue order.");
                }
            });
        }
        return true;
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        return button;
    }

    private void configureServer() {
        EditText input = new EditText(this);
        input.setHint("https://music.example.com");
        input.setText(serverUrl);
        new AlertDialog.Builder(this).setTitle("ssYTDLP server")
                .setMessage("Use the HTTPS URL for your ssYTDLP server.")
                .setView(input).setPositiveButton("Save", (dialog, which) -> {
                    try {
                        serverUrl = ServerUrl.normalize(input.getText().toString());
                        getSharedPreferences("settings", MODE_PRIVATE).edit()
                                .putString("server_url", serverUrl).apply();
                        startActivity(new Intent(this, LoginActivity.class)
                                .putExtra("login_url", serverUrl + "/login"));
                    } catch (IllegalArgumentException exception) {
                        showMessage(exception.getMessage());
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void openServer() {
        if (serverUrl.isEmpty()) {
            configureServer();
            return;
        }
        startActivity(new Intent(this, LoginActivity.class).putExtra("login_url", serverUrl));
    }

    private void loadSongs() {
        if (serverUrl.isEmpty()) {
            configureServer();
            return;
        }
        executor.execute(() -> {
            try {
                List<Song> loaded = new ServerClient(serverUrl).loadSongs();
                runOnUiThread(() -> {
                    songs.clear();
                    songs.addAll(loaded);
                    adapter.notifyDataSetChanged();
                });
            } catch (Exception exception) {
                DebugLogger.debug(this, "Library load failed: " + exception.getMessage());
                showMessage("Could not load the library.");
            }
        });
    }

    private void queueSong(Song song) {
        executor.execute(() -> {
            try {
                new ServerClient(serverUrl).queue(song);
                showMessage("Added to queue.");
            } catch (Exception exception) {
                showMessage("Could not add to queue.");
            }
        });
    }

    private void requestDownload() {
        EditText input = new EditText(this);
        input.setHint("YouTube Music URL");
        new AlertDialog.Builder(this).setTitle("Download music").setView(input)
                .setPositiveButton("Download", (dialog, which) -> executor.execute(() -> {
                    try {
                        new ServerClient(serverUrl).download(input.getText().toString());
                        showMessage("Download submitted.");
                    } catch (Exception exception) {
                        showMessage("Could not submit download.");
                    }
                })).setNegativeButton("Cancel", null).show();
    }

    private void showSettings() {
        CheckBox debug = new CheckBox(this);
        debug.setText("Enable debug logging");
        debug.setChecked(getSharedPreferences("settings", MODE_PRIVATE)
                .getBoolean("debug_logging", false));
        new AlertDialog.Builder(this).setTitle("Settings").setView(debug)
                .setPositiveButton("Save", (dialog, which) -> getSharedPreferences("settings", MODE_PRIVATE)
                        .edit().putBoolean("debug_logging", debug.isChecked()).apply())
                .setNegativeButton("Cancel", null).show();
    }

    private void checkForUpdates() {
        UpdateChecker.check(this, executor, (message, url) -> runOnUiThread(() -> {
            AlertDialog.Builder dialog = new AlertDialog.Builder(this).setMessage(message)
                    .setPositiveButton("OK", null);
            if (url != null && message.startsWith("Update ")) {
                dialog.setNegativeButton("Open", (ignored, ignoredWhich) ->
                        UpdateChecker.openRelease(this, url));
            }
            dialog.show();
        }));
    }

    private void showMessage(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
