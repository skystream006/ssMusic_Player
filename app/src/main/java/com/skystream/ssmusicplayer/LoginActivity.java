package com.skystream.ssmusicplayer;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.net.Uri;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AppCompatActivity;

public final class LoginActivity extends AppCompatActivity {
    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String loginUrl;
        try {
            loginUrl = ServerUrl.normalize(getIntent().getStringExtra("login_url"));
        } catch (Exception exception) {
            finish();
            return;
        }
        Uri serverUri = Uri.parse(loginUrl);
        WebView webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Uri destination = Uri.parse(url);
                return !"https".equalsIgnoreCase(destination.getScheme())
                        || !serverUri.getHost().equalsIgnoreCase(destination.getHost())
                        || effectiveHttpsPort(serverUri) != effectiveHttpsPort(destination);
            }
        });
        webView.loadUrl(loginUrl);
        setContentView(webView);
    }

    private static int effectiveHttpsPort(Uri uri) {
        return uri.getPort() == -1 ? 443 : uri.getPort();
    }
}
