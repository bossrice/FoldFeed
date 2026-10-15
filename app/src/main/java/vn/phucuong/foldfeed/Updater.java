package vn.phucuong.foldfeed;

import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Tự cập nhật: mỗi lần mở app (tối đa 30 phút/lần) đọc version.json trên GitHub Releases.
 * Có bản mới → tự tải APK về → hỏi "Cài ngay?" → cài đè bằng PackageInstaller.
 */
final class Updater {

    private static final String BASE =
            "https://github.com/bossrice/FoldFeed/releases/download/latest/";
    private static final long INTERVAL_MS = 30 * 60 * 1000L;

    private final MainActivity act;
    private final SharedPreferences prefs;
    private volatile boolean busy;

    Updater(MainActivity act) {
        this.act = act;
        this.prefs = act.getSharedPreferences("updater", Context.MODE_PRIVATE);
    }

    void checkIfDue() {
        long now = System.currentTimeMillis();
        if (busy || now - prefs.getLong("lastCheck", 0) < INTERVAL_MS) return;
        prefs.edit().putLong("lastCheck", now).apply();
        busy = true;
        new Thread(this::check, "foldfeed-updater").start();
    }

    @SuppressWarnings("deprecation")
    private void check() {
        try {
            final JSONObject v = new JSONObject(readText(BASE + "version.json"));
            final int remote = v.getInt("versionCode");
            int current = act.getPackageManager()
                    .getPackageInfo(act.getPackageName(), 0).versionCode;
            if (remote <= current) { busy = false; return; }

            final File apk = new File(act.getCacheDir(), "update.apk");
            download(BASE + "FoldFeed.apk", apk);

            PackageInfo info = act.getPackageManager().getPackageArchiveInfo(apk.getPath(), 0);
            if (info == null || !act.getPackageName().equals(info.packageName)
                    || info.versionCode <= current) {
                throw new IOException("APK tải về không hợp lệ");
            }
            final String name = v.optString("versionName", String.valueOf(remote));
            final String notes = v.optString("notes", "");
            act.runOnUiThread(() -> askInstall(apk, name, notes));
        } catch (Exception e) {
            busy = false; // không có mạng / chưa có bản phát hành → thử lại lần sau
        }
    }

    private void askInstall(File apk, String name, String notes) {
        if (act.isFinishing() || act.isDestroyed()) { busy = false; return; }
        String msg = "FoldFeed " + name + " đã tải xong."
                + (notes.isEmpty() ? "" : "\n\n" + notes)
                + "\n\nCài xong anh mở lại app là dùng bản mới.";
        new AlertDialog.Builder(act)
                .setTitle("Có bản cập nhật mới")
                .setMessage(msg)
                .setCancelable(false)
                .setPositiveButton("Cài ngay", (d, w) -> install(apk))
                .setNegativeButton("Để sau", (d, w) -> busy = false)
                .show();
    }

    private void install(File apk) {
        PackageManager pm = act.getPackageManager();
        if (!pm.canRequestPackageInstalls()) {
            act.toast("Bật \"Cho phép từ nguồn này\" cho FoldFeed, rồi quay lại app");
            prefs.edit().putLong("lastCheck", 0).apply(); // quay lại là kiểm tra tiếp
            busy = false;
            try {
                act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + act.getPackageName())));
            } catch (Exception ignored) { }
            return;
        }
        act.toast("Đang cài bản mới…");
        new Thread(() -> {
            try {
                commit(apk);
            } catch (Exception e) {
                busy = false;
                act.runOnUiThread(() -> act.toast("Cập nhật lỗi: " + e.getMessage()));
            }
        }, "foldfeed-install").start();
    }

    private void commit(File apk) throws IOException {
        PackageInstaller pi = act.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(act.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) {
            // Android 12+: từ lần cập nhật thứ 2 trở đi có thể cài luôn, không cần bấm xác nhận.
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int id = pi.createSession(params);
        PackageInstaller.Session session = pi.openSession(id);
        try {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent result = PendingIntent.getBroadcast(
                    act, id, new Intent(act, UpdateReceiver.class), flags);
            session.commit(result.getIntentSender());
        } finally {
            session.close();
        }
    }

    // ------------------------------------------------------------------ mạng

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        c.setUseCaches(false);
        int code = c.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            c.disconnect();
            throw new IOException("HTTP " + code);
        }
        return c;
    }

    private static String readText(String url) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    private static void download(String url, File dest) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            c.disconnect();
        }
    }
}
