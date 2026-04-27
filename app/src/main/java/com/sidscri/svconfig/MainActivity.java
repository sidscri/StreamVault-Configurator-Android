package com.sidscri.svconfig;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends Activity {

    private WebView webView;

    private static final int REQ_OPEN_BACKUP = 1001;
    private static final int REQ_PICK_M3U    = 1002;
    private static final int REQ_SAVE_BACKUP = 1003;

    // Pending callback IDs waiting on system dialogs
    private String pendingOpenCb;
    private String pendingPickM3uCb;
    private String pendingSaveCb;
    private String pendingSaveRaw;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.parseColor("#12121a"));
            getWindow().setNavigationBarColor(Color.parseColor("#0a0a0f"));
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0a0a0f"));
        webView = new WebView(this);
        setupWebView();
        root.addView(webView, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        webView.loadUrl("file:///android_asset/index.html");
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setAllowUniversalAccessFromFileURLs(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        // Use a desktop-like UA so the configurator layout renders correctly
        ws.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 SVConfig/4.1");

        webView.addJavascriptInterface(new AndroidAPI(), "AndroidAPI");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                // Inject the window.API shim right after page load
                injectApiShim();
            }
        });
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }

    /** Injects window.API as a Promise-based wrapper around window.AndroidAPI */
    private void injectApiShim() {
        String shim =
            "(function(){" +
            "  if(typeof window.API!=='undefined')return;" +
            "  if(typeof window.AndroidAPI==='undefined')return;" +
            "  var __id=0;" +
            "  window.__svCb={};" +
            "  window.__svResolve=function(id,json){" +
            "    var fn=window.__svCb[id];" +
            "    if(fn){delete window.__svCb[id];" +
            "      try{fn(typeof json==='string'?JSON.parse(json):json);}catch(e){fn(null);}}" +
            "  };" +
            "  function wrap(method){" +
            "    return function(){" +
            "      var args=Array.prototype.slice.call(arguments);" +
            "      return new Promise(function(resolve){" +
            "        var id=String(++__id);" +
            "        window.__svCb[id]=resolve;" +
            "        window.AndroidAPI[method].apply(window.AndroidAPI,args.concat([id]));" +
            "      });" +
            "    };" +
            "  }" +
            "  window.API={" +
            "    openBackup:   wrap('openBackup')," +
            "    pickM3u:      wrap('pickM3u')," +
            "    readFile:     wrap('readFile')," +
            "    scanDrives:   wrap('scanDrives')," +
            "    networkPing:  wrap('networkPing')," +
            "    networkInject:wrap('networkInject')," +
            "    networkPull:  wrap('networkPull')," +
            "    networkScan:  wrap('networkScan')," +
            "    saveBackup:function(args){" +
            "      return new Promise(function(resolve){" +
            "        var id=String(++__id);" +
            "        window.__svCb[id]=resolve;" +
            "        window.AndroidAPI.saveBackup(args.raw||'',args.suggestedPath||'',id);" +
            "      });" +
            "    }," +
            "    saveInject:function(args){" +
            "      return new Promise(function(resolve){" +
            "        var id=String(++__id);" +
            "        window.__svCb[id]=resolve;" +
            "        window.AndroidAPI.saveInject(args.raw||'',id);" +
            "      });" +
            "    }" +
            "  };" +
            "})();";
        webView.evaluateJavascript(shim, null);
    }

    /** Called from Java to resolve a JS Promise by callback ID */
    private void resolveJs(String cbId, String json) {
        if (cbId == null || cbId.isEmpty()) return;
        runOnUiThread(() -> webView.evaluateJavascript(
            "window.__svResolve('" + cbId + "'," + json + ")", null));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Android API Bridge (called from JS via window.AndroidAPI.xxx)
    // ═══════════════════════════════════════════════════════════════════════
    public class AndroidAPI {

        // ── File: Open backup JSON ──────────────────────────────────────────
        @JavascriptInterface
        public void openBackup(String cbId) {
            runOnUiThread(() -> {
                pendingOpenCb = cbId;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                              | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(Intent.createChooser(intent, "Open StreamVault Backup"), REQ_OPEN_BACKUP);
            });
        }

        // ── File: Pick M3U ─────────────────────────────────────────────────
        @JavascriptInterface
        public void pickM3u(String cbId) {
            runOnUiThread(() -> {
                pendingPickM3uCb = cbId;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivityForResult(Intent.createChooser(intent, "Open M3U Playlist"), REQ_PICK_M3U);
            });
        }

        // ── File: Read file at path or content URI ──────────────────────────
        @JavascriptInterface
        public void readFile(String pathOrUri, String cbId) {
            new Thread(() -> {
                try {
                    String text = readTextFromPathOrUri(pathOrUri);
                    resolveJs(cbId, JSONObject.quote(text));
                } catch (Exception e) {
                    resolveJs(cbId, "null");
                    toastMain("Read error: " + e.getMessage());
                }
            }).start();
        }

        // ── File: Save backup JSON ─────────────────────────────────────────
        @JavascriptInterface
        public void saveBackup(String raw, String suggestedPath, String cbId) {
            runOnUiThread(() -> {
                pendingSaveCb  = cbId;
                pendingSaveRaw = raw;
                String fname = suggestedPath.isEmpty()
                    ? "streamvault-backup-" + datestamp() + ".json"
                    : new File(suggestedPath).getName();
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/json");
                intent.putExtra(Intent.EXTRA_TITLE, fname);
                startActivityForResult(Intent.createChooser(intent, "Save Backup As"), REQ_SAVE_BACKUP);
            });
        }

        // ── File: Save inject to Downloads/StreamVault/inject/ ─────────────
        @JavascriptInterface
        public void saveInject(String raw, String cbId) {
            new Thread(() -> {
                try {
                    File dir = new File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        "StreamVault/inject");
                    if (!dir.exists()) dir.mkdirs();
                    String fname = "streamvault-backup-" + datestamp() + ".json";
                    File dest = new File(dir, fname);
                    try (FileOutputStream fos = new FileOutputStream(dest)) {
                        fos.write(raw.getBytes(StandardCharsets.UTF_8));
                    }
                    resolveJs(cbId, JSONObject.quote(dest.getAbsolutePath()));
                    toastMain("Saved to Downloads/StreamVault/inject/");
                } catch (Exception e) {
                    resolveJs(cbId, "null");
                    toastMain("Save inject error: " + e.getMessage());
                }
            }).start();
        }

        // ── File: Scan drives for existing inject files ────────────────────
        @JavascriptInterface
        public void scanDrives(String cbId) {
            new Thread(() -> {
                try {
                    JSONArray results = new JSONArray();
                    File injectDir = new File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        "StreamVault/inject");
                    if (injectDir.exists()) {
                        File[] files = injectDir.listFiles(f -> f.getName().endsWith(".json"));
                        if (files != null) {
                            java.util.Arrays.sort(files,
                                (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                            for (File f : files) {
                                JSONObject obj = new JSONObject();
                                obj.put("drive", "Downloads/StreamVault");
                                obj.put("file",  f.getName());
                                obj.put("fullPath", f.getAbsolutePath());
                                results.put(obj);
                            }
                        }
                    }
                    resolveJs(cbId, results.toString());
                } catch (Exception e) {
                    resolveJs(cbId, "[]");
                }
            }).start();
        }

        // ── Network: Ping Fire TV ──────────────────────────────────────────
        @JavascriptInterface
        public void networkPing(String ip, int port, String cbId) {
            new Thread(() -> {
                try {
                    URL url = new URL("http://" + ip + ":" + port + "/ping");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(3000);
                    conn.setReadTimeout(3000);
                    conn.setRequestMethod("GET");
                    int code = conn.getResponseCode();
                    String body = readStream(conn.getInputStream());
                    conn.disconnect();
                    JSONObject result = new JSONObject();
                    result.put("ok", code == 200);
                    result.put("body", new JSONObject(body));
                    resolveJs(cbId, result.toString());
                } catch (Exception e) {
                    JSONObject result = new JSONObject();
                    try {
                        result.put("ok", false);
                        result.put("error", e.getMessage() != null ? e.getMessage()
                            : "No response — is StreamVault open on the device?");
                    } catch (Exception ignored) {}
                    resolveJs(cbId, result.toString());
                }
            }).start();
        }

        // ── Network: Push settings to Fire TV ─────────────────────────────
        @JavascriptInterface
        public void networkInject(String ip, int port, String raw, String cbId) {
            new Thread(() -> {
                try {
                    URL url = new URL("http://" + ip + ":" + port + "/inject");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json");
                    byte[] payload = raw.getBytes(StandardCharsets.UTF_8);
                    conn.setRequestProperty("Content-Length", String.valueOf(payload.length));
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(payload);
                    }
                    int code = conn.getResponseCode();
                    String body = readStream(code == 200 ? conn.getInputStream() : conn.getErrorStream());
                    conn.disconnect();
                    JSONObject result = new JSONObject();
                    result.put("ok", code == 200);
                    result.put("status", code);
                    result.put("body", body);
                    resolveJs(cbId, result.toString());
                } catch (Exception e) {
                    JSONObject result = new JSONObject();
                    try {
                        result.put("ok", false);
                        result.put("error", e.getMessage() != null ? e.getMessage() : "Connection failed");
                    } catch (Exception ignored) {}
                    resolveJs(cbId, result.toString());
                }
            }).start();
        }

        // ── Network: Pull current config from StreamVault (GET /backup) ────
        @JavascriptInterface
        public void networkPull(String ip, int port, String cbId) {
            new Thread(() -> {
                try {
                    URL url = new URL("http://" + ip + ":" + port + "/backup");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(12000);
                    conn.setRequestMethod("GET");
                    int code = conn.getResponseCode();
                    String body = readStream(code == 200 ? conn.getInputStream() : conn.getErrorStream());
                    conn.disconnect();
                    JSONObject result = new JSONObject();
                    result.put("ok", code == 200);
                    result.put("status", code);
                    if (code == 200) {
                        result.put("raw", body);
                    } else {
                        result.put("error", "HTTP " + code);
                    }
                    resolveJs(cbId, result.toString());
                } catch (Exception e) {
                    JSONObject result = new JSONObject();
                    try {
                        result.put("ok", false);
                        result.put("error", e.getMessage() != null ? e.getMessage() : "Connection failed");
                    } catch (Exception ignored) {}
                    resolveJs(cbId, result.toString());
                }
            }).start();
        }

        // ── Network: Scan LAN for StreamVault servers ──────────────────────
        // Scans the local /24 subnet in parallel (50 threads, 300ms socket timeout).
        // Each responding host is pinged at /ping to confirm StreamVault identity.
        // Returns JSON array of {ip, name, version, port} objects.
        @JavascriptInterface
        public void networkScan(int port, String cbId) {
            new Thread(() -> {
                try {
                    // Determine local IP via WifiManager
                    android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                        getApplicationContext().getSystemService(WIFI_SERVICE);
                    int ipInt = wm != null ? wm.getConnectionInfo().getIpAddress() : 0;
                    if (ipInt == 0) {
                        resolveJs(cbId, "[]");
                        return;
                    }
                    // Convert to dotted notation (little-endian on Android)
                    String localIp = String.format(Locale.US, "%d.%d.%d.%d",
                        (ipInt & 0xff), (ipInt >> 8 & 0xff),
                        (ipInt >> 16 & 0xff), (ipInt >> 24 & 0xff));
                    String[] parts = localIp.split("\\.");
                    if (parts.length < 3) { resolveJs(cbId, "[]"); return; }
                    String subnet = parts[0] + "." + parts[1] + "." + parts[2] + ".";

                    List<JSONObject> found = Collections.synchronizedList(new ArrayList<>());
                    ExecutorService pool = Executors.newFixedThreadPool(50);
                    CountDownLatch latch = new CountDownLatch(254);

                    for (int i = 1; i <= 254; i++) {
                        final String target = subnet + i;
                        pool.submit(() -> {
                            try {
                                // Fast TCP probe first — much faster than full HTTP
                                try (Socket sock = new Socket()) {
                                    sock.connect(new java.net.InetSocketAddress(target, port), 300);
                                }
                                // Port responded — confirm it's StreamVault via /ping
                                String pingBody = "";
                                try {
                                    URL url = new URL("http://" + target + ":" + port + "/ping");
                                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                                    conn.setConnectTimeout(1500);
                                    conn.setReadTimeout(1500);
                                    conn.setRequestMethod("GET");
                                    if (conn.getResponseCode() == 200) {
                                        pingBody = readStream(conn.getInputStream());
                                    }
                                    conn.disconnect();
                                } catch (Exception ignored) {}

                                JSONObject entry = new JSONObject();
                                entry.put("ip", target);
                                entry.put("port", port);
                                // Parse name/version from /ping response if available
                                try {
                                    JSONObject pong = new JSONObject(pingBody);
                                    entry.put("app",     pong.optString("app", "StreamVault"));
                                    entry.put("version", pong.optString("version", ""));
                                    entry.put("name",    pong.optString("name", target));
                                } catch (Exception e2) {
                                    entry.put("app",  "StreamVault");
                                    entry.put("name", target);
                                }
                                found.add(entry);
                            } catch (Exception ignored) {
                                // Port closed or host unreachable — skip silently
                            } finally {
                                latch.countDown();
                            }
                        });
                    }

                    pool.shutdown();
                    latch.await(12, TimeUnit.SECONDS);
                    pool.shutdownNow();

                    resolveJs(cbId, new JSONArray(found).toString());
                } catch (Exception e) {
                    resolveJs(cbId, "[]");
                }
            }).start();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // File picker result handling
    // ═══════════════════════════════════════════════════════════════════════
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_OPEN_BACKUP) {
            final String cbId = pendingOpenCb; pendingOpenCb = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData();
                persistReadUri(uri, data);
                new Thread(() -> {
                    try {
                        String text = readTextFromUri(uri);
                        JSONObject result = new JSONObject();
                        result.put("path", uri.toString());
                        result.put("raw", text);
                        resolveJs(cbId, result.toString());
                    } catch (Exception e) {
                        resolveJs(cbId, "null");
                        toastMain("Open error: " + e.getMessage());
                    }
                }).start();
            } else {
                resolveJs(cbId, "null");
            }
            return;
        }

        if (requestCode == REQ_PICK_M3U) {
            final String cbId = pendingPickM3uCb; pendingPickM3uCb = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData();
                persistReadUri(uri, data);
                new Thread(() -> {
                    try {
                        String text = readTextFromUri(uri);
                        JSONObject result = new JSONObject();
                        result.put("path", uri.toString());
                        result.put("raw", text);
                        resolveJs(cbId, result.toString());
                    } catch (Exception e) {
                        resolveJs(cbId, "null");
                    }
                }).start();
            } else {
                resolveJs(cbId, "null");
            }
            return;
        }

        if (requestCode == REQ_SAVE_BACKUP) {
            final String cbId = pendingSaveCb; pendingSaveCb = null;
            final String raw  = pendingSaveRaw; pendingSaveRaw = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData();
                new Thread(() -> {
                    try {
                        try (OutputStream os = getContentResolver().openOutputStream(uri, "w")) {
                            if (os == null) throw new Exception("Cannot open output stream");
                            os.write(raw.getBytes(StandardCharsets.UTF_8));
                        }
                        resolveJs(cbId, JSONObject.quote(uri.toString()));
                        toastMain("Saved ✓");
                    } catch (Exception e) {
                        resolveJs(cbId, "null");
                        toastMain("Save error: " + e.getMessage());
                    }
                }).start();
            } else {
                resolveJs(cbId, "null");
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════
    private String readTextFromPathOrUri(String pathOrUri) throws Exception {
        if (pathOrUri.startsWith("content://")) {
            return readTextFromUri(Uri.parse(pathOrUri));
        }
        return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(pathOrUri)),
            StandardCharsets.UTF_8);
    }

    private String readTextFromUri(Uri uri) throws Exception {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) throw new Exception("Cannot open " + uri);
            return readStream(is);
        }
    }

    private String readStream(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int len;
        while ((len = is.read(buf)) != -1) bos.write(buf, 0, len);
        return bos.toString(StandardCharsets.UTF_8.name());
    }

    private void persistReadUri(Uri uri, Intent data) {
        try {
            final int flags = data.getFlags()
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            getContentResolver().takePersistableUriPermission(uri, flags | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {}
    }

    private String datestamp() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    private void toastMain(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }

    @Override
    protected void onResume()  { super.onResume();  if (webView != null) webView.onResume(); }
    @Override
    protected void onPause()   { if (webView != null) webView.onPause(); super.onPause(); }
    @Override
    protected void onDestroy() { if (webView != null) { webView.stopLoading(); webView.destroy(); } super.onDestroy(); }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
