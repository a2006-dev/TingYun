package com.tingyun.app.lyric;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

public final class LyricProvider {

    private static final String TAG = "TingYunLyric";
    private static final int TIMEOUT = 6000;
    
    private static final int MAX_BODY = 512 * 1024;

    private LyricProvider() {
    }

    public static final class Result {
        public String lrc = "";
        public String tlyric = "";
        
        public String from = "";
        
        public String matchTitle = "";
        public String matchArtist = "";
        
        public int candidates = 0;
        
        public boolean rejected = false;

        public boolean isEmpty() {
            return lrc == null || lrc.length() == 0;
        }
    }

    public static Result local(File musicFile) {
        Result r = new Result();
        if (musicFile == null) return r;

        File side = sidecarOf(musicFile);
        if (side.exists()) {
            String txt = readText(side);
            if (txt != null && txt.length() > 0) {
                r.lrc = txt;
                r.from = "local";
                r.matchTitle = baseName(musicFile);
                return r;
            }
            
            r.rejected = true;
        }
        return r;
    }

    public static boolean saveSidecar(File musicFile, String lrc) {
        if (musicFile == null || lrc == null || lrc.length() == 0) return false;
        try {
            File out = sidecarOf(musicFile);
            try (FileOutputStream fo = new FileOutputStream(out)) {
                fo.write(lrc.getBytes(Charset.forName("UTF-8")));
                fo.flush();
                fo.getFD().sync();
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "歌词落盘失败", e);
            return false;
        }
    }

    public static File sidecarOf(File musicFile) {
        String n = musicFile.getName();
        int dot = n.lastIndexOf('.');
        String base = dot > 0 ? n.substring(0, dot) : n;
        return new File(musicFile.getParentFile(), base + ".lrc");
    }

    public static Result online(Context ctx, String title, String artist) {
        Result r = new Result();
        if (title == null || title.length() == 0) return r;

        try {
            r = netease(title, artist);
            if (!r.isEmpty()) return r;
        } catch (Exception e) {
            Log.d(TAG, "网易云未取到，" + e.getClass().getSimpleName());
        }

        try {
            r = qqmusic(title, artist);
            if (!r.isEmpty()) return r;
        } catch (Exception e) {
            Log.d(TAG, "QQ音乐未取到，" + e.getClass().getSimpleName());
        }

        return new Result();
    }

    private static Result netease(String title, String artist) throws Exception {
        Result r = new Result();

        String kw = artist != null && artist.length() > 0 ? title + " " + artist : title;
        String searchUrl = "https://music.163.com/api/search/get/web?s="
                + URLEncoder.encode(kw, "UTF-8") + "&type=1&limit=5&offset=0";
        String body = get(searchUrl, "https://music.163.com");
        if (body == null) return r;

        JSONObject root = new JSONObject(body);
        JSONObject result = root.optJSONObject("result");
        if (result == null) return r;
        JSONArray songs = result.optJSONArray("songs");
        if (songs == null || songs.length() == 0) return r;

        r.candidates = songs.length();
        JSONObject first = songs.optJSONObject(0);
        if (first == null) return r;

        long id = first.optLong("id", 0L);
        if (id <= 0) return r;
        r.matchTitle = first.optString("name", "");
        JSONArray artists = first.optJSONArray("artists");
        if (artists != null && artists.length() > 0) {
            JSONObject a0 = artists.optJSONObject(0);
            if (a0 != null) r.matchArtist = a0.optString("name", "");
        }

        String lyricUrl = "https://music.163.com/api/song/lyric?id=" + id + "&lv=-1&kv=-1&tv=-1";
        String lb = get(lyricUrl, "https://music.163.com");
        if (lb == null) return r;

        JSONObject lroot = new JSONObject(lb);
        JSONObject lrcObj = lroot.optJSONObject("lrc");
        if (lrcObj != null) r.lrc = lrcObj.optString("lyric", "");
        JSONObject tObj = lroot.optJSONObject("tlyric");
        if (tObj != null) r.tlyric = tObj.optString("lyric", "");
        r.from = "netease";
        return r;
    }

    private static Result qqmusic(String title, String artist) throws Exception {
        Result r = new Result();

        String kw = artist != null && artist.length() > 0 ? title + " " + artist : title;
        JSONObject searchData = new JSONObject();
        JSONObject req = new JSONObject();
        JSONObject param = new JSONObject();
        param.put("query", kw);
        param.put("page_num", 1);
        param.put("num_per_page", 5);
        param.put("search_type", 0);
        req.put("module", "music.search.SearchCgiService");
        req.put("method", "DoSearchForQQMusicDesktop");
        req.put("param", param);
        searchData.put("req_1", req);

        String url = "https://u.y.qq.com/cgi-bin/musicu.fcg?format=json&data="
                + URLEncoder.encode(searchData.toString(), "UTF-8");
        String body = get(url, "https://y.qq.com");
        if (body == null) return r;

        JSONObject root = new JSONObject(body);
        JSONObject r1 = root.optJSONObject("req_1");
        if (r1 == null) return r;
        JSONObject data = r1.optJSONObject("data");
        if (data == null) return r;
        JSONObject bodyObj = data.optJSONObject("body");
        if (bodyObj == null) return r;
        JSONObject song = bodyObj.optJSONObject("song");
        if (song == null) return r;
        JSONArray list = song.optJSONArray("list");
        if (list == null || list.length() == 0) return r;

        r.candidates = list.length();
        JSONObject s0 = list.optJSONObject(0);
        if (s0 == null) return r;
        String mid = s0.optString("mid", "");
        if (mid.length() == 0) return r;
        r.matchTitle = s0.optString("title", "");
        JSONArray singers = s0.optJSONArray("singer");
        if (singers != null && singers.length() > 0) {
            JSONObject g0 = singers.optJSONObject(0);
            if (g0 != null) r.matchArtist = g0.optString("name", "");
        }

        JSONObject lyricData = new JSONObject();
        JSONObject lreq = new JSONObject();
        JSONObject lparam = new JSONObject();
        lparam.put("songMID", mid);
        lparam.put("songID", 0);
        lreq.put("module", "music.musichallSong.PlayLyricInfo");
        lreq.put("method", "GetPlayLyricInfo");
        lreq.put("param", lparam);
        lyricData.put("req_1", lreq);

        String lurl = "https://u.y.qq.com/cgi-bin/musicu.fcg?format=json&data="
                + URLEncoder.encode(lyricData.toString(), "UTF-8");
        String lb = get(lurl, "https://y.qq.com");
        if (lb == null) return r;

        JSONObject lroot = new JSONObject(lb);
        JSONObject lr1 = lroot.optJSONObject("req_1");
        if (lr1 == null) return r;
        JSONObject ldata = lr1.optJSONObject("data");
        if (ldata == null) return r;

        String b64 = ldata.optString("lyric", "");
        if (b64.length() > 0) r.lrc = decodeB64(b64);
        String t64 = ldata.optString("trans", "");
        if (t64.length() > 0) r.tlyric = decodeB64(t64);
        r.from = "qqmusic";
        return r;
    }

    private static String get(String url, String referer) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(TIMEOUT);
            c.setReadTimeout(TIMEOUT);
            c.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) TingYun/1.0");
            
            if (referer != null) c.setRequestProperty("Referer", referer);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            if (code != 200) return null;

            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                    if (bos.size() > MAX_BODY) break;
                }
                return new String(bos.toByteArray(), Charset.forName("UTF-8"));
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String decodeB64(String s) {
        try {
            byte[] b = android.util.Base64.decode(s, android.util.Base64.DEFAULT);
            return b == null ? "" : new String(b, Charset.forName("UTF-8"));
        } catch (Exception e) {
            return "";
        }
    }

    private static String readText(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), Charset.forName("UTF-8"));
        } catch (Exception e) {
            return "";
        }
    }

    private static String baseName(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }
    
    public static List<String> describe(Result r) {
        List<String> out = new ArrayList<>();
        if (r == null || r.isEmpty()) return out;
        out.add(r.matchTitle + (r.matchArtist.length() > 0 ? " - " + r.matchArtist : ""));
        return out;
    }

    public static final class Candidate {
        public String source = "";      
        public String songId = "";      
        public String title = "";
        public String artist = "";
        public String album = "";
        public String coverUrl = "";    
        public long durationMs = 0L;
    }

    public static List<Candidate> search(Context ctx, String keyword, int limit) {
        List<Candidate> out = new ArrayList<>();
        if (keyword == null || keyword.trim().length() == 0) return out;
        String kw = keyword.trim();
        int n = limit <= 0 ? 8 : limit;

        try {
            out.addAll(searchNetease(kw, n));
        } catch (Exception e) {
            Log.d(TAG, "网易云搜索失败 " + e.getClass().getSimpleName());
        }
        try {
            out.addAll(searchQq(kw, n));
        } catch (Exception e) {
            Log.d(TAG, "QQ音乐搜索失败 " + e.getClass().getSimpleName());
        }
        return out;
    }

    private static List<Candidate> searchNetease(String kw, int limit) throws Exception {
        List<Candidate> out = new ArrayList<>();
        
        String searchUrl = "https://music.163.com/api/search/get/web?s="
                + URLEncoder.encode(kw, "UTF-8") + "&type=1&limit=" + limit + "&offset=0";
        String body = get(searchUrl, "https://music.163.com");
        if (body == null) return out;

        JSONObject root = new JSONObject(body);
        JSONObject result = root.optJSONObject("result");
        if (result == null) return out;
        JSONArray songs = result.optJSONArray("songs");
        if (songs == null) return out;

        for (int i = 0; i < songs.length() && i < limit; i++) {
            JSONObject s = songs.optJSONObject(i);
            if (s == null) continue;
            long id = s.optLong("id", 0L);
            if (id <= 0) continue;

            Candidate c = new Candidate();
            c.source = "netease";
            c.songId = String.valueOf(id);
            c.title = s.optString("name", "");
            c.durationMs = s.optLong("duration", 0L);

            JSONArray artists = s.optJSONArray("artists");
            StringBuilder sb = new StringBuilder();
            if (artists != null) {
                for (int j = 0; j < artists.length(); j++) {
                    JSONObject a = artists.optJSONObject(j);
                    if (a == null) continue;
                    String nm = a.optString("name", "");
                    if (nm.length() == 0) continue;
                    if (sb.length() > 0) sb.append(" / ");
                    sb.append(nm);
                }
            }
            c.artist = sb.toString();

            JSONObject album = s.optJSONObject("album");
            if (album != null) {
                c.album = album.optString("name", "");
                
                String pic = album.optString("picUrl", "");
                if (pic.length() > 0) {
                    c.coverUrl = pic + (pic.contains("?") ? "&" : "?") + "param=200y200";
                }
            }
            out.add(c);
        }
        return out;
    }

    private static List<Candidate> searchQq(String kw, int limit) throws Exception {
        List<Candidate> out = new ArrayList<>();

        JSONObject searchData = new JSONObject();
        JSONObject req = new JSONObject();
        JSONObject param = new JSONObject();
        param.put("query", kw);
        param.put("page_num", 1);
        param.put("num_per_page", limit);
        param.put("search_type", 0);
        req.put("module", "music.search.SearchCgiService");
        req.put("method", "DoSearchForQQMusicDesktop");
        req.put("param", param);
        searchData.put("req_1", req);

        String url = "https://u.y.qq.com/cgi-bin/musicu.fcg?format=json&data="
                + URLEncoder.encode(searchData.toString(), "UTF-8");
        String body = get(url, "https://y.qq.com");
        if (body == null) return out;

        JSONObject root = new JSONObject(body);
        JSONObject r1 = root.optJSONObject("req_1");
        if (r1 == null) return out;
        JSONObject data = r1.optJSONObject("data");
        if (data == null) return out;
        JSONObject bodyObj = data.optJSONObject("body");
        if (bodyObj == null) return out;
        JSONObject song = bodyObj.optJSONObject("song");
        if (song == null) return out;
        JSONArray list = song.optJSONArray("list");
        if (list == null) return out;

        for (int i = 0; i < list.length() && i < limit; i++) {
            JSONObject s = list.optJSONObject(i);
            if (s == null) continue;
            String mid = s.optString("mid", "");
            if (mid.length() == 0) continue;

            Candidate c = new Candidate();
            c.source = "qqmusic";
            c.songId = mid;
            c.title = s.optString("title", "");
            c.durationMs = s.optLong("interval", 0L) * 1000L;

            JSONArray singers = s.optJSONArray("singer");
            StringBuilder sb = new StringBuilder();
            if (singers != null) {
                for (int j = 0; j < singers.length(); j++) {
                    JSONObject g = singers.optJSONObject(j);
                    if (g == null) continue;
                    String nm = g.optString("name", "");
                    if (nm.length() == 0) continue;
                    if (sb.length() > 0) sb.append(" / ");
                    sb.append(nm);
                }
            }
            c.artist = sb.toString();

            JSONObject album = s.optJSONObject("album");
            if (album != null) {
                c.album = album.optString("name", "");
                String pmid = album.optString("mid", "");
                if (pmid.length() > 0) {
                    c.coverUrl = "https://y.gtimg.cn/music/photo_new/T002R300x300M000"
                            + pmid + ".jpg";
                }
            }
            out.add(c);
        }
        return out;
    }

    public static Result byId(Context ctx, String source, String songId) {
        Result r = new Result();
        if (songId == null || songId.length() == 0) return r;
        try {
            if ("netease".equals(source)) {
                String lyrical = "https://music.163.com/api/song/lyric?id=" + songId
                        + "&lv=-1&kv=-1&tv=-1";
                String lb = get(lyrical, "https://music.163.com");
                if (lb == null) return r;
                JSONObject lroot = new JSONObject(lb);
                JSONObject lrcObj = lroot.optJSONObject("lrc");
                if (lrcObj != null) r.lrc = lrcObj.optString("lyric", "");
                JSONObject tObj = lroot.optJSONObject("tlyric");
                if (tObj != null) r.tlyric = tObj.optString("lyric", "");
                r.from = "netease";
            } else if ("qqmusic".equals(source)) {
                JSONObject lyricData = new JSONObject();
                JSONObject lreq = new JSONObject();
                JSONObject lparam = new JSONObject();
                lparam.put("songMID", songId);
                lparam.put("songID", 0);
                lreq.put("module", "music.musichallSong.PlayLyricInfo");
                lreq.put("method", "GetPlayLyricInfo");
                lreq.put("param", lparam);
                lyricData.put("req_1", lreq);

                String lurl = "https://u.y.qq.com/cgi-bin/musicu.fcg?format=json&data="
                        + URLEncoder.encode(lyricData.toString(), "UTF-8");
                String lb = get(lurl, "https://y.qq.com");
                if (lb == null) return r;
                JSONObject lroot = new JSONObject(lb);
                JSONObject lr1 = lroot.optJSONObject("req_1");
                if (lr1 == null) return r;
                JSONObject ldata = lr1.optJSONObject("data");
                if (ldata == null) return r;
                String b64 = ldata.optString("lyric", "");
                if (b64.length() > 0) r.lrc = decodeB64(b64);
                String t64 = ldata.optString("trans", "");
                if (t64.length() > 0) r.tlyric = decodeB64(t64);
                r.from = "qqmusic";
            }
        } catch (Exception e) {
            Log.d(TAG, "按 id 取词失败 " + e.getClass().getSimpleName());
        }
        return r;
    }
}
