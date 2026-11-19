package com.tingyun.app.bridge;

import android.content.Context;
import android.net.Uri;
import android.os.PowerManager;
import android.webkit.JavascriptInterface;

import com.tingyun.app.decrypt.Cipher;
import com.tingyun.app.decrypt.NcmDecoder;
import com.tingyun.app.decrypt.QmcDecoder;
import com.tingyun.app.data.LibraryStore;
import com.tingyun.app.data.Prefs;
import com.tingyun.app.data.Track;
import com.tingyun.app.lyric.LrcParser;
import com.tingyun.app.lyric.LyricProvider;
import com.tingyun.app.media.Importer;
import com.tingyun.app.media.TagReader;
import com.tingyun.app.player.AmbientEngine;
import com.tingyun.app.player.AudioEngine;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import android.util.Base64;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class TingYunBridge {

    public interface JsSink {
        void eval(String js);
    }

    private static final String TAG = "TingYunBridge";
    private static final int MAX_STR = 512;

    private final Context appCtx;
    private final AudioEngine audio;
    private final AmbientEngine amb;
    private final LibraryStore library;
    private final Prefs prefs;
    private final Importer importer;

    private JsSink sink;

    public TingYunBridge(Context ctx, AudioEngine audio, AmbientEngine amb,
                         LibraryStore library, Prefs prefs, JsSink sink) {
        this.appCtx = ctx.getApplicationContext();
        this.audio = audio;
        this.amb = amb;
        this.library = library;
        this.prefs = prefs;
        this.sink = sink;
        this.importer = new Importer(appCtx);
        this.actRef = (ctx instanceof android.app.Activity)
                ? new java.lang.ref.WeakReference<>((android.app.Activity) ctx)
                : null;
    }

    public void setSink(JsSink s) {
        this.sink = s;
    }

    public void bindListeners() {
        if (audio == null) return;
        audio.setListener(new AudioEngine.Listener() {
            @Override
            public void onState(boolean playing) {
                emit("window.TingYunNative&&window.TingYunNative.onState(" + playing + ")");
            }

            @Override
            public void onProgress(long positionMs, long durationMs) {
                emit("window.TingYunNative&&window.TingYunNative.onProgress("
                        + positionMs + "," + durationMs + ")");
            }

            @Override
            public void onEnded() {
                emit("window.TingYunNative&&window.TingYunNative.onEnded()");
            }

            @Override
            public void onError(String message) {
                
                emit("window.TingYunNative&&window.TingYunNative.onError()");
            }
        });
    }

    private void emit(String js) {
        JsSink s = sink;
        if (s != null) s.eval(js);
    }

    private static String safe(String s) {
        if (s == null) return "";
        int n = Math.min(s.length(), MAX_STR);
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            
            if (c >= 0x20 || c == '\t') sb.append(c);
        }
        return sb.toString();
    }

    private static boolean safeUri(String uri) {
        if (uri == null || uri.length() == 0 || uri.length() > MAX_STR) return false;
        String u = uri.toLowerCase();
        return u.startsWith("https://appassets.androidplatform.net/")
                || u.startsWith("content://")
                || u.startsWith("file://");
    }

    public static String clampStr(String s) {
        return safe(s);
    }

    public static boolean checkUri(String u) {
        return safeUri(u);
    }

    @JavascriptInterface
    public boolean isLowPower() {
        try {
            PowerManager pm = (PowerManager) appCtx.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isPowerSaveMode();
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public boolean reduceMotion() {
        try {
            float v = android.provider.Settings.Global.getFloat(
                    appCtx.getContentResolver(),
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
            return v == 0f;
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public String version() {
        return "0.1";
    }

    @JavascriptInterface
    public boolean isLandscape() {
        return appCtx.getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    }

    @JavascriptInterface
    public void rotate(final boolean landscape) {
        
        final android.app.Activity act = weakActivity();
        if (act == null) return;
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    act.setRequestedOrientation(landscape
                            ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
                } catch (Exception e) {
                    
                    Log.w(TAG, "设置屏幕方向失败: " + e.getMessage());
                }
            }
        });
    }

    private final java.lang.ref.WeakReference<android.app.Activity> actRef;

    private android.app.Activity weakActivity() {
        android.app.Activity a = actRef == null ? null : actRef.get();
        return (a == null || a.isFinishing()) ? null : a;
    }

    @JavascriptInterface
    public String getPrefs() {
        try {
            JSONObject o = new JSONObject();
            o.put("bgPlay", prefs.bgPlay());
            o.put("autoLyric", prefs.autoLyric());
            o.put("keepAwake", prefs.keepAwake());
            o.put("dim", prefs.dimStrength());
            o.put("lyricOffset", prefs.lyricOffset());
            
            o.put("fadeOn", prefs.fadeOn());
            o.put("fadeDelay", prefs.fadeDelay());
            o.put("fadeLevel", prefs.fadeLevel());
            o.put("scene", prefs.scene());
            o.put("ambVol", prefs.ambVolume());
            
            o.put("wallImage", prefs.wallImage().length() > 0);
            
            o.put("lastIndex", prefs.lastIndex());
            o.put("lastPos", prefs.lastPos());
            o.put("lastOn", prefs.lastOnPlayer());
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    @JavascriptInterface
    public void setPref(String key, String value) {
        String k = safe(key);
        boolean on = "true".equals(value) || "1".equals(value);
        switch (k) {
            case "bgPlay":
                
                boolean ok = true;
                if (on && bgPlaySink != null) ok = bgPlaySink.requestBgPlay();
                if (!ok) {
                    
                    prefs.setBgPlay(false);
                    pushTip("没有通知权限，后台播放没法打开");
                    if (bgPlaySink != null) bgPlaySink.onBgPlayChanged(false);
                    return;
                }
                prefs.setBgPlay(on);
                if (bgPlaySink != null) bgPlaySink.onBgPlayChanged(on);
                break;
            case "autoLyric":
                prefs.setAutoLyric(on);
                break;
            case "keepAwake":
                prefs.setKeepAwake(on);
                break;
            case "dim":
                try {
                    prefs.setDimStrength(Integer.parseInt(safe(value)));
                } catch (NumberFormatException ignored) {
                }
                break;
            case "lyricOffset":
                
                try {
                    prefs.setLyricOffset(Integer.parseInt(safe(value)));
                } catch (NumberFormatException ignored) {
                }
                break;
            case "scene":
                
                prefs.setScene(safe(value));
                break;
            case "ambVol":
                try {
                    prefs.setAmbVolume(Integer.parseInt(safe(value)));
                } catch (NumberFormatException ignored) {
                }
                break;
            case "fadeOn":
                prefs.setFadeOn(on);
                break;
            case "fadeDelay":
                try {
                    prefs.setFadeDelay(Integer.parseInt(safe(value)));
                } catch (NumberFormatException ignored) {
                }
                break;
            case "fadeLevel":
                try {
                    prefs.setFadeLevel(Integer.parseInt(safe(value)));
                } catch (NumberFormatException ignored) {
                }
                break;
            default:
                break;
        }
    }

    public interface BgPlaySink {
        
        boolean requestBgPlay();

        void onBgPlayChanged(boolean on);
    }

    private BgPlaySink bgPlaySink;

    public void setBgPlaySink(BgPlaySink s) {
        this.bgPlaySink = s;
    }

    @JavascriptInterface
    public String getTracks() {
        try {
            List<Track> list = library.load();
            JSONArray arr = new JSONArray();
            for (Track t : list) {
                JSONObject o = new JSONObject();
                o.put("id", t.id);
                o.put("title", t.title);
                o.put("artist", t.subtitle());
                o.put("cover", t.cover);
                o.put("uri", t.uri);
                o.put("durationMs", t.durationMs);
                arr.put(o);
            }
            return arr.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    @JavascriptInterface
    public boolean isEmpty() {
        return library.isEmpty();
    }

    @JavascriptInterface
    public String getLyricIds() {
        try {
            List<Track> list = library.load();
            JSONArray arr = new JSONArray();
            for (Track t : list) {
                if (t.uri == null || !t.uri.startsWith("file://")) continue;
                File mf = new File(t.uri.substring(7));
                File side = LyricProvider.sidecarOf(mf);
                if (side.exists() && side.length() > 0) arr.put(t.id);
            }
            return arr.toString();
        } catch (Exception e) {
            return "[]";
        }
    }
    @JavascriptInterface
    public void removeTrack(String id) {
        String i = safe(id);
        if (i.length() == 0) return;
        library.remove(i);
    }

    @JavascriptInterface
    public String retagTrack(String id) {
        String i = safe(id);
        if (i.length() == 0) return null;
        Track t = findTrack(i);
        if (t == null || t.uri == null || !t.uri.startsWith("file://")) return null;

        File f = new File(t.uri.substring(7));
        if (!f.exists() || f.length() == 0) return null;

        String ext = QmcDecoder.extOf(f.getName());
        TagReader.Tag tag = TagReader.read(readHead(f), ext);
        if (tag.isEmpty()) return null;

        boolean changed = false;
        if (tag.title.length() > 0 && !tag.title.equals(t.title)) {
            t.title = tag.title;
            changed = true;
        }
        if (tag.artist.length() > 0 && !tag.artist.equals(t.artist)) {
            t.artist = tag.artist;
            changed = true;
        }
        
        if (tag.cover != null) {
            String cu = saveCover(t.id, tag.cover);
            if (cu.length() > 0 && !cu.equals(t.cover)) {
                t.cover = cu;
                changed = true;
            }
        }
        if (!changed) return null;

        library.add(t);
        try {
            JSONObject o = new JSONObject();
            o.put("id", t.id);
            o.put("title", t.title);
            o.put("artist", t.artist);
            o.put("cover", t.cover);
            o.put("album", tag.album);
            return o.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] readHead(File f) {
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            long len = f.length();
            int cap = (int) Math.min(len, 512L * 1024L);
            byte[] buf = new byte[cap];
            int got = 0;
            while (got < cap) {
                int n = in.read(buf, got, cap - got);
                if (n <= 0) break;
                got += n;
            }
            if (got == cap) return buf;
            byte[] out = new byte[got];
            System.arraycopy(buf, 0, out, 0, got);
            return out;
        } catch (Exception e) {
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignore) {
            }
        }
    }

    @JavascriptInterface
    public void getLyricAsync(final int seq, final String trackId, final String title, final String artist) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String json;
                try {
                    json = getLyric(trackId, title, artist, true);
                } catch (Exception e) {
                    
                    json = "{\"code\":1,\"lines\":[]}";
                }
                emit("window.TingYunNative&&window.TingYunNative.onLyric&&window.TingYunNative.onLyric("
                        + seq + "," + JSONObject.quote(json) + ")");
            }
        }, "tingyun-lyric").start();
    }

    @JavascriptInterface
    public String getLyric(String trackId, String title, String artist) {
        return getLyric(trackId, title, artist, false);
    }

    @JavascriptInterface
    public String getLyric(String trackId, String title, String artist, boolean manual) {
        try {
            String id = safe(trackId);
            String ti = safe(title);
            String ar = safe(artist);

            LyricProvider.Result r = new LyricProvider.Result();

            Track t = findTrack(id);
            if (t != null && t.uri != null && t.uri.startsWith("file://")) {
                File mf = new File(t.uri.substring(7));
                r = LyricProvider.local(mf);
            }

            boolean needConfirm = false;
            boolean allowNet = manual || prefs.autoLyric();
            if (r.isEmpty() && (manual || !r.rejected) && allowNet && ti.length() > 0) {
                LyricProvider.Result net = LyricProvider.online(appCtx, ti, ar);
                if (!net.isEmpty()) {
                    r = net;
                    
                    String localName = ti.trim();
                    String gotName = net.matchTitle == null ? "" : net.matchTitle.trim();
                    boolean same = localName.equalsIgnoreCase(gotName);
                    needConfirm = !same || net.candidates > 1;
                }
            }

            JSONObject out = new JSONObject();
            if (r.isEmpty()) {
                
                out.put("code", 1);
                out.put("lines", new JSONArray());
                return out.toString();
            }

            List<LrcParser.Line> lines = LrcParser.parse(r.lrc);
            if (r.tlyric != null && r.tlyric.length() > 0) {
                LrcParser.pairTranslation(lines, LrcParser.parse(r.tlyric));
            }

            JSONArray arr = new JSONArray();
            for (LrcParser.Line l : lines) {
                JSONArray one = new JSONArray();
                one.put(l.timeMs);
                one.put(l.text);
                one.put(l.hasTrans() ? l.trans : "");
                arr.put(one);
            }

            out.put("code", needConfirm ? 2 : 0);
            out.put("lines", arr);
            out.put("from", r.from);
            out.put("match", r.matchTitle + (r.matchArtist.length() > 0 ? " - " + r.matchArtist : ""));
            return out.toString();
        } catch (Exception e) {
            
            Log.d(TAG, "歌词读取失败", e);
            try {
                JSONObject out = new JSONObject();
                out.put("code", 1);
                out.put("lines", new JSONArray());
                return out.toString();
            } catch (Exception ignore) {
                return "{\"code\":1,\"lines\":[]}";
            }
        }
    }

    @JavascriptInterface
    public boolean saveLyric(String trackId, String lrc) {
        String id = safe(trackId);
        if (id.length() == 0 || lrc == null || lrc.length() == 0) return false;
        Track t = findTrack(id);
        if (t == null || t.uri == null || !t.uri.startsWith("file://")) return false;
        File mf = new File(t.uri.substring(7));
        boolean ok = LyricProvider.saveSidecar(mf, lrc);
        if (ok) {
            File side = LyricProvider.sidecarOf(mf);
            t.lrc = "file://" + side.getAbsolutePath();
            library.add(t);
        }
        return ok;
    }

    @JavascriptInterface
    public void rejectLyric(String trackId) {
        String id = safe(trackId);
        if (id.length() == 0) return;
        Track t = findTrack(id);
        if (t == null) return;
        
        try {
            if (t.uri != null && t.uri.startsWith("file://")) {
                File mf = new File(t.uri.substring(7));
                File side = LyricProvider.sidecarOf(mf);
                if (!side.exists()) {
                    side.getParentFile().mkdirs();
                    side.createNewFile();
                }
            }
        } catch (Exception ignore) {
        }
    }

    private Track findTrack(String id) {
        if (id.length() == 0) return null;
        try {
            for (Track t : library.load()) {
                if (id.equals(t.id)) return t;
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    @JavascriptInterface
    public void searchLyricCandidates(final int seq, final String title, final String artist) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String json = "[]";
                try {
                    String kw = safe(title);
                    String ar = safe(artist);
                    if (ar.length() > 0) kw = kw + " " + ar;
                    java.util.List<LyricProvider.Candidate> list =
                            LyricProvider.search(appCtx, kw, 8);

                    JSONArray arr = new JSONArray();
                    for (LyricProvider.Candidate c : list) {
                        JSONObject o = new JSONObject();
                        o.put("source", c.source);
                        o.put("id", c.songId);
                        o.put("title", c.title);
                        o.put("artist", c.artist);
                        o.put("album", c.album);
                        o.put("cover", c.coverUrl);
                        o.put("durationMs", c.durationMs);
                        arr.put(o);
                    }
                    json = arr.toString();
                } catch (Exception e) {
                    Log.d(TAG, "搜索歌词候选失败", e);
                }
                emit("window.TingYunNative&&window.TingYunNative.onLyricCandidates&&"
                        + "window.TingYunNative.onLyricCandidates("
                        + seq + "," + JSONObject.quote(json) + ")");
            }
        }, "tingyun-lyric-search").start();
    }

    @JavascriptInterface
    public void useLyricCandidate(final int seq, final String trackId,
                                  final String source, final String songId) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String json;
                try {
                    LyricProvider.Result r = LyricProvider.byId(appCtx, safe(source), safe(songId));
                    if (r.isEmpty()) {
                        json = "{\"code\":1,\"lines\":[]}";
                    } else {
                        
                        if (trackId != null && trackId.length() > 0) {
                            try {
                                saveLyric(trackId, r.lrc);
                            } catch (Exception ignore) {
                            }
                        }
                        List<LrcParser.Line> lines = LrcParser.parse(r.lrc);
                        if (r.tlyric != null && r.tlyric.length() > 0) {
                            LrcParser.pairTranslation(lines, LrcParser.parse(r.tlyric));
                        }
                        JSONArray arr = new JSONArray();
                        for (LrcParser.Line l : lines) {
                            JSONArray one = new JSONArray();
                            one.put(l.timeMs);
                            one.put(l.text);
                            one.put(l.hasTrans() ? l.trans : "");
                            arr.put(one);
                        }
                        JSONObject out = new JSONObject();
                        out.put("code", 0);
                        out.put("lines", arr);
                        out.put("from", r.from);
                        json = out.toString();
                    }
                } catch (Exception e) {
                    Log.d(TAG, "应用候选歌词失败", e);
                    json = "{\"code\":1,\"lines\":[]}";
                }
                emit("window.TingYunNative&&window.TingYunNative.onLyric&&window.TingYunNative.onLyric("
                        + seq + "," + JSONObject.quote(json) + ")");
            }
        }, "tingyun-lyric-apply").start();
    }

    @JavascriptInterface
    public String importFile(String name, String base64) {
        String n = safe(name);
        if (n.length() == 0 || base64 == null || base64.length() == 0) return "";

        byte[] raw;
        try {
            raw = Base64.decode(base64, Base64.DEFAULT);
        } catch (Exception e) {
            pushTip("导入失败");
            return "";
        }
        return ingest(n, raw);
    }

    @JavascriptInterface
    public String importUri(String uriString) {
        String u = safe(uriString);
        if (u.length() == 0) return "";

        Uri uri;
        try {
            uri = Uri.parse(u);
        } catch (Exception e) {
            pushTip("导入失败");
            return "";
        }
        if (!"content".equals(uri.getScheme())) {
            pushTip("导入失败");
            return "";
        }

        importer.persist(uri);
        
        String name = importer.displayName(uri);
        if (name.length() == 0) name = "unknown.mp3";
        if (!Importer.isAudioName(name)) {
            pushTip("这个格式暂不支持");
            return "";
        }
        if (importer.tooLarge(uri)) {
            pushTip("文件太大，无法导入");
            return "";
        }

        try {
            byte[] raw = importer.readAll(uri);
            if (raw == null || raw.length == 0) {
                pushTip("导入失败");
                return "";
            }
            return ingest(name, raw);
        } catch (Exception e) {
            Log.w(TAG, "SAF 导入失败 " + name, e);
            pushTip("导入失败");
            return "";
        }
    }

    @JavascriptInterface
    public int importTree(String treeUriString) {
        String u = safe(treeUriString);
        if (u.length() == 0) return 0;
        Uri tree;
        try {
            tree = Uri.parse(u);
        } catch (Exception e) {
            return 0;
        }
        importer.persist(tree);
        List<Uri> files = importer.listAudioInTree(tree);
        if (files.isEmpty()) return 0;

        int ok = 0;
        for (Uri f : files) {
            String name = importer.displayName(f);
            if (name.length() == 0 || !Importer.isAudioName(name)) continue;
            if (importer.tooLarge(f)) continue;
            try {
                byte[] raw = importer.readAll(f);
                if (raw == null || raw.length == 0) continue;
                String id = ingest(name, raw);
                if (id.length() > 0) ok++;
            } catch (Exception e) {
                Log.w(TAG, "批量导入跳过 " + name, e);
            }
        }
        return ok;
    }

    @JavascriptInterface
    public void pickFiles() {
        
        final com.tingyun.app.MainActivity a = mainActivity();
        if (a == null) {
            pushTip("导入失败");
            return;
        }
        a.runOnUiThread(a::launchPickFiles);
    }

    @JavascriptInterface
    public void pickTree() {
        final com.tingyun.app.MainActivity a = mainActivity();
        if (a == null) {
            pushTip("导入失败");
            return;
        }
        a.runOnUiThread(a::launchPickTree);
    }

    private com.tingyun.app.MainActivity mainActivity() {
        android.app.Activity act = weakActivity();
        if (act == null || act.isFinishing()) return null;
        return (act instanceof com.tingyun.app.MainActivity)
? (com.tingyun.app.MainActivity) act : null;
    }

    @JavascriptInterface
    public void pickWallpaper() {
        final com.tingyun.app.MainActivity a = mainActivity();
        if (a == null) {
            pushTip("选壁纸失败");
            return;
        }
        a.runOnUiThread(a::launchPickWallpaper);
    }

    @JavascriptInterface
    public void clearWallpaper() {
        clearWallOne(false);
        emitWallState();
        pushTip("已回到纯画面");
    }

    @JavascriptInterface
    public void clearWallpaper(boolean video) {
        clearWallpaper();
    }

    private void clearWallOne(boolean video) {
        try {
            File dir = new File(appCtx.getFilesDir(), "wallpapers");
            File[] fs = dir.listFiles();
            if (fs != null) {
                for (File f : fs) {
                    
                    boolean isImg = f.getName().endsWith(".img");
                    if (video || !isImg) {
                        
                        f.delete();
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "清理壁纸失败", e);
        }
        if (!video) prefs.setWallImage("");
    }

    @JavascriptInterface
    public String getWallImage() {
        String v = prefs.wallImage();
        if (v == null) return "";
        if (v.length() == 0) return "";
        if (v.startsWith("http")) return v;
        return "https://appassets.androidplatform.net/wall/" + v;
    }

    @JavascriptInterface
    public String getWallCurrent() {
        try {
            
            String v = prefs.wallImage();
            if (v == null) return "";
            
            if (v.startsWith("http")) {
                int i = v.lastIndexOf('/');
                return i >= 0 ? v.substring(i + 1) : v;
            }
            return v;
        } catch (Exception e) {
            return "";
        }
    }

    @JavascriptInterface
    public String getWallList() {
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            java.io.File dir = new java.io.File(appCtx.getFilesDir(), "wallpapers");
            java.util.List<String> names = prefs.wallList();
            
            java.io.File legacy = new java.io.File(dir, "wall.img");
            if (legacy.exists() && legacy.length() > 0 && !names.contains("wall.img")) {
                names.add(0, "wall.img");
                prefs.setWallList(names);
                if (prefs.wallImage().length() == 0) prefs.setWallImage("wall.img");
            }
            java.util.List<String> alive = new java.util.ArrayList<>();
            for (String n : names) {
                if (n == null || n.length() == 0) continue;
                java.io.File f = new java.io.File(dir, n);
                if (!f.exists() || f.length() == 0) continue;
                alive.add(n);
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("name", n);
                o.put("url", "https://appassets.androidplatform.net/wall/" + n);
                
                o.put("size", f.length());
                
                o.put("kind", "image");
                arr.put(o);
            }
            
            if (alive.size() != names.size()) prefs.setWallList(alive);
            return arr.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    @JavascriptInterface
    public void useWall(String name) {
        try {
            if (name == null || name.length() == 0) return;
            java.io.File dir = new java.io.File(appCtx.getFilesDir(), "wallpapers");
            java.io.File f = new java.io.File(dir, name);
            if (!f.exists() || f.length() == 0) {
                pushTip("这张壁纸已不存在");
                return;
            }
            
            prefs.setWallImage(name);
            emit("window.TingYunSetWallpaper&&window.TingYunSetWallpaper("
                    + jsStr("https://appassets.androidplatform.net/wall/" + name) + ")");
        } catch (Exception e) {
            Log.w(TAG, "切换壁纸失败", e);
        }
    }

    @JavascriptInterface
    public void deleteWall(String name) {
        try {
            if (name == null || name.length() == 0) return;
            java.io.File dir = new java.io.File(appCtx.getFilesDir(), "wallpapers");
            java.io.File f = new java.io.File(dir, name);
            boolean wasCurrent = name.equals(prefs.wallImage());
            if (f.exists()) {
                
                f.delete();
            }
            prefs.removeWall(name);
            if (wasCurrent) {
                
                emit("window.TingYunSetWallpaper&&window.TingYunSetWallpaper('')");
            }
            emit("window.TingYunPushWallList&&window.TingYunPushWallList()");
            pushTip("已删除这张壁纸");
        } catch (Exception e) {
            Log.w(TAG, "删除壁纸失败", e);
        }
    }

    @JavascriptInterface
    public void emitWallState() {
        emit("window.TingYunPushWallState&&window.TingYunPushWallState()");
    }

    public void onPickedWallpaper(Uri uri) {
        if (uri == null) {
            onPickCanceled();
            return;
        }
        startCrop(uri);
    }

    private void startCrop(Uri uri) {
        
        new Thread(() -> {
            try {
                String name = importer.displayName(uri);
                String low = name == null ? "" : name.toLowerCase();
                boolean okExt = low.endsWith(".jpg") || low.endsWith(".jpeg")
                        || low.endsWith(".png") || low.endsWith(".webp");
                if (!okExt) {
                    pushTip("只支持 jpg/png/webp");
                    return;
                }
                File dir = new File(appCtx.getCacheDir(), "wallcrop");
                if (!dir.exists() && !dir.mkdirs()) { pushTip("选壁纸失败"); return; }
                
                File[] old = dir.listFiles();
                if (old != null) for (File f : old) {
                    
                    f.delete();
                }
                File tmp = new File(dir, "src.bin");
                InputStream in = appCtx.getContentResolver().openInputStream(uri);
                if (in == null) { pushTip("选壁纸失败"); return; }
                FileOutputStream fos = new FileOutputStream(tmp);
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                fos.close();
                in.close();

                com.tingyun.app.MainActivity a = mainActivity();
                if (a == null) { pushTip("选壁纸失败"); return; }
                File out = new File(dir, "out.jpg");
                a.runOnUiThread(() -> a.launchCropWallpaper(tmp, out));
            } catch (Exception e) {
                Log.w(TAG, "准备裁剪失败", e);
                pushTip("选壁纸失败");
            }
        }, "wall-crop-prep").start();
    }

    public void onCroppedWallpaper(File jpg) {
        try {
            if (jpg == null || !jpg.exists() || jpg.length() == 0) {
                onPickCanceled();
                return;
            }
            File dir = new File(appCtx.getFilesDir(), "wallpapers");
            if (!dir.exists() && !dir.mkdirs()) { pushTip("选壁纸失败"); return; }
            
            File out = new File(dir, "wall_" + System.currentTimeMillis() + ".img");
            copyFile(jpg, out);

            String url = registerWallUrl(out);
            if (url == null) { pushTip("选壁纸失败"); return; }
            
            prefs.addWall(out.getName());
            prefs.setWallImage(out.getName());
            emit("window.TingYunSetWallpaper&&window.TingYunSetWallpaper("
                    + jsStr(url) + ")");
            
            emit("window.TingYunPushWallList&&window.TingYunPushWallList()");
            pushTip("壁纸已添加");
            Log.i(TAG, "静态壁纸已保存 " + out + " (" + out.length() + "B)");
        } catch (Exception e) {
            Log.w(TAG, "静态壁纸保存失败", e);
            pushTip("选壁纸失败");
        }
    }

    private String registerWallUrl(File f) {
        try {
            com.tingyun.app.MainActivity a = mainActivity();
            if (a == null) return null;
            return a.registerLocalFile(f, null);
        } catch (Throwable t) {
            Log.w(TAG, "注册壁纸文件失败", t);
            return null;
        }
    }

    private static void copyFile(File from, File to) throws Exception {
        InputStream in = new java.io.FileInputStream(from);
        FileOutputStream fos = new FileOutputStream(to);
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        fos.close();
        in.close();
    }

    private static String guessImageMime(String name) {
        String n = name == null ? "" : name.toLowerCase();
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }

    private static String jsStr(String s) {
        if (s == null) return "''";
        StringBuilder b = new StringBuilder("'");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '\'') b.append('\\').append(c);
            else if (c == '\n') b.append("\\n");
            else if (c == '\r') b.append("\\r");
            else b.append(c);
        }
        return b.append("'").toString();
    }

    public void onPickedFiles(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) {
            onPickCanceled();
            return;
        }
        int ok = 0;
        for (Uri u : uris) {
            if (u == null) continue;
            String id = importUri(u.toString());
            if (id.length() > 0) ok++;
        }
        pushTip(ok > 0 ? ("已导入 " + ok + " 首") : "导入失败");
        emit("window.TingYunNative&&window.TingYunNative.onImported&&"
                + "window.TingYunNative.onImported(" + ok + ")");
    }

    public void onPickedTree(Uri tree) {
        if (tree == null) {
            onPickCanceled();
            return;
        }
        int ok = importTree(tree.toString());
        pushTip(ok > 0 ? ("已导入 " + ok + " 首") : "没有找到可导入的文件");
        emit("window.TingYunNative&&window.TingYunNative.onImported&&"
                + "window.TingYunNative.onImported(" + ok + ")");
    }

    public void onPickCanceled() {
        pushTip("");
    }

    public void onCropCanceled() {
        pushTip("");
    }

    private String ingest(String n, byte[] raw) {
        String ext = QmcDecoder.extOf(n);
        try {
            if (raw == null || raw.length == 0) return "";

            String cacheKey = null;
            File cacheHit = null;
            if ("ncm".equals(ext) || QmcDecoder.supports(n)) {
                cacheKey = sha256Hex(raw);
                cacheHit = cacheFile(cacheKey, "meta.json");
                if (cacheHit.exists()) {
                    String cachedId = readCachedTrack(cacheHit);
                    
                    if (cachedId.length() > 0 && trackExists(cachedId)) {
                        Log.d(TAG, "解密缓存命中 " + n);
                        return cachedId;
                    }
                }
            }

            byte[] audio = raw;
            String outName = n;
            String title = stripExt(n);
            String artist = "";
            String embeddedLrc = "";
            byte[] cover = null;

            TagReader.Tag tag = TagReader.read(raw, ext);
            if (tag.title.length() > 0) title = tag.title;
            if (tag.artist.length() > 0) artist = tag.artist;
            
            if (tag.cover != null) cover = tag.cover;

            if ("ncm".equals(ext)) {
                NcmDecoder.Result r = NcmDecoder.decode(raw, null);
                audio = r.audio;
                if (r.title != null && r.title.length() > 0) title = r.title;
                if (r.artist != null) artist = r.artist;
                
                if (r.lyric != null) embeddedLrc = r.lyric;
                
                if (r.cover != null && r.cover.length > 0) cover = r.cover;
                outName = title + "." + (r.container == null ? "mp3" : r.container);
            } else if (QmcDecoder.supports(n)) {
                QmcDecoder.Result r = QmcDecoder.decode(raw, n, null);
                audio = r.audio;
                String c = (r.container == null || "bin".equals(r.container)) ? "mp3" : r.container;
                outName = title + "." + c;
            } else if (!isPlainAudio(ext)) {
                
                pushTip("这个格式暂不支持");
                return "";
            }

            if (audio == null || audio.length == 0) {
                pushTip("这个文件解不开");
                return "";
            }

            String id = writeTrack(outName, audio, title, artist, embeddedLrc, cover);
            
            if (id.length() > 0 && cacheKey != null) {
                writeCachedTrack(cacheFile(cacheKey, "meta.json"), id);
            }
            
            if (id.length() == 0) pushTip("导入失败");
            return id;
        } catch (Cipher.DecryptException e) {
            String msg = e.getMessage();
            
            if (msg != null && msg.startsWith("no-ekey")) {
                pushTip("这个文件暂时解不开，可以先用其他工具转成 MP3");
            } else {
                pushTip("这个文件解不开，可以先用其他工具转成 MP3");
            }
            Log.w(TAG, "解密失败 " + n + " : " + msg);
            return "";
        } catch (Exception e) {
            Log.w(TAG, "导入失败 " + n, e);
            pushTip("导入失败");
            return "";
        }
    }

    private static boolean isPlainAudio(String ext) {
        return "mp3".equals(ext) || "flac".equals(ext) || "m4a".equals(ext)
                || "ogg".equals(ext) || "wav".equals(ext) || "aac".equals(ext);
    }

    private File cacheFile(String key, String name) {
        File dir = new File(new File(appCtx.getFilesDir(), "decoded"), key);
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, name);
    }

    private static String sha256Hex(byte[] data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                int v = b & 0xFF;
                if (v < 16) sb.append('0');
                sb.append(Integer.toHexString(v));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String readCachedTrack(File f) {
        try {
            String txt = readSmall(f);
            if (txt == null || txt.length() == 0) return "";
            JSONObject o = new JSONObject(txt);
            String id = o.optString("trackId", "");
            
            return id;
        } catch (Exception e) {
            return "";
        }
    }

    private static void writeCachedTrack(File f, String trackId) {
        try {
            JSONObject o = new JSONObject();
            o.put("trackId", trackId);
            o.put("at", System.currentTimeMillis());
            try (FileOutputStream fo = new FileOutputStream(f)) {
                fo.write(o.toString().getBytes("UTF-8"));
                fo.flush();
            }
        } catch (Exception ignore) {
        }
    }

    private static String readSmall(File f) {
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private boolean trackExists(String id) {
        if (id == null || id.length() == 0) return false;
        try {
            List<Track> list = library.load();
            for (Track t : list) {
                if (id.equals(t.id)) return true;
            }
        } catch (Exception ignore) {
        }
        return false;
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private String saveCover(String trackId, byte[] img) {
        if (img == null || img.length < 64) return "";
        if (trackId == null || trackId.length() == 0) return "";
        try {
            File dir = new File(appCtx.getFilesDir(), "covers");
            if (!dir.exists() && !dir.mkdirs()) return "";
            
            String ext = ".jpg";
            if (img.length > 8 && (img[0] & 0xFF) == 0x89 && img[1] == 'P' && img[2] == 'N' && img[3] == 'G') {
                ext = ".png";
            } else if (img.length > 12 && img[0] == 'R' && img[1] == 'I' && img[2] == 'F' && img[3] == 'F'
                    && img[8] == 'W' && img[9] == 'E' && img[10] == 'B' && img[11] == 'P') {
                ext = ".webp";
            } else if (!(img.length > 3 && (img[0] & 0xFF) == 0xFF && (img[1] & 0xFF) == 0xD8)) {
                
                return "";
            }
            
            String safeId = Long.toHexString(Integer.toUnsignedLong(trackId.hashCode()));
            
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) {
                String n = f.getName();
                if (n.startsWith(safeId + ".")) {
                    
                    f.delete();
                }
            }
            File out = new File(dir, safeId + ext);
            try (FileOutputStream fo = new FileOutputStream(out)) {
                fo.write(img);
                fo.flush();
                fo.getFD().sync();
            }
            if (out.length() == 0) return "";
            return "https://appassets.androidplatform.net/cover/" + out.getName();
        } catch (Exception e) {
            Log.w(TAG, "封面落盘失败", e);
            return "";
        }
    }

    private String writeTrack(String outName, byte[] audio, String title, String artist) {
        return writeTrack(outName, audio, title, artist, null, null);
    }

    private String writeTrack(String outName, byte[] audio, String title, String artist,
                              String lyric, byte[] cover) {
        try {
            File dir = new File(appCtx.getFilesDir(), "music");
            if (!dir.exists() && !dir.mkdirs()) return "";

            File out = new File(dir, outName);
            int seq = 1;
            while (out.exists()) {
                int d2 = outName.lastIndexOf('.');
                String base = d2 >= 0 ? outName.substring(0, d2) : outName;
                String e2 = d2 >= 0 ? outName.substring(d2) : "";
                out = new File(dir, base + "_" + (seq++) + e2);
                if (seq > 999) break;
            }

            try (FileOutputStream fo = new FileOutputStream(out)) {
                fo.write(audio);
                fo.flush();
                fo.getFD().sync();
            }

            if (lyric != null && lyric.length() > 0) {
                LyricProvider.saveSidecar(out, lyric);
            }

            Track t = new Track();
            t.id = String.valueOf(out.getAbsolutePath().hashCode());
            t.title = title;
            t.artist = artist;
            t.uri = "file://" + out.getAbsolutePath();
            
            File side = LyricProvider.sidecarOf(out);
            if (side.exists()) t.lrc = "file://" + side.getAbsolutePath();
            
            String coverUrl = saveCover(t.id, cover);
            if (coverUrl.length() > 0) t.cover = coverUrl;
            t.imported = true;
            library.add(t);
            return t.id;
        } catch (Exception e) {
            Log.w(TAG, "落盘失败 " + outName, e);
            return "";
        }
    }

    private void pushTip(String text) {
        if (sink == null) return;
        try {
            sink.eval("window.TingYunNative&&window.TingYunNative.onTip&&window.TingYunNative.onTip("
                    + JSONObject.quote(text) + ")");
        } catch (Exception ignore) {
        }
    }

    @JavascriptInterface
    public void saveSession(String scene, int index, long posMs, boolean onPlayer) {
        
        try {
            prefs.setScene(safe(scene));
            prefs.saveSession(index, Math.max(0L, posMs), onPlayer);
        } catch (Throwable ignore) {
        }
        if (sessionSink != null) {
            sessionSink.onSession(safe(scene), index, Math.max(0L, posMs), onPlayer);
        }
    }

    public interface SessionSink {
        void onSession(String scene, int index, long posMs, boolean onPlayer);
    }

    private SessionSink sessionSink;

    public void setSessionSink(SessionSink s) {
        this.sessionSink = s;
    }

    @JavascriptInterface
    public void play(String url, String title) {
        if (!safeUri(url)) return;
        audio.setSource(safe(url), safe(title));
        audio.play();
    }

    @JavascriptInterface
    public void pause() {
        audio.pause();
    }

    @JavascriptInterface
    public void toggle() {
        audio.toggle();
    }

    @JavascriptInterface
    public void seekTo(long ms) {
        audio.seekTo(Math.max(0, ms));
    }

    @JavascriptInterface
    public boolean isPlaying() {
        return audio.isPlaying();
    }

    @JavascriptInterface
    public long position() {
        return audio.position();
    }

    @JavascriptInterface
    public long duration() {
        return audio.duration();
    }

    @JavascriptInterface
    public void stop() {
        audio.stop();
    }

    @JavascriptInterface
    public void setVolume(float v) {
        audio.setVolume(v);
    }

    @JavascriptInterface
    public void ambScene(String url, String scene) {
        if (url == null || url.length() == 0) return;
        
        String full = resolveAsset(url);
        if (!safeUri(full)) return;
        amb.setScene(full, safe(scene));
    }

    private static String resolveAsset(String u) {
        if (u == null) return "";
        String s = u.trim();
        String low = s.toLowerCase();
        if (low.startsWith("https://") || low.startsWith("http://")
                || low.startsWith("content://") || low.startsWith("file://")
                || low.startsWith("data:")) {
            return s;
        }
        
        while (s.startsWith("./")) s = s.substring(2);
        while (s.startsWith("/")) s = s.substring(1);
        if (s.startsWith("assets/")) s = s.substring(7);
        return "https://appassets.androidplatform.net/assets/" + s;
    }

    @JavascriptInterface
    public void ambEnable() {
        amb.enable();
    }

    @JavascriptInterface
    public void ambDisable() {
        amb.disable();
    }

    @JavascriptInterface
    public void ambVolume(float v) {
        amb.setVolume(v);
    }

    @JavascriptInterface
    public boolean ambIsOn() {
        return amb.isEnabled();
    }

    @JavascriptInterface
    public String ambSceneName() {
        return amb.scene();
    }

    public interface ScreenSink {
        
        void onScreenChanged(boolean immersive);
    }

    private ScreenSink screenSink;

    public void setScreenSink(ScreenSink s) {
        this.screenSink = s;
    }

    @JavascriptInterface
    public void setImmersive(boolean immersive) {
        ScreenSink s = screenSink;
        if (s != null) s.onScreenChanged(immersive);
    }
}
