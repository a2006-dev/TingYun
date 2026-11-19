package com.tingyun.app.data;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class Track {

    public String id = "";
    
    public String title = "";
    
    public String artist = "";
    
    public String uri = "";
    
    public String cover = "";
    
    public String lrc = "";
    
    public long durationMs = 0L;
    
    public boolean imported = false;

    public Track() {
    }

    public static Track fromJson(JSONObject o) {
        Track t = new Track();
        t.id = o.optString("id", "");
        t.title = o.optString("title", "");
        t.artist = o.optString("artist", "");
        t.uri = o.optString("uri", "");
        t.cover = o.optString("cover", "");
        t.lrc = o.optString("lrc", "");
        t.durationMs = o.optLong("durationMs", 0L);
        t.imported = o.optBoolean("imported", false);
        return t;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("title", title);
        o.put("artist", artist);
        o.put("uri", uri);
        o.put("cover", cover);
        o.put("lrc", lrc);
        o.put("durationMs", durationMs);
        o.put("imported", imported);
        return o;
    }

    public String subtitle() {
        return artist == null || artist.length() == 0 ? "未知歌手" : artist;
    }

    public static JSONArray toArray(java.util.List<Track> list) throws JSONException {
        JSONArray a = new JSONArray();
        for (Track t : list) a.put(t.toJson());
        return a;
    }

    public static java.util.List<Track> fromArray(JSONArray a) {
        java.util.List<Track> list = new java.util.ArrayList<>();
        if (a == null) return list;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) list.add(Track.fromJson(o));
        }
        return list;
    }
}
