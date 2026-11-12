package io.github.eddiew2.sector8;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hosts the Sector 8 game (assets/www/index.html) in a full-screen WebView.
 * The page is served from https://appassets.androidplatform.net so it gets a real
 * origin: localStorage (saves, scores, credits) persists between launches.
 *
 * Self-update: on launch (at most every 6 hours) and from Settings, the app reads the
 * repo's "latest" GitHub release, and if it holds a newer Sector8-x.y.N.apk it offers
 * to download and install it.
 */
public class MainActivity extends Activity implements SensorEventListener {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/assets/www/index.html";
    private static final String RELEASE_API = "https://api.github.com/repos/eddie-w2/block-puzzle/releases/tags/latest";
    private static final Pattern APK_NAME = Pattern.compile("Sector8-(\\d+)\\.(\\d+)\\.(\\d+)\\.apk");
    private static final long AUTO_CHECK_EVERY_MS = 6L * 60 * 60 * 1000;

    private WebView web;
    private String updateUrl;
    private String updateVersion;
    private File downloadedApk;
    private boolean waitingForInstallPermission;
    private volatile boolean downloading;

    // Shake to drop: the game turns this on from its Settings
    private SensorManager sensors;
    private volatile boolean shakeWanted;
    private boolean shakeListening;
    private long firstPeakAt, lastShakeAt;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        web.setBackgroundColor(0xFF04060C);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportZoom(false);
        s.setTextZoom(100); // ignore system font scaling so the layout stays intact

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (HOST.equals(uri.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) { }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                maybeAutoCheck();
            }
        });

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.loadUrl(START_URL);
    }

    /* ---------------- self-update ---------------- */

    private void maybeAutoCheck() {
        SharedPreferences p = getSharedPreferences("updates", MODE_PRIVATE);
        long last = p.getLong("lastCheck", 0);
        if (System.currentTimeMillis() - last < AUTO_CHECK_EVERY_MS) return;
        p.edit().putLong("lastCheck", System.currentTimeMillis()).apply();
        checkForUpdate(false);
    }

    private int currentVersionCode() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) { return 0; }
    }

    private String currentVersionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception e) { return "?"; }
    }

    private void checkForUpdate(final boolean manual) {
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(RELEASE_API).openConnection();
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("User-Agent", "Sector8-app");
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                if (c.getResponseCode() != 200) {
                    if (manual) report("error", "Couldn't reach the update server. Try again later.", null, 0);
                    return;
                }
                JSONObject release = new JSONObject(readAll(c.getInputStream()));
                JSONArray assets = release.getJSONArray("assets");
                int best = -1; String url = null, version = null;
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    Matcher m = APK_NAME.matcher(a.getString("name"));
                    if (!m.matches()) continue;
                    int build = Integer.parseInt(m.group(3));
                    if (build > best) {
                        best = build;
                        url = a.getString("browser_download_url");
                        version = m.group(1) + "." + m.group(2) + "." + m.group(3);
                    }
                }
                if (best > currentVersionCode() && url != null) {
                    updateUrl = url; updateVersion = version;
                    report("available", null, version, 0);
                } else if (manual) {
                    report("none", null, null, 0);
                }
            } catch (Exception e) {
                if (manual) report("error", "Couldn't check for updates. Check your connection.", null, 0);
            }
        }).start();
    }

    private void downloadAndInstall() {
        if (updateUrl == null || downloading) return;
        if (downloadedApk != null && downloadedApk.exists()) { launchInstaller(); return; }
        downloading = true;
        new Thread(() -> {
            try {
                File dir = new File(getCacheDir(), "updates");
                if (!dir.exists()) dir.mkdirs();
                File[] old = dir.listFiles();
                if (old != null) for (File f : old) f.delete();
                File out = new File(dir, "Sector8-" + updateVersion + ".apk");

                HttpURLConnection c = (HttpURLConnection) new URL(updateUrl).openConnection();
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", "Sector8-app");
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                int code = c.getResponseCode();
                // GitHub redirects release downloads to its file host
                for (int hops = 0; hops < 5 && code >= 300 && code < 400; hops++) {
                    String next = c.getHeaderField("Location");
                    c.disconnect();
                    c = (HttpURLConnection) new URL(next).openConnection();
                    c.setRequestProperty("User-Agent", "Sector8-app");
                    code = c.getResponseCode();
                }
                if (code != 200) throw new Exception("HTTP " + code);
                long total = c.getContentLengthLong(), got = 0;
                int lastPct = -1;
                try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(out)) {
                    byte[] buf = new byte[32 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        got += n;
                        int pct = total > 0 ? (int) (got * 100 / total) : 0;
                        if (pct != lastPct) { lastPct = pct; report("downloading", null, updateVersion, pct); }
                    }
                }
                downloadedApk = out;
                runOnUiThread(this::launchInstaller);
            } catch (Exception e) {
                report("error", "The download didn't finish. Try again.", null, 0);
            } finally {
                downloading = false;
            }
        }).start();
    }

    private void launchInstaller() {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            // one-time: Android asks the player to allow this app to install updates
            waitingForInstallPermission = true;
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        try {
            web.evaluateJavascript("window.sector8Save && window.sector8Save()", null);
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".updates", downloadedApk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            report("error", "Android couldn't open the installer.", null, 0);
        }
    }

    private void report(String state, String msg, String version, int pct) {
        try {
            JSONObject o = new JSONObject();
            o.put("state", state);
            if (msg != null) o.put("msg", msg);
            if (version != null) o.put("version", version);
            o.put("pct", pct);
            o.put("current", currentVersionName());
            final String js = "window.sector8Update && window.sector8Update(" + o + ")";
            runOnUiThread(() -> web.evaluateJavascript(js, null));
        } catch (Exception ignored) { }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toString("UTF-8");
    }

    /* ---------------- shake to drop ---------------- */

    private void updateShakeListener() {
        if (sensors == null) sensors = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensors == null) return;
        Sensor acc = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (shakeWanted && !shakeListening && acc != null) {
            shakeListening = sensors.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME);
        } else if (!shakeWanted && shakeListening) {
            sensors.unregisterListener(this);
            shakeListening = false;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        float x = e.values[0], y = e.values[1], z = e.values[2];
        double g = Math.sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH;
        if (g < 2.3) return;
        long now = System.currentTimeMillis();
        // a shake is two strong jolts within 0.8 s, at most once per second
        if (now - firstPeakAt > 800) { firstPeakAt = now; return; }
        if (now - lastShakeAt < 1000) return;
        lastShakeAt = now; firstPeakAt = 0;
        web.evaluateJavascript("window.sector8Shake && window.sector8Shake()", null);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    /* ---------------- activity plumbing ---------------- */

    /** Back button: the game pauses / closes its menus first, and only exits from the home screen. */
    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.sector8Back ? window.sector8Back() : false)", result -> {
            if (!"true".equals(result)) finish();
        });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    @Override
    protected void onPause() {
        web.evaluateJavascript("window.sector8Save && window.sector8Save()", null);
        if (shakeListening && sensors != null) { sensors.unregisterListener(this); shakeListening = false; }
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        updateShakeListener();
        if (waitingForInstallPermission && Build.VERSION.SDK_INT >= 26 && getPackageManager().canRequestPackageInstalls()) {
            waitingForInstallPermission = false;
            launchInstaller();
        }
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }

    /** Everything the page can ask of the app. */
    class Bridge {
        private final Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        @JavascriptInterface
        public String appVersion() { return currentVersionName(); }

        @JavascriptInterface
        public void checkUpdate() { checkForUpdate(true); }

        @JavascriptInterface
        public void setShake(boolean on) { shakeWanted = on; runOnUiThread(MainActivity.this::updateShakeListener); }

        @JavascriptInterface
        public void installUpdate() { runOnUiThread(MainActivity.this::downloadAndInstall); }

        /** Native haptics: the WebView has no Vibration API, so the game calls this instead. */
        @JavascriptInterface
        public void vibrate(String json) {
            if (vibrator == null || !vibrator.hasVibrator()) return;
            try {
                JSONArray a = new JSONArray(json);
                if (a.length() == 0) return;
                if (a.length() == 1) {
                    long ms = Math.max(1, a.getLong(0));
                    if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
                    else vibrator.vibrate(ms);
                    return;
                }
                // web pattern is [on, off, on, ...]; Android expects [delay, on, off, ...]
                long[] p = new long[a.length() + 1];
                for (int i = 0; i < a.length(); i++) p[i + 1] = a.getLong(i);
                if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createWaveform(p, -1));
                else vibrator.vibrate(p, -1);
            } catch (Exception ignored) { }
        }
    }
}
