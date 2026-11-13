/*
 * Custom server downloader flyout integration.
 * Copyright 2026.
 */
package app.morphe.extension.youtube.videoplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.utils.FlyoutUtils;

/**
 * Sends the selected YouTube video to the user's self-hosted downloader.
 */
@SuppressWarnings("unused")
public final class ServerDownloadButton {
    private static final String PREFS = "morphe_server_downloader";
    private static final String LAN_HOST = "http://192.168.68.52:5050";
    private static final String VPN_HOST = "http://100.108.154.30:5050";
    private static final String KEY_USER = "user";
    private static final String KEY_VPN = "vpn";
    private static final String KEY_AUDIO = "audio";
    private static final String KEY_USERS = "users";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private static Drawable icon;

    private ServerDownloadButton() { }

    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false; // Set by the patcher.
    }

    public static int addFlyoutButton(Object panel, int index, String videoId) {
        if (videoId == null || videoId.isEmpty()) return index;
        try {
            if (icon == null) icon = ResourceUtils.getDrawable(ShortsDownloadButton.DOWNLOAD_ICON);
            return FlyoutUtils.addFlyoutButton(panel, icon, "Send to my server",
                    view -> {
                        FlyoutUtils.dismissFlyout();
                        show(videoId);
                    }, index);
        } catch (Exception ex) {
            Logger.printException(() -> "ServerDownloadButton addFlyoutButton failure", ex);
            return index;
        }
    }

    private static void show(String videoId) {
        Activity activity = Utils.getActivity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;

        var prefs = activity.getSharedPreferences(PREFS, 0);
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * activity.getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad / 2, pad, 0);

        TextView urlText = new TextView(activity);
        String videoUrl = "https://www.youtube.com/watch?v=" + videoId;
        urlText.setText(videoUrl);
        urlText.setTextIsSelectable(true);
        layout.addView(urlText);

        TextView userLabel = new TextView(activity);
        userLabel.setText("Gebruiker");
        layout.addView(userLabel);

        Spinner usersSpinner = new Spinner(activity);
        layout.addView(usersSpinner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Switch vpnSwitch = new Switch(activity);
        vpnSwitch.setText("Gebruik Tailscale/VPN");
        vpnSwitch.setChecked(prefs.getBoolean(KEY_VPN, false));
        layout.addView(vpnSwitch);

        RadioGroup formatGroup = new RadioGroup(activity);
        formatGroup.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton videoOption = new RadioButton(activity);
        videoOption.setText("Video");
        videoOption.setId(View.generateViewId());
        RadioButton audioOption = new RadioButton(activity);
        audioOption.setText("Audio");
        audioOption.setId(View.generateViewId());
        formatGroup.addView(videoOption);
        formatGroup.addView(audioOption);
        formatGroup.check(prefs.getBoolean(KEY_AUDIO, false) ? audioOption.getId() : videoOption.getId());
        layout.addView(formatGroup);

        TextView status = new TextView(activity);
        status.setText("Gebruikerslijst laden...");
        layout.addView(status);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Send to my server")
                .setView(layout)
                .setNegativeButton("Annuleren", null)
                .setPositiveButton("Download", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            Button download = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            download.setEnabled(false);
            download.setOnClickListener(v -> {
                Object selected = usersSpinner.getSelectedItem();
                if (selected == null) {
                    status.setText("Selecteer eerst een gebruiker.");
                    return;
                }
                String user = selected.toString();
                boolean vpn = vpnSwitch.isChecked();
                boolean audio = formatGroup.getCheckedRadioButtonId() == audioOption.getId();
                prefs.edit().putString(KEY_USER, user).putBoolean(KEY_VPN, vpn)
                        .putBoolean(KEY_AUDIO, audio).apply();
                download.setEnabled(false);
                status.setText("Download wordt verzonden...");
                sendDownload(activity, dialog, status, download, videoUrl, user, vpn, audio);
            });
        });
        dialog.show();

        loadUsers(activity, usersSpinner, status, dialog, prefs, vpnSwitch, videoOption, audioOption);
        vpnSwitch.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean(KEY_VPN, checked).apply();
            status.setText("Gebruikerslijst laden...");
            loadUsers(activity, usersSpinner, status, dialog, prefs, vpnSwitch, videoOption, audioOption);
        });
    }

    private static void loadUsers(Activity activity, Spinner spinner, TextView status,
                                  AlertDialog dialog, android.content.SharedPreferences prefs,
                                  Switch vpnSwitch, RadioButton videoOption, RadioButton audioOption) {
        final boolean vpn = vpnSwitch.isChecked();
        EXECUTOR.execute(() -> {
            List<String> users = new ArrayList<>();
            try {
                HttpURLConnection connection = open(vpn, "/api/users", "GET", 15000);
                try {
                    if (connection.getResponseCode() != 200) throw new Exception("HTTP " + connection.getResponseCode());
                    JSONObject json = new JSONObject(read(connection.getInputStream()));
                    JSONArray array = json.getJSONArray("users");
                    for (int i = 0; i < array.length(); i++) users.add(array.getString(i));
                } finally { connection.disconnect(); }
                prefs.edit().putString(KEY_USERS, new JSONArray(users).toString()).apply();
            } catch (Exception ex) {
                Logger.printException(() -> "Could not load server users", ex);
                try {
                    JSONArray cached = new JSONArray(prefs.getString(KEY_USERS, "[]"));
                    for (int i = 0; i < cached.length(); i++) users.add(cached.getString(i));
                } catch (Exception ignored) { }
                String saved = prefs.getString(KEY_USER, "");
                if (!saved.isEmpty() && !users.contains(saved)) users.add(saved);
            }

            activity.runOnUiThread(() -> {
                if (dialog.isShowing()) {
                    if (users.isEmpty()) {
                        status.setText("Geen gebruikerslijst beschikbaar. Verbind eerst via je thuisnetwerk.");
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                        return;
                    }
                    ArrayAdapter<String> adapter = new ArrayAdapter<>(activity,
                            android.R.layout.simple_spinner_item, users);
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                    spinner.setAdapter(adapter);
                    String saved = prefs.getString(KEY_USER, "");
                    int selected = users.indexOf(saved);
                    if (selected >= 0) spinner.setSelection(selected);
                    status.setText("Server verbonden.");
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                }
            });
        });
    }

    private static void sendDownload(Activity activity, AlertDialog dialog, TextView status,
                                     Button button, String videoUrl, String user, boolean vpn, boolean audio) {
        EXECUTOR.execute(() -> {
            String error = null;
            try {
                JSONObject body = new JSONObject();
                body.put("url", videoUrl);
                body.put("user", user);
                HttpURLConnection connection = open(vpn, audio ? "/download_audio" : "/download",
                        "POST", 20000);
                try {
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                    try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
                    int code = connection.getResponseCode();
                    InputStream stream = code >= 200 && code < 400
                            ? connection.getInputStream() : connection.getErrorStream();
                    String response = stream == null ? "" : read(stream);
                    JSONObject json = response.isEmpty() ? new JSONObject() : new JSONObject(response);
                    if (code < 200 || code >= 300) {
                        error = json.optString("error", json.optString("message", "HTTP " + code));
                    } else {
                        String message = json.optString("message", "Download succesvol verzonden.");
                        activity.runOnUiThread(() -> {
                            if (dialog.isShowing()) {
                                status.setText(message);
                                button.setEnabled(false);
                                status.postDelayed(dialog::dismiss, 1000);
                            }
                        });
                    }
                } finally { connection.disconnect(); }
            } catch (Exception ex) {
                error = ex.getMessage() == null ? "Verbinding mislukt." : ex.getMessage();
                Logger.printException(() -> "Server download request failed", ex);
            }
            if (error != null) {
                final String message = error;
                activity.runOnUiThread(() -> {
                    if (dialog.isShowing()) {
                        status.setText("Fout: " + message);
                        button.setText("Opnieuw proberen");
                        button.setEnabled(true);
                    }
                });
            }
        });
    }

    private static HttpURLConnection open(boolean vpn, String path, String method, int timeout) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL((vpn ? VPN_HOST : LAN_HOST) + path)
                .openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(timeout);
        connection.setReadTimeout(timeout);
        return connection;
    }

    private static String read(InputStream stream) throws Exception {
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }
}
