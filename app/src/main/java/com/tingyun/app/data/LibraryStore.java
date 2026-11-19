package com.tingyun.app.data;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

public class LibraryStore {

    private static final String TAG = "TingYunLib";
    private static final String FILE = "library.json";
    
    private static final int VERSION = 1;

    private final File file;
    private List<Track> cache;

    public LibraryStore(Context ctx) {
        this.file = new File(ctx.getApplicationContext().getFilesDir(), FILE);
    }

    public List<Track> load() {
        if (cache != null) return cache;

        if (!file.exists()) {
            cache = new ArrayList<>();
            return cache;
        }

        try {
            String text = readAll(file);
            if (text == null || text.length() == 0) {
                cache = new ArrayList<>();
                return cache;
            }
            JSONObject root = new JSONObject(text);
            cache = Track.fromArray(root.optJSONArray("tracks"));
            return cache;
        } catch (Exception e) {
            
            Log.w(TAG, "曲库解析失败，按空处理", e);
            cache = new ArrayList<>();
            return cache;
        }
    }

    public void save(List<Track> list) {
        cache = list;
        try {
            JSONObject root = new JSONObject();
            root.put("version", VERSION);
            root.put("tracks", Track.toArray(list));
            writeAll(file, root.toString());
        } catch (Exception e) {
            Log.w(TAG, "曲库保存失败", e);
        }
    }

    public void add(Track t) {
        List<Track> list = new ArrayList<>(load());
        
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).uri.equals(t.uri)) {
                list.set(i, t);
                save(list);
                return;
            }
        }
        list.add(t);
        save(list);
    }

    public void remove(String id) {
        List<Track> list = new ArrayList<>(load());
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).id.equals(id)) list.remove(i);
        }
        save(list);
    }

    public int count() {
        return load().size();
    }

    public boolean isEmpty() {
        return load().isEmpty();
    }

    private static String readAll(File f) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            byte[] buf = new byte[(int) raf.length()];
            raf.readFully(buf);
            return new String(buf, Charset.forName("UTF-8"));
        }
    }

    private static void writeAll(File f, String text) throws IOException {
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(Charset.forName("UTF-8")));
            out.flush();
            out.getFD().sync();
        }
    }
}
