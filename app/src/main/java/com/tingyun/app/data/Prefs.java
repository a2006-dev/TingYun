package com.tingyun.app.data;

import android.content.Context;
import android.content.SharedPreferences;

public class Prefs {

    private static final String FILE = "tingyun_prefs";

    private static final String K_BG_PLAY = "bg_play";
    
    private static final String K_AUTO_LYRIC = "auto_lyric";
    
    private static final String K_KEEP_AWAKE = "keep_awake";
    
    private static final String K_DIM = "dim_strength";
    
    private static final String K_LYRIC_OFF = "lyric_offset";
    
    private static final String K_FADE_ON = "fade_on";
    private static final String K_FADE_DELAY = "fade_delay";
    private static final String K_FADE_LEVEL = "fade_level";
    
    private static final String K_SCENE = "amb_scene";
    
    private static final String K_AMB_VOL = "amb_vol";
    
    private static final String K_WALL_IMAGE = "wall_image_url";
    
    private static final String K_WALLPAPER = "wallpaper_url";
    
    private static final String K_WALL_LIST = "wall_image_list";
    private static final String K_WALL_CUR = "wall_image_current";
    
    private static final String K_LAST_INDEX = "last_index";
    private static final String K_LAST_POS = "last_pos";
    private static final String K_LAST_ON = "last_on_player";

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        this.sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public boolean bgPlay() {
        return sp.getBoolean(K_BG_PLAY, false);
    }

    public void setBgPlay(boolean v) {
        sp.edit().putBoolean(K_BG_PLAY, v).apply();
    }

    public boolean autoLyric() {
        return sp.getBoolean(K_AUTO_LYRIC, true);
    }

    public void setAutoLyric(boolean v) {
        sp.edit().putBoolean(K_AUTO_LYRIC, v).apply();
    }

    public boolean keepAwake() {
        return sp.getBoolean(K_KEEP_AWAKE, true);
    }

    public void setKeepAwake(boolean v) {
        sp.edit().putBoolean(K_KEEP_AWAKE, v).apply();
    }

    public int dimStrength() {
        return sp.getInt(K_DIM, 55);
    }

    public void setDimStrength(int v) {
        sp.edit().putInt(K_DIM, Math.max(0, Math.min(100, v))).apply();
    }

    public int lyricOffset() {
        return sp.getInt(K_LYRIC_OFF, 0);
    }

    public void setLyricOffset(int ms) {
        sp.edit().putInt(K_LYRIC_OFF, Math.max(-5000, Math.min(5000, ms))).apply();
    }

    public boolean fadeOn() {
        return sp.getBoolean(K_FADE_ON, true);
    }

    public void setFadeOn(boolean v) {
        sp.edit().putBoolean(K_FADE_ON, v).apply();
    }

    public int fadeDelay() {
        return sp.getInt(K_FADE_DELAY, 6);
    }

    public void setFadeDelay(int v) {
        sp.edit().putInt(K_FADE_DELAY, Math.max(0, Math.min(60, v))).apply();
    }

    public int fadeLevel() {
        return sp.getInt(K_FADE_LEVEL, 40);
    }

    public void setFadeLevel(int v) {
        sp.edit().putInt(K_FADE_LEVEL, Math.max(0, Math.min(100, v))).apply();
    }

    public String scene() {
        String s = sp.getString(K_SCENE, "rain");
        if ("fire".equals(s) || "forest".equals(s) || "waves".equals(s) || "rain".equals(s)) return s;
        return "rain";
    }

    public void setScene(String v) {
        String s = v == null ? "" : v.trim();
        if ("fire".equals(s) || "forest".equals(s) || "waves".equals(s) || "rain".equals(s)) {
            sp.edit().putString(K_SCENE, s).apply();
        }
    }

    public int ambVolume() {
        return sp.getInt(K_AMB_VOL, 34);
    }

    public void setAmbVolume(int v) {
        sp.edit().putInt(K_AMB_VOL, Math.max(0, Math.min(100, v))).apply();
    }

    public String wallImage() {
        return sp.getString(K_WALL_IMAGE, legacyIfImage());
    }
    public void setWallImage(String url) {
        sp.edit().putString(K_WALL_IMAGE, url == null ? "" : url).apply();
    }

    public java.util.List<String> wallList() {
        java.util.List<String> out = new java.util.ArrayList<>();
        String raw = sp.getString(K_WALL_LIST, "");
        if (raw == null || raw.length() == 0) return out;
        try {
            org.json.JSONArray arr = new org.json.JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                String n = arr.optString(i, "");
                if (n != null && n.length() > 0) out.add(n);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public void setWallList(java.util.List<String> names) {
        org.json.JSONArray arr = new org.json.JSONArray();
        if (names != null) {
            for (String n : names) {
                if (n != null && n.length() > 0) arr.put(n);
            }
        }
        sp.edit().putString(K_WALL_LIST, arr.toString()).apply();
    }

    public boolean addWall(String name) {
        if (name == null || name.length() == 0) return false;
        java.util.List<String> list = wallList();
        if (list.contains(name)) return true;
        list.add(name);
        setWallList(list);
        return false;
    }

    public void removeWall(String name) {
        java.util.List<String> list = wallList();
        if (list.remove(name)) setWallList(list);
        
        if (name != null && name.equals(wallImage())) {
            sp.edit().putString(K_WALL_IMAGE, "").apply();
        }
    }

    private String legacyIfImage() {
        String old = sp.getString(K_WALLPAPER, "");
        return (old.startsWith("data:image/")) ? old : "";
    }

    public int lastIndex() {
        return sp.getInt(K_LAST_INDEX, -1);
    }

    public long lastPos() {
        return sp.getLong(K_LAST_POS, 0L);
    }

    public boolean lastOnPlayer() {
        return sp.getBoolean(K_LAST_ON, false);
    }

    public void saveSession(int index, long posMs, boolean onPlayer) {
        sp.edit()
                .putInt(K_LAST_INDEX, Math.max(-1, index))
                .putLong(K_LAST_POS, Math.max(0L, posMs))
                .putBoolean(K_LAST_ON, onPlayer)
                .apply();
    }

    public void clearSession() {
        sp.edit()
                .putInt(K_LAST_INDEX, -1)
                .putLong(K_LAST_POS, 0L)
                .putBoolean(K_LAST_ON, false)
                .apply();
    }
}
