package com.termux.app.nocode;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.HttpAuthHandler;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.R;

/**
 * The NO CODE app main screen: the real OpenCode web UI running inside a WebView.
 * The OpenCode server itself runs inside the proot Ubuntu environment and serves
 * its own genuine web interface on 127.0.0.1.
 */
public class NocodeMainActivity extends Activity {

    private static final String LOG_TAG = "NocodeMainActivity";

    /** How long the web UI may take to load before we show a diagnostic instead of a black screen. */
    private static final long UI_TIMEOUT_MS = 60_000;

    private WebView mWebView;
    private View mLoadingOverlay;
    private View mErrorOverlay;
    private TextView mErrorText;
    private TextView mLoadingText;
    private boolean mLoaded = false;
    private boolean mPageRequested = false;
    private boolean mLoadFailed = false;

    /** File picker opened from the web UI (attachments). */
    private static final int REQUEST_FILE_CHOOSER = 1001;
    private ValueCallback<Uri[]> mFileChooserCallback;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private final Runnable mLoadTimeout = () -> {
        if (mLoaded) return;
        String message = getString(R.string.nocode_ui_timeout);
        NocodeLog.append("OpenCode UI did not finish loading within " + (UI_TIMEOUT_MS / 1000) + "s");
        showError(message);
    };

    private String baseUrl() {
        return "http://127.0.0.1:" + NocodeManager.OPENCODE_PORT + "/";
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // The setup already keeps the screen on; the web UI needs the same, otherwise the
        // screen blacks out while a long answer is being written.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_nocode_main);

        mWebView = findViewById(R.id.nocode_webview);
        mLoadingOverlay = findViewById(R.id.nocode_loading_overlay);
        mErrorOverlay = findViewById(R.id.nocode_error_overlay);
        mErrorText = findViewById(R.id.nocode_error_text);
        mLoadingText = findViewById(R.id.nocode_loading_text);

        Button retryButton = findViewById(R.id.nocode_retry_button);
        Button browserButton = findViewById(R.id.nocode_error_browser_button);
        Button copyButton = findViewById(R.id.nocode_error_copy_button);
        Button terminalButton = findViewById(R.id.nocode_error_terminal_button);

        retryButton.setOnClickListener(v -> openServer());
        browserButton.setOnClickListener(v -> openInBrowser());
        copyButton.setOnClickListener(v -> copyDiagnostics());
        terminalButton.setOnClickListener(v -> startActivity(new Intent(this, com.termux.app.TermuxActivity.class)));

        mWebView.getSettings().setJavaScriptEnabled(true);
        mWebView.getSettings().setDomStorageEnabled(true);
        mWebView.getSettings().setJavaScriptCanOpenWindowsAutomatically(false);
        mWebView.getSettings().setSupportZoom(true);
        mWebView.getSettings().setBuiltInZoomControls(true);
        mWebView.getSettings().setDisplayZoomControls(false);
        mWebView.getSettings().setUseWideViewPort(true);
        // Do not zoom the page out to fit a "desktop" width: the OpenCode UI is responsive
        // and must be laid out at the real device width on a phone.
        mWebView.getSettings().setLoadWithOverviewMode(false);
        // Slightly larger text; 13px is the web UI's base size and is small on a phone.
        mWebView.getSettings().setTextZoom(110);
        mWebView.setVerticalScrollBarEnabled(false);
        mWebView.setBackgroundColor(0xFFFAFAFA);

        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // Only the local OpenCode server runs inside the WebView; everything
                // else (docs links etc.) opens in the system browser.
                if (url != null && url.startsWith(baseUrl())) {
                    return false;
                }
                if (url != null && url.startsWith("http")) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    } catch (Exception ignored) {}
                }
                return true;
            }

            @Override
            public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler,
                                                  String host, String realm) {
                // The local OpenCode server may protect the API with HTTP basic auth.
                handler.proceed(NocodeManager.OPENCODE_USERNAME, NocodeManager.OPENCODE_PASSWORD);
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                NocodeLog.append("WebView page started: " + url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url != null && url.startsWith(baseUrl()) && !mLoadFailed) {
                    showWebView();
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request == null || !request.isForMainFrame()) return;
                String detail = error == null ? "unknown error"
                    : (error.getErrorCode() + " " + error.getDescription());
                NocodeLog.append("WebView load error: " + detail + " (" + request.getUrl() + ")");
                showError(getString(R.string.nocode_web_load_error, detail));
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                            android.webkit.WebResourceResponse errorResponse) {
                super.onReceivedHttpError(view, request, errorResponse);
                if (request == null || !request.isForMainFrame()) return;
                int code = errorResponse == null ? 0 : errorResponse.getStatusCode();
                NocodeLog.append("WebView HTTP error " + code + " for " + request.getUrl());
                if (code == 401 || code == 403) {
                    showError(getString(R.string.nocode_web_auth_error, String.valueOf(code)));
                } else if (code >= 400) {
                    showError(getString(R.string.nocode_web_http_error, String.valueOf(code)));
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                NocodeLog.append("WebView renderer crashed, recreating it");
                showError(getString(R.string.nocode_web_renderer_gone));
                return true;
            }
        });

        mWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                // Attachments in the OpenCode UI are <input type="file"> elements; without
                // this handler Android WebView silently ignores them.
                if (mFileChooserCallback != null) {
                    mFileChooserCallback.onReceiveValue(null);
                }
                mFileChooserCallback = callback;
                try {
                    Intent intent = params.createIntent();
                    startActivityForResult(intent, REQUEST_FILE_CHOOSER);
                    return true;
                } catch (Exception e) {
                    NocodeLog.append("Could not open the file picker: " + e);
                    mFileChooserCallback = null;
                    return false;
                }
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                if (consoleMessage != null && consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    NocodeLog.append("WebView console: " + consoleMessage.message());
                }
                return true;
            }
        });

        openServer();
    }

    /** Makes sure the OpenCode server is running, then loads its web UI. */
    private void openServer() {
        mLoaded = false;
        mPageRequested = false;
        mLoadFailed = false;
        mHandler.removeCallbacks(mLoadTimeout);

        mLoadingOverlay.setVisibility(View.VISIBLE);
        mErrorOverlay.setVisibility(View.GONE);
        // The WebView is only made visible once the page really finished loading, so the
        // user never sits in front of a blank surface.
        mWebView.setVisibility(View.INVISIBLE);
        mLoadingText.setText(R.string.nocode_starting_opencode);

        NocodeManager.ensureServerRunning(this, (ready, message) -> {
            if (isFinishing() || isDestroyed()) return;
            if (ready) {
                mLoadingText.setText(R.string.nocode_loading_ui);
                mPageRequested = true;
                mWebView.loadUrl(baseUrl());
                mHandler.postDelayed(mLoadTimeout, UI_TIMEOUT_MS);
            } else {
                NocodeLog.append("OpenCode server did not start: " + message);
                showError(getString(R.string.nocode_web_error));
            }
        });
    }

    /** Reveals the WebView and hides the black loading overlay. */
    private void showWebView() {
        if (mLoadFailed) return;
        mLoaded = true;
        mHandler.removeCallbacks(mLoadTimeout);
        mErrorOverlay.setVisibility(View.GONE);
        mLoadingOverlay.setVisibility(View.GONE);
        mWebView.setVisibility(View.VISIBLE);
        syncSystemBars();
        mHandler.postDelayed(this::syncSystemBars, 2000);
    }

    /** Tints the status/navigation bars with the web UI background so the app looks native. */
    private void syncSystemBars() {
        mWebView.evaluateJavascript(
            "(function(){try{var c=getComputedStyle(document.body).backgroundColor;return c||''}catch(e){return ''}})()",
            value -> {
                Integer color = parseCssColor(value);
                if (color != null) applySystemBarColor(color);
            });
    }

    private void applySystemBarColor(int color) {
        try {
            getWindow().setStatusBarColor(color);
            getWindow().setNavigationBarColor(color);
            boolean light = isLightColor(color);
            int flags = getWindow().getDecorView().getSystemUiVisibility();
            if (light) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (light) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                } else {
                    flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
            }
            getWindow().getDecorView().setSystemUiVisibility(flags);
        } catch (Exception ignored) {
        }
    }

    private static boolean isLightColor(int color) {
        double luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color)
            + 0.114 * Color.blue(color)) / 255.0;
        return luminance > 0.6;
    }

    /** Parses the value returned by getComputedStyle(...).backgroundColor. */
    private static Integer parseCssColor(String value) {
        if (value == null) return null;
        String text = value.trim();
        if (text.startsWith("\"") && text.endsWith("\"") && text.length() > 2) {
            text = text.substring(1, text.length() - 1);
        }
        try {
            if (text.startsWith("#")) {
                return Color.parseColor(text);
            }
            if (text.startsWith("rgb")) {
                String body = text.substring(text.indexOf('(') + 1, text.indexOf(')'));
                String[] parts = body.split(",");
                if (parts.length >= 3) {
                    int r = (int) Math.round(Double.parseDouble(parts[0].trim()));
                    int g = (int) Math.round(Double.parseDouble(parts[1].trim()));
                    int b = (int) Math.round(Double.parseDouble(parts[2].trim()));
                    return Color.rgb(r, g, b);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Shows a readable diagnostic instead of an endless black screen. */
    private void showError(String message) {
        mLoadFailed = true;
        mHandler.removeCallbacks(mLoadTimeout);
        mLoadingOverlay.setVisibility(View.GONE);
        mWebView.setVisibility(View.GONE);
        mErrorOverlay.setVisibility(View.VISIBLE);
        mErrorText.setText(message + "\n\n" + getString(R.string.nocode_web_error_hint));
    }

    private void openInBrowser() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(baseUrl()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            Toast.makeText(this, getString(R.string.nocode_web_password_toast), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, String.valueOf(e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void copyDiagnostics() {
        StringBuilder text = new StringBuilder();
        text.append("NO CODE ").append(NocodeManager.BUILD_LABEL).append('\n');
        text.append("error: ").append(mErrorText.getText()).append('\n');
        text.append("server up: ").append(NocodeManager.isServerUp()).append('\n');
        text.append("--- app log ---\n").append(NocodeLog.tail(200));
        String serverLog = readServerLog();
        if (!serverLog.isEmpty()) {
            text.append("--- server log ---\n").append(serverLog);
        }
        try {
            ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("NO CODE log", text.toString()));
            }
            Toast.makeText(this, R.string.nocode_log_copied, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, String.valueOf(e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    /** Last lines of the OpenCode server output, if the script managed to write it. */
    private String readServerLog() {
        java.io.File file = new java.io.File(
            com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH,
            NocodeManager.SERVER_LOG_RELATIVE);
        if (!file.isFile()) return "";
        java.util.ArrayDeque<String> lines = new java.util.ArrayDeque<>();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
            new java.io.InputStreamReader(new java.io.FileInputStream(file), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.addLast(line);
                if (lines.size() > 60) lines.removeFirst();
            }
        } catch (Exception e) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String line : lines) builder.append(line).append('\n');
        return builder.toString();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Back goes back in the web UI history instead of leaving the app
        if (keyCode == KeyEvent.KEYCODE_BACK && mWebView.canGoBack()) {
            mWebView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mWebView != null) mWebView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mWebView != null) mWebView.onResume();
        // On every app open make sure the environment/server is still alive.
        if (!NocodeManager.isServerUp() && !isFinishing()) {
            openServer();
        } else if (mLoaded) {
            mWebView.setVisibility(View.VISIBLE);
        } else if (mPageRequested && mWebView.getVisibility() != View.VISIBLE) {
            // The page was requested while the app was in the background; retry cleanly.
            openServer();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_FILE_CHOOSER) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    ClipData clip = data.getClipData();
                    result = new Uri[clip.getItemCount()];
                    for (int i = 0; i < clip.getItemCount(); i++) {
                        result[i] = clip.getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
            }
            if (mFileChooserCallback != null) {
                mFileChooserCallback.onReceiveValue(result);
                mFileChooserCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onDestroy() {
        mHandler.removeCallbacks(mLoadTimeout);
        if (mFileChooserCallback != null) {
            mFileChooserCallback.onReceiveValue(null);
            mFileChooserCallback = null;
        }
        if (mWebView != null) {
            mWebView.destroy();
        }
        super.onDestroy();
    }
}
