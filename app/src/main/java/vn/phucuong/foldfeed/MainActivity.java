package vn.phucuong.foldfeed;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import java.util.Locale;

/**
 * FoldFeed — trình xem Facebook tối ưu cho điện thoại gập (Galaxy Z Fold).
 *
 * Gập máy (màn hình ngoài, hẹp)  → 1 khung: bảng tin.
 * Mở máy (màn hình trong, rộng) → 2 khung: bảng tin bên trái, khung phụ bên phải
 *   (thông báo / tin nhắn / chợ / nhóm / video, hoặc link anh nhấn giữ để mở).
 * Gập/mở không làm tải lại trang, đăng nhập dùng chung cho cả 2 khung.
 */
public class MainActivity extends Activity {

    private static final String MOBILE_HOST = "m.facebook.com";
    private static final String DESKTOP_HOST = "www.facebook.com";
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/130.0.0.0 Safari/537.36";
    private static final int WIDE_DP = 600;      // từ 600dp trở lên coi là màn hình lớn
    private static final int REQ_FILE = 42;

    private WebView left, right, lastTouched;
    private FrameLayout leftPane, fullscreen;
    private LinearLayout panes, rightPane;
    private View divider;
    private ProgressBar leftProg, rightProg;
    private Button btnPc;

    private SharedPreferences prefs;
    private float ratio;          // tỉ lệ bề ngang khung trái (0.3 – 0.75)
    private int zoom;             // cỡ chữ %, áp dụng cả 2 khung
    private boolean wide;
    private boolean rightDesktop; // khung phải dùng bản máy tính
    private String mobileUa;

    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private ValueCallback<Uri[]> fileCallback;

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

        prefs = getSharedPreferences("foldfeed", MODE_PRIVATE);
        ratio = prefs.getFloat("ratio", 0.55f);
        zoom = prefs.getInt("zoom", 100);
        rightDesktop = prefs.getBoolean("rightDesktop", false);

        panes = findViewById(R.id.panes);
        leftPane = findViewById(R.id.leftPane);
        rightPane = findViewById(R.id.rightPane);
        divider = findViewById(R.id.divider);
        fullscreen = findViewById(R.id.fullscreen);
        left = findViewById(R.id.left);
        right = findViewById(R.id.right);
        leftProg = findViewById(R.id.leftProg);
        rightProg = findViewById(R.id.rightProg);
        btnPc = findViewById(R.id.btnPc);

        CookieManager.getInstance().setAcceptCookie(true);
        setupWebView(left, leftProg);
        setupWebView(right, rightProg);
        mobileUa = left.getSettings().getUserAgentString();
        applyRightUserAgent();
        lastTouched = left;

        setupLeftLongPress();
        setupDivider();
        setupToolbar();

        Bundle lb = state == null ? null : state.getBundle("left");
        Bundle rb = state == null ? null : state.getBundle("right");
        if (rb != null) right.restoreState(rb);
        Uri shared = facebookUriFrom(getIntent());
        if (shared != null) {
            left.loadUrl(shared.toString());
        } else if (lb == null || left.restoreState(lb) == null) {
            left.loadUrl(home(false));
        }

        applyLayout(getResources().getConfiguration());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Uri u = facebookUriFrom(intent);
        if (u != null) {
            if (wide) { right.loadUrl(u.toString()); lastTouched = right; }
            else left.loadUrl(u.toString());
        }
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        applyLayout(c); // gập/mở máy: chỉ đổi bố cục, không tải lại trang
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        Bundle lb = new Bundle();
        left.saveState(lb);
        out.putBundle("left", lb);
        if (right.getUrl() != null) {
            Bundle rb = new Bundle();
            right.saveState(rb);
            out.putBundle("right", rb);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        left.onResume();
        right.onResume();
    }

    @Override
    protected void onPause() {
        CookieManager.getInstance().flush(); // giữ đăng nhập
        left.onPause();
        right.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        left.destroy();
        right.destroy();
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (customView != null) { hideFullscreen(); return; }
        WebView target = (wide && lastTouched == right) ? right : left;
        if (target.canGoBack()) { target.goBack(); return; }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ bố cục

    private void applyLayout(Configuration c) {
        wide = c.screenWidthDp >= WIDE_DP;
        rightPane.setVisibility(wide ? View.VISIBLE : View.GONE);
        divider.setVisibility(wide ? View.VISIBLE : View.GONE);
        if (wide && right.getUrl() == null) right.loadUrl(home(rightDesktop) + "notifications/");
        if (!wide) lastTouched = left;
        applyWeights();
    }

    private void applyWeights() {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) leftPane.getLayoutParams();
        LinearLayout.LayoutParams rp = (LinearLayout.LayoutParams) rightPane.getLayoutParams();
        lp.weight = wide ? ratio : 1f;
        rp.weight = 1f - ratio;
        leftPane.setLayoutParams(lp);
        rightPane.setLayoutParams(rp);
    }

    private void setupDivider() {
        // Kéo thanh giữa để chỉnh độ rộng 2 khung; nhấn đúp để về mặc định.
        final long[] lastUp = {0};
        divider.setOnTouchListener((v, e) -> {
            float w = panes.getWidth();
            if (w <= 0) return false;
            int[] loc = new int[2];
            panes.getLocationOnScreen(loc);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    ratio = clamp((e.getRawX() - loc[0]) / w, 0.3f, 0.75f);
                    applyWeights();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    long now = System.currentTimeMillis();
                    if (now - lastUp[0] < 300) { ratio = 0.55f; applyWeights(); }
                    lastUp[0] = now;
                    prefs.edit().putFloat("ratio", ratio).apply();
                    return true;
                default:
                    return false;
            }
        });
    }

    private void setupToolbar() {
        bindNav(R.id.btnNoti, "notifications/");
        bindNav(R.id.btnMsg, "messages/");
        bindNav(R.id.btnMarket, "marketplace/");
        bindNav(R.id.btnGroups, "groups/");
        bindNav(R.id.btnWatch, "watch/");
        findViewById(R.id.btnBack).setOnClickListener(v -> { if (right.canGoBack()) right.goBack(); });
        findViewById(R.id.btnReload).setOnClickListener(v -> right.reload());
        btnPc.setOnClickListener(v -> {
            rightDesktop = !rightDesktop;
            prefs.edit().putBoolean("rightDesktop", rightDesktop).apply();
            applyRightUserAgent();
            right.loadUrl(swapHost(right.getUrl(), rightDesktop));
        });
        findViewById(R.id.btnZoomOut).setOnClickListener(v -> changeZoom(-10));
        findViewById(R.id.btnZoomIn).setOnClickListener(v -> changeZoom(10));
    }

    private void bindNav(int id, final String path) {
        findViewById(id).setOnClickListener(v -> {
            right.loadUrl(home(rightDesktop) + path);
            lastTouched = right;
        });
    }

    private void changeZoom(int delta) {
        zoom = Math.max(70, Math.min(200, zoom + delta));
        left.getSettings().setTextZoom(zoom);
        right.getSettings().setTextZoom(zoom);
        prefs.edit().putInt("zoom", zoom).apply();
        Toast.makeText(this, "Cỡ chữ: " + zoom + "%", Toast.LENGTH_SHORT).show();
    }

    private void applyRightUserAgent() {
        right.getSettings().setUserAgentString(rightDesktop ? DESKTOP_UA : mobileUa);
        btnPc.setText(rightDesktop ? R.string.mobile : R.string.pc);
    }

    // ------------------------------------------------------------------ WebView

    private void setupWebView(final WebView w, final ProgressBar bar) {
        WebSettings s = w.getSettings();
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
        s.setTextZoom(zoom);
        // Bỏ dấu "; wv" để Facebook coi như trình duyệt Chrome bình thường.
        s.setUserAgentString(s.getUserAgentString().replace("; wv", ""));
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);

        w.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) lastTouched = w;
            return false;
        });

        w.setDownloadListener((url, ua, cd, mime, len) -> openExternal(Uri.parse(url)));

        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return handleOutside(req.getUrl());
            }
        });

        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                bar.setProgress(p);
                bar.setVisibility(p < 100 ? View.VISIBLE : View.GONE);
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
                // Link mở "tab mới": màn hình lớn → khung phải; màn hình nhỏ → khung hiện tại.
                final WebView tmp = new WebView(MainActivity.this);
                tmp.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v2, WebResourceRequest req) {
                        routeNewWindow(req.getUrl(), w);
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

    private void routeNewWindow(Uri u, WebView from) {
        if (handleOutside(u)) return;
        if (wide) { right.loadUrl(u.toString()); lastTouched = right; }
        else from.loadUrl(u.toString());
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

    private static String home(boolean desktop) {
        return "https://" + (desktop ? DESKTOP_HOST : MOBILE_HOST) + "/";
    }

    private static String swapHost(String url, boolean desktop) {
        if (url == null) return home(desktop);
        Uri u = Uri.parse(url);
        String h = u.getHost();
        if (h == null || !(h.equals(MOBILE_HOST) || h.equals(DESKTOP_HOST)
                || h.equals("facebook.com"))) {
            return home(desktop);
        }
        return u.buildUpon().authority(desktop ? DESKTOP_HOST : MOBILE_HOST).build().toString();
    }

    // ------------------------------------------------------------------ nhấn giữ link

    private void setupLeftLongPress() {
        left.setOnLongClickListener(v -> {
            if (!wide) return false;
            WebView.HitTestResult r = left.getHitTestResult();
            int type = r.getType();
            if (type == WebView.HitTestResult.SRC_ANCHOR_TYPE && r.getExtra() != null) {
                showLinkMenu(r.getExtra());
                return true;
            }
            if (type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                left.requestFocusNodeHref(hrefHandler.obtainMessage());
                return true;
            }
            return false;
        });
    }

    private void showLinkMenu(final String url) {
        String[] items = {
                getString(R.string.open_right),
                getString(R.string.open_browser),
                getString(R.string.copy_link)
        };
        new AlertDialog.Builder(this)
                .setTitle(url)
                .setItems(items, (d, which) -> {
                    Uri u = Uri.parse(url);
                    if (which == 0) {
                        if (!handleOutside(u)) { right.loadUrl(url); lastTouched = right; }
                    } else if (which == 1) {
                        openExternal(u);
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

    private void openExternal(Uri u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (ActivityNotFoundException ex) {
            toast("Không có ứng dụng nào mở được link này");
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
