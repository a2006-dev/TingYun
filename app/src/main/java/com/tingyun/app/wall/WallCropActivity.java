package com.tingyun.app.wall;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class WallCropActivity extends Activity {

    public static final String EXTRA_SRC = "src";
    public static final String EXTRA_OUT = "out";
    
    public static final String EXTRA_ASPECT = "aspect";

    private static final String TAG = "TingYun/crop";

    private CropView view;
    private String srcPath;
    private File outFile;
    
    private boolean cropping = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);

        final String s = getIntent().getStringExtra(EXTRA_SRC);
        final String o = getIntent().getStringExtra(EXTRA_OUT);
        final float aspect = getIntent().getFloatExtra(EXTRA_ASPECT, 0f);
        if (s == null || o == null) { finish(); return; }
        
        srcPath = s;
        outFile = new File(o);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#070a0f"));

        TextView loading = new TextView(this);
        loading.setText("正在载入…");
        loading.setTextSize(13);
        loading.setTextColor(Color.parseColor("#8fa3bb"));
        root.addView(loading, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        setContentView(root);

        new Thread(() -> {
            final Bitmap bmp = decode(srcPath);
            runOnUiThread(() -> {
                if (isFinishing()) {
                    if (bmp != null) bmp.recycle();
                    return;
                }
                if (bmp == null) {
                    Log.w(TAG, "解码失败的图片: " + srcPath);
                    setResult(RESULT_CANCELED);
                    finish();
                    return;
                }
                buildUi(root, bmp, aspect);
            });
        }, "wall-crop-decode").start();
    }

    private void buildUi(FrameLayout root, Bitmap bmp, float aspect) {
        root.removeAllViews();

        view = new CropView(this, bmp, aspect);
        root.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(20);
        top.setPadding(pad, dp(28), pad, 0);

        TextView hint = new TextView(this);
        hint.setText("双指缩放 · 拖动取景");
        hint.setTextSize(13);
        hint.setTextColor(Color.parseColor("#8fa3bb"));
        top.addView(hint, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView mode = new TextView(this);
        mode.setText("铺满");
        mode.setTextSize(13);
        mode.setTextColor(Color.parseColor("#eef3fa"));
        mode.setTypeface(Typeface.DEFAULT_BOLD);
        mode.setPadding(dp(14), dp(8), 0, dp(8));
        mode.setOnClickListener(v -> {
            boolean free = view.toggleFree();
            mode.setText(free ? "原始" : "铺满");
        });
        top.addView(mode);

        root.addView(top, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(pad, 0, pad, dp(34));

        TextView cancel = mkBtn("取消", "#8fa3bb");
        cancel.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
        TextView ok = mkBtn("完成", "#eef3fa");
        ok.setTypeface(Typeface.DEFAULT_BOLD);
        ok.setOnClickListener(v -> doCrop());

        bar.addView(cancel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(ok, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.BOTTOM;
        root.addView(bar, blp);
    }

    private TextView mkBtn(String text, String color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(15);
        t.setTextColor(Color.parseColor(color));
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(16), 0, dp(16));
        return t;
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private Bitmap decode(String path) {
        InputStream in = null;
        try {
            
            File f = new File(path);
            if (!f.exists() || f.length() == 0) {
                Log.w(TAG, "源文件不存在或为空: " + path);
                return null;
            }
            in = new java.io.FileInputStream(f);
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, opt);
            closeQuietly(in);

            int maxW = getResources().getDisplayMetrics().widthPixels * 2;
            int maxH = getResources().getDisplayMetrics().heightPixels * 2;
            int sample = 1;
            while (opt.outWidth / (sample * 2) >= maxW
                    && opt.outHeight / (sample * 2) >= maxH) {
                sample *= 2;
            }

            in = new java.io.FileInputStream(f);
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            o2.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeStream(in, null, o2);
        } catch (Exception e) {
            Log.w(TAG, "解码异常", e);
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) try { in.close(); } catch (Exception ignored) { }
    }

    private void doCrop() {
        if (view == null || cropping) return;
        cropping = true;
        
        new Thread(() -> {
            final Bitmap out = view.crop();
            boolean ok = false;
            try {
                if (out == null) {
                    ok = false;
                } else {
                    File dir = outFile.getParentFile();
                    if (dir != null && !dir.exists()) dir.mkdirs();
                    FileOutputStream fos = new FileOutputStream(outFile);
                    out.compress(Bitmap.CompressFormat.JPEG, 92, fos);
                    fos.close();
                    out.recycle();
                    ok = outFile.length() > 0;
                }
            } catch (Exception e) {
                Log.w(TAG, "裁剪保存失败", e);
                ok = false;
            }
            final boolean okf = ok;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (okf) {
                    Intent r = new Intent();
                    r.putExtra(EXTRA_OUT, outFile.getAbsolutePath());
                    setResult(RESULT_OK, r);
                    Log.i(TAG, "裁剪完成 " + outFile + " (" + outFile.length() + "B)");
                } else {
                    setResult(RESULT_CANCELED);
                }
                finish();
            });
        }, "wall-crop-save").start();
    }

    private static class CropView extends View {

        private final Bitmap bmp;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Paint dim = new Paint();
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint faint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF frame = new RectF();   
        private final RectF img = new RectF();     

        private float baseScale = 1f;   
        private float scale = 1f;       
        private float tx = 0f, ty = 0f; 
        private float lastX, lastY;
        private float lastDist = 0f;
        private float lockedAspect = 0f; 
        private boolean free = false;

        CropView(android.content.Context c, Bitmap b, float aspect) {
            super(c);
            bmp = b;
            lockedAspect = aspect;
            dim.setColor(Color.parseColor("#c8070a0f"));
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(1f * getResources().getDisplayMetrics().density);
            line.setColor(Color.parseColor("#66eef3fa"));
            faint.setStyle(Paint.Style.STROKE);
            faint.setStrokeWidth(1f * getResources().getDisplayMetrics().density);
            faint.setColor(Color.parseColor("#22eef3fa"));
        }

        boolean toggleFree() {
            free = !free;
            if (free) {
                
                lockedAspect = (float) bmp.getWidth() / (float) bmp.getHeight();
            }
            layoutFrame();
            resetTransform();
            invalidate();
            return free;
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            layoutFrame();
            resetTransform();
        }

        private void layoutFrame() {
            float padX = 24f * getResources().getDisplayMetrics().density;
            float padTop = 90f * getResources().getDisplayMetrics().density;
            float padBottom = 110f * getResources().getDisplayMetrics().density;
            float availW = getWidth() - padX * 2;
            float availH = getHeight() - padTop - padBottom;
            if (availW <= 0 || availH <= 0) return;

            if (lockedAspect <= 0f) {
                frame.set(padX, padTop, padX + availW, padTop + availH);
                return;
            }
            
            float w = availW, h = w / lockedAspect;
            if (h > availH) { h = availH; w = h * lockedAspect; }
            float cx = padX + availW / 2f;
            float cy = padTop + availH / 2f;
            frame.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
        }

        private void resetTransform() {
            if (bmp.getWidth() == 0 || frame.width() <= 0) return;
            float coverScale = Math.max(
                    frame.width() / (float) bmp.getWidth(),
                    frame.height() / (float) bmp.getHeight());
            baseScale = coverScale;
            scale = 1f;
            tx = 0f;
            ty = 0f;
            applyImageRect();
        }

        private void applyImageRect() {
            float w = bmp.getWidth() * baseScale * scale;
            float h = bmp.getHeight() * baseScale * scale;
            float cx = frame.centerX() + tx;
            float cy = frame.centerY() + ty;
            img.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
            clamp();
        }

        private void clamp() {
            float w = img.width(), h = img.height();
            if (w <= frame.width()) {
                tx += frame.centerX() - img.centerX();
            } else {
                if (img.left > frame.left) tx -= (img.left - frame.left);
                if (img.right < frame.right) tx += (frame.right - img.right);
            }
            if (h <= frame.height()) {
                ty += frame.centerY() - img.centerY();
            } else {
                if (img.top > frame.top) ty -= (img.top - frame.top);
                if (img.bottom < frame.bottom) ty += (frame.bottom - img.bottom);
            }
            float cw = bmp.getWidth() * baseScale * scale;
            float ch = bmp.getHeight() * baseScale * scale;
            float ncx = frame.centerX() + tx;
            float ncy = frame.centerY() + ty;
            img.set(ncx - cw / 2f, ncy - ch / 2f, ncx + cw / 2f, ncy + ch / 2f);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = e.getX();
                    lastY = e.getY();
                    lastDist = 0f;
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    lastDist = dist(e);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (e.getPointerCount() >= 2) {
                        float d = dist(e);
                        if (lastDist > 0f && d > 0f) {
                            float f = d / lastDist;
                            float before = scale;
                            scale = Math.max(1f, Math.min(6f, scale * f));
                            
                            float real = scale / before;
                            tx *= real;
                            ty *= real;
                            applyImageRect();
                            invalidate();
                        }
                        lastDist = d;
                    } else {
                        tx += e.getX() - lastX;
                        ty += e.getY() - lastY;
                        lastX = e.getX();
                        lastY = e.getY();
                        applyImageRect();
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_POINTER_UP:
                    lastDist = 0f;
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    return true;
            }
            return true;
        }

        private float dist(MotionEvent e) {
            if (e.getPointerCount() < 2) return 0f;
            float dx = e.getX(0) - e.getX(1);
            float dy = e.getY(0) - e.getY(1);
            return (float) Math.sqrt(dx * dx + dy * dy);
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            c.drawBitmap(bmp, null, img, paint);

            c.drawRect(0, 0, getWidth(), frame.top, dim);
            c.drawRect(0, frame.bottom, getWidth(), getHeight(), dim);
            c.drawRect(0, frame.top, frame.left, frame.bottom, dim);
            c.drawRect(frame.right, frame.top, getWidth(), frame.bottom, dim);

            c.drawRect(frame, line);
            
            float x1 = frame.left + frame.width() / 3f;
            float x2 = frame.left + frame.width() * 2f / 3f;
            float y1 = frame.top + frame.height() / 3f;
            float y2 = frame.top + frame.height() * 2f / 3f;
            c.drawLine(x1, frame.top, x1, frame.bottom, faint);
            c.drawLine(x2, frame.top, x2, frame.bottom, faint);
            c.drawLine(frame.left, y1, frame.right, y1, faint);
            c.drawLine(frame.left, y2, frame.right, y2, faint);
        }

        Bitmap crop() {
            try {
                float sx = bmp.getWidth() / img.width();
                float sy = bmp.getHeight() / img.height();
                int x = Math.round((frame.left - img.left) * sx);
                int y = Math.round((frame.top - img.top) * sy);
                int w = Math.round(frame.width() * sx);
                int h = Math.round(frame.height() * sy);
                if (w <= 0 || h <= 0) return null;
                x = Math.max(0, Math.min(x, bmp.getWidth() - 1));
                y = Math.max(0, Math.min(y, bmp.getHeight() - 1));
                w = Math.min(w, bmp.getWidth() - x);
                h = Math.min(h, bmp.getHeight() - y);
                return Bitmap.createBitmap(bmp, x, y, w, h);
            } catch (Exception e) {
                Log.w(TAG, "裁剪计算失败", e);
                return null;
            }
        }
    }
}
