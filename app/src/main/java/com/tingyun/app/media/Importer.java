package com.tingyun.app.media;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class Importer {

    private static final String TAG = "TingYunImport";

    public static final int PERSIST_FLAGS = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION;

    private static final long MAX_BYTES = 120L * 1024 * 1024;

    private final Context ctx;

    public Importer(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public void persist(Uri uri) {
        if (uri == null) return;
        try {
            ctx.getContentResolver().takePersistableUriPermission(uri, PERSIST_FLAGS);
        } catch (Exception e) {
            
            Log.w(TAG, "takePersistableUriPermission 失败: " + uri);
        }
    }

    public String displayName(Uri uri) {
        if (uri == null) return "";
        try (Cursor c = ctx.getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && n.length() > 0) return n;
            }
        } catch (Exception ignore) {
        }
        
        String p = uri.getLastPathSegment();
        return p == null ? "" : p;
    }

    public long sizeOf(Uri uri) {
        if (uri == null) return -1L;
        try (Cursor c = ctx.getContentResolver().query(
                uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Exception ignore) {
        }
        return -1L;
    }

    public boolean tooLarge(Uri uri) {
        long s = sizeOf(uri);
        return s > MAX_BYTES;
    }

    public byte[] readAll(Uri uri) throws Exception {
        long size = sizeOf(uri);
        if (size > MAX_BYTES) throw new java.io.IOException("file too large");
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new java.io.IOException("openInputStream == null");
            ByteArrayOutputStream bos = new ByteArrayOutputStream(
                    size > 0 && size < Integer.MAX_VALUE ? (int) size : 32 * 1024);
            byte[] buf = new byte[64 * 1024];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) throw new java.io.IOException("file too large");
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    public InputStream openStream(Uri uri) throws Exception {
        return ctx.getContentResolver().openInputStream(uri);
    }

    public List<Uri> listAudioInTree(Uri treeUri) {
        List<Uri> out = new ArrayList<>();
        if (treeUri == null) return out;

        String docId;
        try {
            docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri);
        } catch (Exception e) {
            return out;
        }
        Uri children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, docId);

        String[] projection = new String[]{
                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE
        };

        try (Cursor c = ctx.getContentResolver().query(children, projection, null, null, null)) {
            if (c == null) return out;
            while (c.moveToNext()) {
                String id = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                if (id == null) continue;
                Uri fileUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                        treeUri, id);
                if (android.provider.DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    continue;
                }
                
                if (isAudioName(name) || isLyricName(name)) out.add(fileUri);
            }
        } catch (Exception e) {
            Log.w(TAG, "遍历目录失败", e);
        }
        return out;
    }

    public static boolean isAudioName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        return n.endsWith(".mp3") || n.endsWith(".flac") || n.endsWith(".m4a")
                || n.endsWith(".aac") || n.endsWith(".ogg") || n.endsWith(".wav")
                || n.endsWith(".opus") || n.endsWith(".ncm")
                || n.endsWith(".qmc0") || n.endsWith(".qmc2") || n.endsWith(".qmc3")
                || n.endsWith(".qmc4") || n.endsWith(".qmc6") || n.endsWith(".qmc8")
                || n.endsWith(".qmcflac") || n.endsWith(".qmcogg")
                || n.endsWith(".mflac") || n.endsWith(".mflac0")
                || n.endsWith(".mgg") || n.endsWith(".mgg0") || n.endsWith(".mgg1")
                || n.endsWith(".mggl") || n.endsWith(".mmp4")
                || n.endsWith(".tkc") || n.endsWith(".bkc");
    }

    public static boolean isLyricName(String name) {
        return name != null && name.toLowerCase().endsWith(".lrc");
    }

    public File musicDir() {
        File d = new File(ctx.getFilesDir(), "music");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public File decodedDir() {
        File d = new File(ctx.getFilesDir(), "decoded");
        if (!d.exists()) d.mkdirs();
        return d;
    }
}
