package com.tingyun.app.player;

import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.Context;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

public class AudioEngine {

    private static final String TAG = "TingYunAudio";

    private static final long TICK_MS = 500;

    public interface Listener {
        void onState(boolean playing);

        void onProgress(long positionMs, long durationMs);

        void onEnded();

        void onError(String message);
    }

    private final Context appCtx;
    private final Handler main = new Handler(Looper.getMainLooper());

    private ExoPlayer player;
    private Listener listener;

    private String currentUrl;
    private String currentTitle;

    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private int lastFocusState = -1;

    private boolean resumeOnFocusGain = false;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (player != null && listener != null) {
                long d = player.getDuration();
                listener.onProgress(player.getCurrentPosition(), d > 0 ? d : 0);
            }
            main.postDelayed(this, TICK_MS);
        }
    };

    private final AudioManager.OnAudioFocusChangeListener focusListener = this::handleFocusChange;

    private BroadcastReceiver noisyReceiver;

    private void registerNoisy() {
        if (noisyReceiver != null) return;
        noisyReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) return;
                Log.d(TAG, "耳机拔出，暂停");
                
                resumeOnFocusGain = false;
                pause();
            }
        };
        try {
            IntentFilter f = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
            if (Build.VERSION.SDK_INT >= 33) {
                appCtx.registerReceiver(noisyReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                appCtx.registerReceiver(noisyReceiver, f);
            }
        } catch (Exception e) {
            Log.w(TAG, "注册耳机广播失败", e);
            noisyReceiver = null;
        }
    }

    private void unregisterNoisy() {
        if (noisyReceiver == null) return;
        try {
            appCtx.unregisterReceiver(noisyReceiver);
        } catch (Exception ignore) {
        }
        noisyReceiver = null;
    }

    public AudioEngine(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
        this.audioManager = (AudioManager) appCtx.getSystemService(Context.AUDIO_SERVICE);
        
        registerNoisy();
        
        registerDeviceCallback();
    }

    private void run(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else main.post(r);
    }

    public void setListener(final Listener l) {
        run(() -> this.listener = l);
    }

    private void ensurePlayer() {
        
        if (player != null) return;
        player = new ExoPlayer.Builder(appCtx).build();

        player.setAudioAttributes(
                new androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(1)
                        .setContentType(2)
                        .build(),
                
                false);

        player.addListener(new Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (listener != null) listener.onState(isPlaying);
                if (isPlaying) startTicker();
                else stopTicker();
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) {
                    stopTicker();
                    if (listener != null) listener.onEnded();
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                stopTicker();
                Log.w(TAG, "播放失败", error);
                
                if (listener != null) listener.onError(error.getMessage());
            }
        });
    }

    private void startTicker() {
        main.removeCallbacks(ticker);
        main.post(ticker);
    }

    private void stopTicker() {
        main.removeCallbacks(ticker);
    }

    public void setSource(final String url, final String title) {
        run(() -> {
            ensurePlayer();
            if (url == null || url.length() == 0) return;
            currentUrl = url;
            currentTitle = title == null ? "" : title;
            player.setMediaItem(MediaItem.fromUri(url));
            player.prepare();
        });
    }

    public void play() {
        run(() -> {
            ensurePlayer();
            if (player.getMediaItemCount() == 0 && currentUrl != null) {
                player.setMediaItem(MediaItem.fromUri(currentUrl));
                player.prepare();
            }
            if (!requestFocus()) return;
            player.play();
            startTicker();
        });
    }

    public void pause() {
        run(() -> {
            if (player != null) player.pause();
            stopTicker();
        });
    }

    public void toggle() {
        run(() -> {
            if (player != null && player.isPlaying()) {
                player.pause();
                stopTicker();
            } else {
                ensurePlayer();
                if (player.getMediaItemCount() == 0 && currentUrl != null) {
                    player.setMediaItem(MediaItem.fromUri(currentUrl));
                    player.prepare();
                }
                if (!requestFocus()) return;
                player.play();
                startTicker();
            }
        });
    }

    public void seekTo(final long ms) {
        run(() -> {
            if (player != null) player.seekTo(Math.max(0, ms));
        });
    }

    public boolean isPlaying() {
        ExoPlayer p = player;
        return p != null && p.isPlaying();
    }

    public long position() {
        ExoPlayer p = player;
        return p == null ? 0 : p.getCurrentPosition();
    }

    public long duration() {
        ExoPlayer p = player;
        return p == null ? 0 : p.getDuration();
    }

    public String currentTitle() {
        return currentTitle == null ? "" : currentTitle;
    }

    public ExoPlayer player() {
        ensurePlayer();
        return player;
    }

    public void stop() {
        run(() -> {
            if (player != null) player.stop();
            stopTicker();
            abortFocus();
        });
    }

    public void setVolume(final float v) {
        run(() -> {
            if (player != null) player.setVolume(Math.max(0f, Math.min(1f, v)));
        });
    }

    public void release() {
        
        final Runnable r = () -> {
            unregisterNoisy();
            unregisterDeviceCallback();
            stopTicker();
            abortFocus();
            if (player != null) {
                player.release();
                player = null;
            }
            listener = null;
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

    private boolean requestFocus() {
        if (audioManager == null) return true;

        int res;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (focusRequest == null) {
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        
                        .setAudioAttributes(new android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                        
                        .setWillPauseWhenDucked(false)
                        .setOnAudioFocusChangeListener(focusListener)
                        .build();
            }
            res = audioManager.requestAudioFocus(focusRequest);
        } else {
            res = audioManager.requestAudioFocus(
                    focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
        Log.d(TAG, "requestAudioFocus = " + res);
        return res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abortFocus() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (focusRequest != null) audioManager.abandonAudioFocusRequest(focusRequest);
        } else {
            audioManager.abandonAudioFocus(focusListener);
        }
    }

    private void handleFocusChange(int change) {
        Log.d(TAG, "onAudioFocusChange = " + change);
        lastFocusState = change;
        if (player == null) return;

        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                resumeOnFocusGain = false;
                player.pause();
                stopTicker();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                resumeOnFocusGain = player.isPlaying();
                player.pause();
                stopTicker();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                
                resumeOnFocusGain = false;
                player.setVolume(0.2f);
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                player.setVolume(1f);
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false;
                    player.play();
                    startTicker();
                }
                break;
            default:
                break;
        }
    }

    private AudioDeviceCallback deviceCallback;
    
    private boolean lastPrivateOut = false;

    private void registerDeviceCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        if (audioManager == null || deviceCallback != null) return;

        deviceCallback = new AudioDeviceCallback() {
            @Override
            public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
                updatePrivateOut();
            }

            @Override
            public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
                boolean wasPrivate = lastPrivateOut;
                updatePrivateOut();
                
                if (wasPrivate && !lastPrivateOut) {
                    main.post(() -> {
                        if (player != null && player.isPlaying()) {
                            Log.d(TAG, "私密输出断开，暂停");
                            player.pause();
                            stopTicker();
                            if (listener != null) listener.onState(false);
                        }
                    });
                }
            }
        };
        try {
            audioManager.registerAudioDeviceCallback(deviceCallback, main);
        } catch (Throwable t) {
            deviceCallback = null;
        }
        updatePrivateOut();
    }

    private void unregisterDeviceCallback() {
        if (audioManager == null || deviceCallback == null) return;
        try {
            audioManager.unregisterAudioDeviceCallback(deviceCallback);
        } catch (Throwable ignore) {
        }
        deviceCallback = null;
    }

    private void updatePrivateOut() {
        if (audioManager == null) return;
        boolean priv = false;
        try {
            AudioDeviceInfo[] outs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            for (AudioDeviceInfo d : outs) {
                int t = d.getType();
                if (t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                        || t == AudioDeviceInfo.TYPE_WIRED_HEADSET
                        || t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                        || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                        || t == AudioDeviceInfo.TYPE_USB_HEADSET
                        || t == AudioDeviceInfo.TYPE_USB_DEVICE) {
                    priv = true;
                    break;
                }
            }
        } catch (Throwable ignore) {
        }
        lastPrivateOut = priv;
    }
}
