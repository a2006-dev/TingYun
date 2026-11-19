package com.tingyun.app;

import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.view.Gravity;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.webkit.WebViewAssetLoader;

import com.tingyun.app.bridge.TingYunBridge;
import com.tingyun.app.data.LibraryStore;
import com.tingyun.app.data.Prefs;
import com.tingyun.app.player.AmbientEngine;
import com.tingyun.app.player.AudioEngine;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "TingYun";

    private static final String ASSET_BASE = "https://appassets.androidplatform.net/assets/";
    private static final String ENTRY_URL = ASSET_BASE + "index.html";

    private WebView web;
    private View cover;
    private FrameLayout root;

    private AudioEngine audio;
    private AmbientEngine amb;
    private LibraryStore library;
    private Prefs prefs;
    private TingYunBridge bridge;

    private boolean immersive = false;

    private String savedScene = "";
    private int savedIndex = -1;
    private long savedPos = 0L;
    private boolean savedOnPlayer = false;

    private static final int REQ_NOTIFY = 0x7E01;

    private com.tingyun.app.player.MediaSessionHolder media;

    private androidx.activity.result.ActivityResultLauncher<String[]> pickFilesLauncher;
    private androidx.activity.result.ActivityResultLauncher<android.net.Uri> pickTreeLauncher;
    
    private androidx.activity.result.ActivityResultLauncher<String[]> pickWallpaperLauncher;
    
    private androidx.activity.result.ActivityResultLauncher<android.content.Intent> cropWallpaperLauncher;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        setContentView(R.layout.activity_main);

        root = findViewById(R.id.web).getParent() instanceof FrameLayout
                ? (FrameLayout) findViewById(R.id.web).getParent() : null;
        web = findViewById(R.id.web);
        cover = findViewById(R.id.cover);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setNavigationBarContrastEnforced(false);
            getWindow().setStatusBarContrastEnforced(false);
        }

        getWindow().getDecorView().setBackgroundColor(Color.parseColor("#070A0F"));

        applySystemBars(false);

        web.setOnLongClickListener(v -> true);
        web.setLongClickable(false);
        web.setHapticFeedbackEnabled(false);
        initEngines();
        setupPickers();
        setupWebView();
        setupBridge();

        if (prefs.bgPlay()) {
            applyBgPlay(true);
        }
    }

    private void showFatal(String message) {
        try {
            TextView tv = new TextView(this);
            tv.setText(message);
            tv.setTextColor(Color.parseColor("#EEF3FA"));
            tv.setTextSize(15);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(dp(32), dp(32), dp(32), dp(32));
            if (root != null) {
                root.addView(tv, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
            }
            if (cover != null) cover.setVisibility(View.GONE);
        } catch (Throwable ignore) {
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void setupPickers() {
        pickFilesLauncher = registerForActivityResult(
                new androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
                uris -> {
                    if (uris == null || uris.isEmpty() || bridge == null) return;
                    bridge.onPickedFiles(uris);
                });

        pickTreeLauncher = registerForActivityResult(
                new androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
                uri -> {
                    if (uri == null || bridge == null) return;
                    bridge.onPickedTree(uri);
                });

        pickWallpaperLauncher = registerForActivityResult(
                new androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri == null || bridge == null) return;
                    bridge.onPickedWallpaper(uri);
                });

        cropWallpaperLauncher = registerForActivityResult(
                new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (bridge == null) return;
                    if (result.getResultCode() != android.app.Activity.RESULT_OK
                            || result.getData() == null) {
                        bridge.onCropCanceled();
                        return;
                    }
                    String out = result.getData().getStringExtra(
                            com.tingyun.app.wall.WallCropActivity.EXTRA_OUT);
                    if (out == null || out.isEmpty()) {
                        bridge.onCropCanceled();
                        return;
                    }
                    bridge.onCroppedWallpaper(new java.io.File(out));
                });
    }

    private static class WallPathHandler implements WebViewAssetLoader.PathHandler {
        private final java.io.File dir;

        WallPathHandler(android.content.Context c) {
            dir = new java.io.File(c.getFilesDir(), "wallpapers");
        }

        @Override
        public WebResourceResponse handle(String path) {
            return open(dir, path);
        }

        static WebResourceResponse open(java.io.File base, String path) {
            try {
                if (path == null) return null;
                
                if (path.contains("..")) return null;
                String name = path;
                int slash = name.lastIndexOf('/');
                if (slash >= 0) name = name.substring(slash + 1);
                if (name.isEmpty()) return null;
                
                boolean ok = name.endsWith(".img")
                        && name.matches("[A-Za-z0-9_.]+\\.img");
                if (!ok) return null;

                java.io.File f = new java.io.File(base, name);
                if (!f.exists() || f.length() == 0) return null;

                String mime = "image/jpeg";
                java.io.FileInputStream in = new java.io.FileInputStream(f);
                WebResourceResponse r = new WebResourceResponse(mime, null, in);
                
                java.util.Map<String, String> h = new java.util.HashMap<>();
                h.put("Content-Length", String.valueOf(f.length()));
                h.put("Accept-Ranges", "bytes");
                h.put("Cache-Control", "no-store");
                r.setResponseHeaders(h);
                r.setStatusCodeAndReasonPhrase(200, "OK");
                return r;
            } catch (Throwable t) {
                return null;
            }
        }
    }

    private static class CoverPathHandler implements WebViewAssetLoader.PathHandler {
        private final java.io.File dir;

        CoverPathHandler(android.content.Context c) {
            dir = new java.io.File(c.getFilesDir(), "covers");
        }

        @Override
        public WebResourceResponse handle(String path) {
            try {
                if (path == null || path.contains("..")) return null;
                String name = path;
                int slash = name.lastIndexOf('/');
                if (slash >= 0) name = name.substring(slash + 1);
                if (name.isEmpty()) return null;
                if (!name.matches("[A-Za-z0-9_.]+\\.(jpg|jpeg|png|webp)")) return null;

                java.io.File f = new java.io.File(dir, name);
                if (!f.exists() || f.length() == 0) return null;

                String low = name.toLowerCase();
                String mime = low.endsWith(".png") ? "image/png"
                        : low.endsWith(".webp") ? "image/webp" : "image/jpeg";
                java.io.FileInputStream in = new java.io.FileInputStream(f);
                WebResourceResponse r = new WebResourceResponse(mime, null, in);
                java.util.Map<String, String> h = new java.util.HashMap<>();
                h.put("Content-Length", String.valueOf(f.length()));
                
                h.put("Cache-Control", "public, max-age=604800");
                r.setResponseHeaders(h);
                r.setStatusCodeAndReasonPhrase(200, "OK");
                return r;
            } catch (Throwable t) {
                return null;
            }
        }
    }

    public String registerLocalFile(java.io.File f, String mime) {
        try {
            if (f == null) return null;
            String n = f.getName();
            
            boolean ok = n.endsWith(".img")
                    && n.matches("[A-Za-z0-9_.]+\\.img");
            if (!ok) {
                Log.w(TAG, "拒绝登记非壁纸文件: " + n);
                return null;
            }
            return "https://appassets.androidplatform.net/wall/" + n;
        } catch (Throwable t) {
            Log.w(TAG, "生成壁纸 URL 失败", t);
            return null;
        }
    }

    public void launchCropWallpaper(java.io.File src, java.io.File out) {
        try {
            android.content.Intent it = new android.content.Intent(this,
                    com.tingyun.app.wall.WallCropActivity.class);
            it.putExtra(com.tingyun.app.wall.WallCropActivity.EXTRA_SRC, src.getAbsolutePath());
            it.putExtra(com.tingyun.app.wall.WallCropActivity.EXTRA_OUT, out.getAbsolutePath());
            int w = getResources().getDisplayMetrics().widthPixels;
            int h = getResources().getDisplayMetrics().heightPixels;
            it.putExtra(com.tingyun.app.wall.WallCropActivity.EXTRA_ASPECT, h <= 0 ? 1f : (float) w / h);
            cropWallpaperLauncher.launch(it);
        } catch (Throwable t) {
            Log.w(TAG, "拉起裁剪页失败", t);
            if (bridge != null) bridge.onCropCanceled();
        }
    }

    public void launchPickWallpaper() {
        try {
            pickWallpaperLauncher.launch(new String[]{"image/*"});
        } catch (Throwable t) {
            Log.w(TAG, "拉起壁纸选择器失败", t);
            if (bridge != null) bridge.onPickCanceled();
        }
    }

    public void launchPickFiles() {
        try {
            
            pickFilesLauncher.launch(new String[]{"audio/*", "application/ogg", "*/*"});
        } catch (Throwable t) {
            Log.w(TAG, "拉起文件选择器失败", t);
            if (bridge != null) bridge.onPickCanceled();
        }
    }

    public void launchPickTree() {
        try {
            pickTreeLauncher.launch(null);
        } catch (Throwable t) {
            Log.w(TAG, "拉起文件夹选择器失败", t);
            if (bridge != null) bridge.onPickCanceled();
        }
    }

    private void initEngines() {
        prefs = new Prefs(this);
        library = new LibraryStore(this);
        audio = new AudioEngine(this);
        amb = new AmbientEngine(this);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        
        try {
            boolean debuggable = (getApplicationInfo().flags
                    & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            WebView.setWebContentsDebuggingEnabled(debuggable);
        } catch (Throwable t) {
            showFatal("这台设备上找不到可用的浏览器内核，无法显示页面");
            return;
        }
        
        try {
            android.content.pm.PackageInfo pi =
                    androidx.webkit.WebViewCompat.getCurrentWebViewPackage(this);
            if (pi != null) {
                Log.i(TAG, "WebView " + pi.packageName + " v" + pi.versionName);
            } else {
                Log.w(TAG, "拿不到 WebView 版本信息");
            }
        } catch (Throwable ignore) {
        }

        WebSettings s = web.getSettings();

        web.setBackgroundColor(Color.parseColor("#070A0F"));

        s.setMediaPlaybackRequiresUserGesture(false);

        s.setJavaScriptEnabled(true);
        
        s.setDomStorageEnabled(true);

        s.setTextZoom(100);

        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);

        s.setLoadWithOverviewMode(false);
        s.setUseWideViewPort(false);

        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);

        s.setSaveFormData(false);

        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            s.setSafeBrowsingEnabled(false);
        }

        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                
                .addPathHandler("/wall/", new WallPathHandler(this))
                
                .addPathHandler("/cover/", new CoverPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                WebResourceResponse r = loader.shouldInterceptRequest(req.getUrl());
                if (r == null) return null;
                
                try {
                    String p = req.getUrl().getPath();
                    if (p != null) {
                        String low = p.toLowerCase();
                        String mime = null;
                        if (low.endsWith(".woff2")) mime = "font/woff2";
                        else if (low.endsWith(".woff")) mime = "font/woff";
                        else if (low.endsWith(".ttf")) mime = "font/ttf";
                        else if (low.endsWith(".otf")) mime = "font/otf";
                        else if (low.endsWith(".css")) mime = "text/css";
                        else if (low.endsWith(".svg")) mime = "image/svg+xml";
                        else if (low.endsWith(".js")) mime = "application/javascript";
                        
                        else if (low.endsWith(".img")) mime = "image/jpeg";
                        if (mime != null) r.setMimeType(mime);
                    }
                } catch (Exception ignore) {
                }
                return r;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                
                if (cover != null && cover.getVisibility() == View.VISIBLE) {
                    cover.animate().alpha(0f).setDuration(200)
                            .withEndAction(() -> cover.setVisibility(View.GONE))
                            .start();
                }
                Log.d(TAG, "页面加载完成 " + url);
                
                restoreSessionIfNeeded();
            }

            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                
                Log.w(TAG, "渲染进程被回收，重建 WebView");

                web.post(() -> {
                    destroyWeb();
                    recreate();
                });
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(@NonNull ConsoleMessage cm) {
                Log.i(TAG + "/web", cm.message() + " @" + cm.lineNumber());
                return true;
            }
        });

        web.loadUrl(ENTRY_URL);
    }

    private void setupBridge() {
        bridge = new TingYunBridge(this, audio, amb, library, prefs, js ->
                
                runOnUiThread(() -> {
                    if (web != null) web.evaluateJavascript(js, null);
                }));

        bridge.setScreenSink(isImmersive -> runOnUiThread(() -> {
            immersive = isImmersive;
            applySystemBars(isImmersive);
        }));

        bridge.setSessionSink((scene, index, posMs, onPlayer) -> {
            if (scene != null) savedScene = scene;
            savedIndex = index;
            savedPos = posMs;
            savedOnPlayer = onPlayer;
        });

        bridge.setBgPlaySink(new TingYunBridge.BgPlaySink() {
            @Override
            public boolean requestBgPlay() {
                
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true;
                if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED) {
                    return true;
                }
                
                requestPermissions(
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                        REQ_NOTIFY);
                return true;
            }

            @Override
            public void onBgPlayChanged(boolean on) {
                applyBgPlay(on);
            }
        });

        bridge.bindListeners();
        web.addJavascriptInterface(bridge, "TingYun");
    }

    private void applySystemBars(boolean hide) {
        WindowInsetsControllerCompat c =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());

        c.setAppearanceLightStatusBars(false);
        c.setAppearanceLightNavigationBars(false);

        if (hide) {
            c.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            c.hide(WindowInsetsCompat.Type.systemBars());
        } else {
            c.show(WindowInsetsCompat.Type.systemBars());
        }

        pushInsets();
    }

    private void pushInsets() {
        if (web == null) return;
        
        String js = "document.documentElement.style.setProperty('--sat','"
                + pxToDp(getStatusBarHeight()) + "px');"
                + "document.documentElement.style.setProperty('--sab','"
                + pxToDp(getNavBarHeight()) + "px');";
        web.evaluateJavascript(js, null);
    }

    private int getStatusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    private int getNavBarHeight() {
        int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    private float pxToDp(int px) {
        return px / getResources().getDisplayMetrics().density;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        
        if (hasFocus) applySystemBars(immersive);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) {
            web.onResume();
            
            web.evaluateJavascript(
                    "window.TingYunResume&&window.TingYunResume();", null);
        }
    }

    @Override
    protected void onPause() {
        if (web != null) web.onPause();
        
        if (!prefs.bgPlay() && audio != null && !isFinishing()) {
            audio.pause();
        }
        super.onPause();
    }

    @Override
    public void onConfigurationChanged(@NonNull android.content.res.Configuration cfg) {
        super.onConfigurationChanged(cfg);
        
        if (web != null) {
            web.postDelayed(() ->
                    web.evaluateJavascript("window.TingYunRotate&&window.TingYunRotate();", null), 160);
        }
    }

    @Override
    public void onBackPressed() {
        
        if (web != null) {
            web.evaluateJavascript(
                    "(function(){try{return !!(window.TingYunBack&&window.TingYunBack());}"
                            + "catch(e){return false;}})()", value -> {
                        
                        if (!"true".equals(value)) {
                            
                            stopAllAudio();
                            finish();
                        }
                    });
        } else {
            stopAllAudio();
            super.onBackPressed();
        }
    }

    private void stopAllAudio() {
        try {
            if (audio != null) audio.pause();
        } catch (Throwable t) {
            Log.w(TAG, "暂停主播放器失败", t);
        }
        try {
            if (amb != null) amb.disable();
        } catch (Throwable t) {
            Log.w(TAG, "停止环境音失败", t);
        }
    }

    @Override
    protected void onDestroy() {
        destroyWeb();
        
        if (media != null) {
            media.release();
            media = null;
        }
        if (audio != null) audio.release();
        if (amb != null) amb.release();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(@NonNull android.os.Bundle out) {
        super.onSaveInstanceState(out);
        if (web == null) return;

        out.putString("scene", savedScene);
        out.putInt("trackIndex", savedIndex);
        out.putLong("position", savedPos);
        out.putBoolean("immersive", immersive);
        out.putBoolean("onPlayer", savedOnPlayer);
    }

    @Override
    protected void onRestoreInstanceState(@NonNull android.os.Bundle in) {
        super.onRestoreInstanceState(in);
        savedScene = in.getString("scene", "");
        savedIndex = in.getInt("trackIndex", -1);
        savedPos = in.getLong("position", 0L);
        savedOnPlayer = in.getBoolean("onPlayer", false);
        
    }

    private void restoreSessionIfNeeded() {
        if (web == null) return;
        String js = "(function(){try{if(!window.TingYunRestore)return;"
                + "window.TingYunRestore("
                + org.json.JSONObject.quote(savedScene == null ? "" : savedScene) + ","
                + savedIndex + "," + savedPos + ","
                + (savedOnPlayer ? "true" : "false")
                + ");}catch(e){}})()";
        web.evaluateJavascript(js, null);
    }

    private void applyBgPlay(boolean on) {
        if (media == null) {
            media = new com.tingyun.app.player.MediaSessionHolder(this);
        }
        if (on) {
            if (audio != null) {
                media.ensureSession(audio.player());
                media.setTrack(audio.currentTitle(), "");
                media.startForeground();
            }
        } else {
            
            media.release();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms,
                                           @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQ_NOTIFY) return;
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            prefs.setBgPlay(false);
            applyBgPlay(false);
            if (web != null) {
                web.evaluateJavascript(
                        "window.TingYunNative&&window.TingYunNative.onTip"
                                + "&&window.TingYunNative.onTip('没有通知权限，后台播放没法打开');", null);
            }
        } else {
            applyBgPlay(true);
        }
    }

    private void destroyWeb() {
        if (web != null) {
            ViewGroup p = (ViewGroup) web.getParent();
            if (p != null) p.removeView(web);
            web.removeJavascriptInterface("TingYun");
            web.stopLoading();
            web.setWebChromeClient(null);
            web.setWebViewClient(null);
            web.destroy();
            web = null;
        }
    }
}
