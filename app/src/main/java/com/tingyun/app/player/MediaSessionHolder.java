package com.tingyun.app.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaStyleNotificationHelper;

import com.tingyun.app.MainActivity;

import java.io.File;

public class MediaSessionHolder {

    private static final String TAG = "TingYunMedia";

    private static final int NOTI_ID = 0x7E47;
    
    private static final String CHANNEL_ID = "tingyun_playback_v1";
    private static final String CHANNEL_NAME = "播放";

    private final Context ctx;
    private MediaSession session;
    private boolean foreground = false;

    private String title = "";
    private String coverPath = "";

    public MediaSessionHolder(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        ensureChannel();
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;

        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        
        ch.enableLights(false);
        ch.enableVibration(false);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
    }

    public void ensureSession(Object player) {
        try {
            if (session != null) return;
            if (!(player instanceof androidx.media3.common.Player)) return;

            session = new MediaSession.Builder(ctx, (androidx.media3.common.Player) player)
                    
                    .setSessionActivity(contentIntent())
                    .setId("TingYunSession")
                    .build();
            Log.d(TAG, "MediaSession 已建立");
        } catch (Throwable t) {
            
            Log.w(TAG, "MediaSession 建立失败，降级", t);
            session = null;
        }
    }

    public void setTrack(String title, String coverPath) {
        this.title = title == null ? "" : title;
        this.coverPath = coverPath == null ? "" : coverPath;
        if (foreground) startForeground();
    }

    public void startForeground() {
        if (session == null) return;
        if (!canNotify()) {
            
            Log.d(TAG, "无通知权限，跳过通知");
            return;
        }
        try {
            Notification n = buildNotification();
            if (n == null) return;
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            nm.notify(NOTI_ID, n);
            foreground = true;
        } catch (Throwable t) {
            Log.w(TAG, "通知推送失败", t);
        }
    }

    public void release() {
        try {
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTI_ID);
        } catch (Throwable ignore) {
        }
        foreground = false;

        if (session != null) {
            try {
                session.release();
            } catch (Throwable ignore) {
            }
            session = null;
        }
        Log.d(TAG, "MediaSession 已释放");
    }

    public boolean isForeground() {
        return foreground;
    }

    public boolean isActive() {
        return session != null;
    }

    @Nullable
    private Notification buildNotification() {
        if (session == null) return null;

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID);

        b.setSmallIcon(android.R.drawable.ic_media_play);
        b.setContentTitle(title.length() == 0 ? "停云" : title);
        b.setContentText("停云 · 正在播放");
        b.setContentIntent(contentIntent());
        b.setOngoing(true);
        b.setOnlyAlertOnce(true);
        b.setShowWhen(false);
        
        b.setPriority(NotificationCompat.PRIORITY_LOW);
        b.setVisibility(NotificationCompat.VISIBILITY_PUBLIC);

        Bitmap cover = loadCover();
        if (cover != null) b.setLargeIcon(cover);

        b.setStyle(new MediaStyleNotificationHelper.MediaStyle(session));

        return b.build();
    }

    private PendingIntent contentIntent() {
        Intent i = new Intent(ctx, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        return PendingIntent.getActivity(ctx, 0, i, flags);
    }

    private Bitmap loadCover() {
        if (coverPath == null || coverPath.length() == 0) return null;
        try {
            File f = coverPath.startsWith("file://")
                    ? new File(coverPath.substring(7)) : new File(coverPath);
            if (!f.exists()) return null;

            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int max = 512;
            int sample = 1;
            while (o.outWidth / sample > max || o.outHeight / sample > max) sample *= 2;

            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean canNotify() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true;
        return ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }
}
