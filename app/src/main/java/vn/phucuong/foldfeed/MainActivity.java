package vn.phucuong.foldfeed;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import java.util.Locale;

/**
 * FoldFeed — trình xem Facebook một khung, toàn màn hình, cho Galaxy Z Fold.
 * Gập/mở máy không tải lại trang. Tự kiểm tra và cài bản mới từ GitHub.
 */
public class MainActivity extends Activity {

    private static final String HOME = "https://m.facebook.com/";
    private static final int REQ_FILE = 42;

    private WebView web;
    private FrameLayout fullscreen;
    private ProgressBar progress;

    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private ValueCallback<Uri[]> fileCallback;

    private Updater updater;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Handler hrefHandler = new Handler(Looper.getMainLooper(), msg -> {
        String url = msg.getData().getString("url");
        if (url != null && !url.isEmpty()) showLinkMenu(url);
        return true;
    });

    // ------------------------------------------------------------------ vòng đời

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);

        web = findViewById(R.id.web);
        fullscreen = findViewById(R.id.fullscreen);
        progress = findViewById(R.id.progress);
        updater = new Updater(this);

        CookieManager.getInstance().setAcceptCookie(true);
        setupWebView();

        Uri shared = facebookUriFrom(getIntent());
        Bundle saved = state == null ? null : state.getBundle("web");
        if (shared != null) {
            web.loadUrl(shared.toString());
        } else if (saved == null || web.restoreState(saved) == null) {
            web.loadUrl(HOME);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Uri u = facebookUriFrom(intent);
        if (u != null) web.loadUrl(u.toString());
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        Bundle b = new Bundle();
        web.saveState(b);
        out.putBundle("web", b);
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        updater.checkIfDue(); // tự kiểm tra bản mới
    }

    @Override
    protected void onPause() {
        CookieManager.getInstance().flush(); // giữ đăng nhập
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (customView != null) { hideFullscreen(); return; }
        if (web.canGoBack()) { web.goBack(); return; }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ WebView

    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        // Bỏ dấu "; wv" để Facebook coi như trình duyệt Chrome bình thường.
        s.setUserAgentString(s.getUserAgentString().replace("; wv", ""));
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setDownloadListener((url, ua, cd, mime, len) -> openExternal(Uri.parse(url)));

        web.setOnLongClickListener(v -> {
            WebView.HitTestResult r = web.getHitTestResult();
            int type = r.getType();
            if (type == WebView.HitTestResult.SRC_ANCHOR_TYPE && r.getExtra() != null) {
                showLinkMenu(r.getExtra());
                return true;
            }
            if (type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                web.requestFocusNodeHref(hrefHandler.obtainMessage());
                return true;
            }
            return false;
        });

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return handleOutside(req.getUrl());
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                progress.setProgress(p);
                progress.setVisibility(p < 100 ? View.VISIBLE : View.GONE);
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                Intent i = params.createIntent();
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                        params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                try {
                    startActivityForResult(i, REQ_FILE);
                    return true;
                } catch (ActivityNotFoundException ex) {
                    fileCallback = null;
                    toast("Không mở được trình chọn ảnh");
                    return false;
                }
            }

            @Override
            public void onShowCustomView(View v, CustomViewCallback cb) {
                showFullscreen(v, cb);
            }

            @Override
            public void onHideCustomView() {
                hideFullscreen();
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean dialog, boolean userGesture,
                                          Message resultMsg) {
                // Link mở "tab mới" → mở luôn trong khung hiện tại.
                final WebView tmp = new WebView(MainActivity.this);
                tmp.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v2, WebResourceRequest req) {
                        Uri u = req.getUrl();
                        if (!handleOutside(u)) web.loadUrl(u.toString());
                        mainHandler.post(tmp::destroy);
                        return true;
                    }
                });
                WebView.WebViewTransport t = (WebView.WebViewTransport) resultMsg.obj;
                t.setWebView(tmp);
                resultMsg.sendToTarget();
                return true;
            }
        });
    }

    /** true = link không thuộc Facebook, đã chuyển ra ngoài (hoặc bỏ qua). */
    private boolean handleOutside(Uri u) {
        if (isFacebook(u)) return false;
        String scheme = u.getScheme();
        if (scheme == null) return true;
        scheme = scheme.toLowerCase(Locale.ROOT);
        // intent:// và fb:// là Facebook đòi mở app chính thức → bỏ qua, ở lại app này.
        if (scheme.equals("intent") || scheme.startsWith("fb")) return true;
        openExternal(u); // link ngoài, tel:, mailto:, zalo... → mở bằng app tương ứng
        return true;
    }

    private static boolean isFacebook(Uri u) {
        String s = u.getScheme(), h = u.getHost();
        if (s == null || h == null) return false;
        if (!s.equalsIgnoreCase("http") && !s.equalsIgnoreCase("https")) return false;
        h = h.toLowerCase(Locale.ROOT);
        return h.equals("facebook.com") || h.endsWith(".facebook.com")
                || h.equals("fb.com") || h.endsWith(".fb.com")
                || h.equals("fb.me") || h.equals("fb.watch")
                || h.endsWith(".fbcdn.net") || h.endsWith(".fbsbx.com")
                || h.equals("messenger.com") || h.endsWith(".messenger.com");
    }

    private static Uri facebookUriFrom(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return null;
        Uri u = intent.getData();
        return (u != null && isFacebook(u)) ? u : null;
    }

    private void showLinkMenu(final String url) {
        String[] items = {getString(R.string.open_browser), getString(R.string.copy_link)};
        new AlertDialog.Builder(this)
                .setTitle(url)
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        openExternal(Uri.parse(url));
                    } else {
                        ClipboardManager cm = getSystemService(ClipboardManager.class);
                        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("link", url));
                        toast("Đã sao chép link");
                    }
                })
                .show();
    }

    // ------------------------------------------------------------------ video toàn màn hình

    @SuppressWarnings("deprecation")
    private void showFullscreen(View v, WebChromeClient.CustomViewCallback cb) {
        if (customView != null) { cb.onCustomViewHidden(); return; }
        customView = v;
        customCallback = cb;
        fullscreen.addView(v, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        fullscreen.setVisibility(View.VISIBLE);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @SuppressWarnings("deprecation")
    private void hideFullscreen() {
        if (customView == null) return;
        fullscreen.removeView(customView);
        fullscreen.setVisibility(View.GONE);
        customView = null;
        if (customCallback != null) customCallback.onCustomViewHidden();
        customCallback = null;
        getWindow().getDecorView().setSystemUiVisibility(0);
    }

    // ------------------------------------------------------------------ chọn ảnh để đăng

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILE && fileCallback != null) {
            Uri[] out = null;
            if (res == RESULT_OK && data != null) {
                ClipData clip = data.getClipData();
                if (clip != null && clip.getItemCount() > 0) {
                    out = new Uri[clip.getItemCount()];
                    for (int i = 0; i < out.length; i++) out[i] = clip.getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    out = new Uri[]{data.getData()};
                }
            }
            fileCallback.onReceiveValue(out);
            fileCallback = null;
            return;
        }
        super.onActivityResult(req, res, data);
    }

    // ------------------------------------------------------------------ tiện ích

    void openExternal(Uri u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (ActivityNotFoundException ex) {
            toast("Không có ứng dụng nào mở được link này");
        }
    }

    void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
