package com.tingyun.app.player;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;

public class AmbientEngine {

    private static final String TAG = "TingYunAmb";

    private static final long XFADE_MS = 1500;

    private static final long FADE_STEP_MS = 60;

    private static final long SWITCH_FADE_MS = 800;

    private static final long MONITOR_MS = 200;

    private final Context appCtx;
    private final Handler main = new Handler(Looper.getMainLooper());

    private ExoPlayer a;
    private ExoPlayer b;

    private ExoPlayer active;
    
    private ExoPlayer standby;

    private String currentUrl;
    private String currentScene = "";

    private boolean enabled = false;
    
    private float targetVolume = 0.34f;

    private boolean crossfading = false;

    private int fadeTokenA = 0;
    private int fadeTokenB = 0;

    private final Runnable monitor = new Runnable() {
        @Override
        public void run() {
            if (!enabled || active == null) return;
            try {
                long dur = active.getDuration();
                long pos = active.getCurrentPosition();

                if (dur <= 0) {
                    main.postDelayed(this, MONITOR_MS);
                    return;
                }

                if (!crossfading && dur - pos <= XFADE_MS && dur - pos > 0) {
                    startCrossfade();
                }
            } catch (Exception e) {
                
                Log.d(TAG, "monitor 跳过：" + e.getMessage());
            }
            main.postDelayed(this, MONITOR_MS);
        }
    };

    public AmbientEngine(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
    }

    private void run(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else main.post(r);
    }

    private ExoPlayer newPlayer() {
        ExoPlayer p = new ExoPlayer.Builder(appCtx).build();
        p.setAudioAttributes(
                
                new androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(1)
                        .setContentType(2)
                        .build(),
                false);
        
        p.setVolume(0f);
        return p;
    }

    private void ensurePlayers() {
        if (a != null) return;
        a = newPlayer();
        b = newPlayer();
        active = a;
        standby = b;
    }

    public void setScene(final String url, final String scene) {
        if (url == null || url.length() == 0) return;
        
        final String real = toLocalFile(url);
        if (real == null) {
            Log.e(TAG, "setScene: 无法解析素材 " + url);
            return;
        }
        run(() -> {
            if (real.equals(currentUrl)) return;
            currentUrl = real;
            currentScene = scene == null ? "" : scene;

            ensurePlayers();

            if (!enabled) {
                
                return;
            }
            switchTo(real);
        });
    }

    private String toLocalFile(String url) {
        String rel = url;
        final String marker = "/assets/";
        int k = rel.indexOf(marker);
        if (k >= 0) {
            rel = rel.substring(k + marker.length());
        } else {
            while (rel.startsWith("./")) rel = rel.substring(2);
            while (rel.startsWith("/")) rel = rel.substring(1);
            if (rel.startsWith("assets/")) rel = rel.substring(7);
        }
        
        if (rel.startsWith("file://") || rel.startsWith("/")) return rel;
        if (rel.length() == 0 || rel.contains("://")) return null;

        try {
            File dir = new File(appCtx.getFilesDir(), "amb");
            if (!dir.exists() && !dir.mkdirs()) {
                Log.e(TAG, "toLocalFile: 无法创建目录 " + dir);
                return null;
            }
            String name = rel.substring(rel.lastIndexOf('/') + 1);
            File out = new File(dir, name);
            
            if (out.exists() && out.length() > 0) {
                return "file://" + out.getAbsolutePath();
            }
            InputStream in = appCtx.getAssets().open(rel);
            FileOutputStream fos = new FileOutputStream(out);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            fos.close();
            in.close();
            Log.e(TAG, "toLocalFile: 已复制 " + rel + " -> " + out + " (" + out.length() + "B)");
            return "file://" + out.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "toLocalFile 失败: " + rel, e);
            return null;
        }
    }

    private void switchTo(String url) {
        ensurePlayers();

        ExoPlayer outgoing = active;
        ExoPlayer incoming = standby;

        fade(outgoing, 0f, SWITCH_FADE_MS, () -> {
            outgoing.stop();
            outgoing.clearMediaItems();
        });

        incoming.stop();
        incoming.clearMediaItems();
        incoming.setVolume(0f);
        incoming.setMediaItem(MediaItem.fromUri(url).buildUpon().setMediaId(url).build());
        incoming.prepare();
        incoming.play();
        fade(incoming, targetVolume, SWITCH_FADE_MS * 2, null);

        active = incoming;
        standby = outgoing;

        crossfading = false;
        main.removeCallbacks(monitor);
        main.postDelayed(monitor, SWITCH_FADE_MS);
    }

    private void startCrossfade() {
        if (crossfading || currentUrl == null) return;
        crossfading = true;

        final ExoPlayer out = active;
        final ExoPlayer in = standby;
        if (out == null || in == null) {
            crossfading = false;
            return;
        }

        try {
            in.stop();
            in.clearMediaItems();
            in.setVolume(0f);
            in.setMediaItem(MediaItem.fromUri(currentUrl).buildUpon().setMediaId(currentUrl).build());
            in.prepare();
            in.play();
        } catch (Exception e) {
            crossfading = false;
            return;
        }

        fade(out, 0f, XFADE_MS, () -> {
            out.stop();
            out.clearMediaItems();
        });
        fade(in, targetVolume, XFADE_MS, () -> crossfading = false);

        active = in;
        standby = out;

        main.removeCallbacks(monitor);
        main.postDelayed(monitor, XFADE_MS);
    }

    private int nextToken(ExoPlayer p) {
        if (b != null && p == b) return ++fadeTokenB;
        return ++fadeTokenA;
    }

    private boolean validToken(ExoPlayer p, int token) {
        if (b != null && p == b) return token == fadeTokenB;
        return token == fadeTokenA;
    }

    private void fade(final ExoPlayer p, final float to, long ms, final Runnable done) {
        if (p == null) return;
        final float from = p.getVolume();
        final int steps = Math.max(1, (int) (ms / FADE_STEP_MS));
        final float delta = (to - from) / steps;

        final int token = nextToken(p);

        for (int i = 1; i <= steps; i++) {
            final float v = from + delta * i;
            final boolean last = (i == steps);
            main.postDelayed(() -> {
                
                if (!validToken(p, token)) return;
                try {
                    p.setVolume(Math.max(0f, Math.min(1f, v)));
                } catch (Exception e) {
                    return;
                }
                if (last && done != null) done.run();
            }, FADE_STEP_MS * i);
        }
    }

    public void enable() {
        run(() -> {
            enabled = true;
            ensurePlayers();
            if (currentUrl != null) {
                
                String now = active.getCurrentMediaItem() == null
                        ? null
                        : active.getCurrentMediaItem().mediaId;
                if (!currentUrl.equals(now)) {
                    switchTo(currentUrl);
                } else if (!active.isPlaying()) {
                    active.play();
                    fade(active, targetVolume, 1200, null);
                }
            }
            main.removeCallbacks(monitor);
            main.postDelayed(monitor, 500);
        });
    }

    public void disable() {
        run(() -> {
            enabled = false;
            main.removeCallbacks(monitor);
            final ExoPlayer p = active;
            if (p != null) {
                fade(p, 0f, 700, () -> {
                    try {
                        p.pause();
                    } catch (Exception ignored) {
                    }
                });
            }
        });
    }

    public void setVolume(final float v) {
        run(() -> {
            targetVolume = Math.max(0f, Math.min(1f, v));
            if (enabled && active != null && !crossfading) {
                fade(active, targetVolume, 260, null);
            }
        });
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String scene() {
        return currentScene == null ? "" : currentScene;
    }

    public void release() {
        
        final Runnable r = () -> {
            main.removeCallbacks(monitor);
            enabled = false;
            crossfading = false;
            if (a != null) {
                a.release();
                a = null;
            }
            if (b != null) {
                b.release();
                b = null;
            }
            active = null;
            standby = null;
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            main.post(r);
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
            }
        }
    }
}
