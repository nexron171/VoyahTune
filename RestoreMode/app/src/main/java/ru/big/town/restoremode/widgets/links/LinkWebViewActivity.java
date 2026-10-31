package ru.big.town.restoremode.widgets.links;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import ru.big.town.restoremode.dashboard.DashboardProfile;

public final class LinkWebViewActivity extends Activity {
    private WebView webView;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String address = getIntent().getDataString();
        if (!LinkShortcutStore.isWebAddress(address)) {
            finish();
            return;
        }
        FrameLayout root = new FrameLayout(this);
        applyWindowInsets(root);
        webView = new WebView(this);
        webView.setWebChromeClient(
                new WebChromeClient() {
                    @Override
                    public boolean onConsoleMessage(ConsoleMessage message) {
                        if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                            Log.e("LinkWebView", "JavaScript: " + message.message());
                        }
                        return true;
                    }
                });
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(false);
        webView.getSettings().setAllowContentAccess(false);
        webView.setWebViewClient(
                new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(
                            WebView view, WebResourceRequest request) {
                        return !LinkShortcutStore.isWebAddress(request.getUrl().toString());
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(WebView view, String url) {
                        return !LinkShortcutStore.isWebAddress(url);
                    }
                });
        root.addView(
                webView,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView close = new TextView(this);
        close.setText("×");
        close.setTextSize(40);
        close.setTextColor(Color.WHITE);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription("Закрыть страницу");
        close.setFocusable(true);
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(0xEE202733);
        close.setBackground(background);
        close.setElevation(dp(8));
        close.setOnClickListener(view -> finish());
        FrameLayout.LayoutParams closeLayout =
                new FrameLayout.LayoutParams(dp(72), dp(72), Gravity.TOP | Gravity.LEFT);
        closeLayout.setMargins(dp(16), dp(16), dp(16), dp(16));
        root.addView(close, closeLayout);
        setContentView(root);
        if (state == null || webView.restoreState(state) == null) {
            webView.loadUrl(address);
        }
    }

    private void applyWindowInsets(FrameLayout root) {
        // The OEM driver dock overlays content without reporting a system inset.
        int nativeDock = DashboardProfile.displayId(this) == 0 ? dp(145) : 0;
        ViewCompat.setOnApplyWindowInsetsListener(
                root,
                (view, insets) -> {
                    Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                    int top = bars.top;
                    if (top == 0) {
                        int resource =
                                getResources()
                                        .getIdentifier("status_bar_height", "dimen", "android");
                        if (resource > 0) {
                            top = getResources().getDimensionPixelSize(resource);
                        }
                    }
                    view.setPadding(nativeDock + bars.left, top, bars.right, bars.bottom);
                    return insets;
                });
        root.setPadding(nativeDock, 0, 0, 0);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (webView != null) {
            webView.saveState(state);
        }
    }

    @Override
    protected void onPause() {
        if (webView != null) {
            webView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            ((ViewGroup) webView.getParent()).removeView(webView);
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
