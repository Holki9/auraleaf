package com.holki.auraleaf;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.LruCache;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PdfManager {

    public interface PageCallback {
        void onPageRendered(int pageIndex, Bitmap bitmap);
    }

    private ParcelFileDescriptor pfd;
    private PdfRenderer renderer;
    private int pageCount = 0;
    private LruCache<Integer, Bitmap> memoryCache;
    private ExecutorService renderExecutor;
    private Handler mainHandler;
    private float pageAspectRatio = 1.414f;

    public PdfManager(Context context, Uri uri) throws Exception {
        mainHandler = new Handler(Looper.getMainLooper());
        renderExecutor = Executors.newFixedThreadPool(2);

        // Max 5 high-res bitmaps in cache to prevent OOM
        final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        final int cacheSize = maxMemory / 8; // 1/8th of available memory

        memoryCache = new LruCache<Integer, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(Integer key, Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }
        };

        try {
            if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
                pfd = ParcelFileDescriptor.open(new File(uri.getPath()), ParcelFileDescriptor.MODE_READ_ONLY);
            } else {
                pfd = context.getContentResolver().openFileDescriptor(uri, "r");
            }
        } catch (Exception e) {
            // Fallback: copy to temp file if direct SAF file descriptor fails
            File temp = new File(context.getCacheDir(), "temp_render.pdf");
            try (InputStream in = context.getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
            pfd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY);
        }

        if (pfd != null) {
            renderer = new PdfRenderer(pfd);
            pageCount = renderer.getPageCount();

            if (pageCount > 0) {
                PdfRenderer.Page p0 = renderer.openPage(0);
                pageAspectRatio = (float) p0.getHeight() / (float) p0.getWidth();
                p0.close();
            }
        }
    }

    public int getPageCount() {
        return pageCount;
    }

    public float getPageAspectRatio() {
        return pageAspectRatio;
    }

    private int blurLevel = 25; // 0..100, default 25 (light blur)

    public void setBlurLevel(int level) {
        if (this.blurLevel != level) {
            this.blurLevel = Math.max(0, Math.min(100, level));
            clearCache();
        }
    }

    public int getBlurLevel() {
        return blurLevel;
    }

    public void clearCache() {
        if (memoryCache != null) {
            memoryCache.evictAll();
        }
    }

    public synchronized Bitmap getPageSync(int index, int targetW, int targetH) {
        if (index < 0 || index >= pageCount || renderer == null) return null;

        Bitmap cached = memoryCache.get(index);
        if (cached != null && !cached.isRecycled()) {
            return cached;
        }

        try {
            PdfRenderer.Page page = renderer.openPage(index);

            // Compute exact fitted size preserving original aspect ratio (NO vertical stretching!)
            float pageW = page.getWidth();
            float pageH = page.getHeight();
            float scale = Math.min((float) targetW / pageW, (float) targetH / pageH);
            int drawW = Math.max(10, Math.round(pageW * scale));
            int drawH = Math.max(10, Math.round(pageH * scale));

            // Render PDF page into high-res intermediate bitmap
            Bitmap pageBmp = Bitmap.createBitmap(drawW, drawH, Bitmap.Config.ARGB_8888);
            Canvas pageCanvas = new Canvas(pageBmp);
            pageCanvas.drawColor(Color.WHITE);
            page.render(pageBmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            page.close();

            // Create full-screen bitmap for authentic edge-to-edge reading
            Bitmap fullBmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
            Canvas fullCanvas = new Canvas(fullBmp);

            int left = (targetW - drawW) / 2;
            int top = (targetH - drawH) / 2;

            if (drawW < targetW || drawH < targetH) {
                if (blurLevel <= 0) {
                    // Pure dark background
                    fullCanvas.drawColor(0xFF0A0C10);
                } else {
                    // Center-crop page area matching target screen aspect ratio
                    float srcAspect = (float) drawW / drawH;
                    float targetAspect = (float) targetW / targetH;
                    int cropX = 0, cropY = 0, cropW = drawW, cropH = drawH;
                    if (srcAspect > targetAspect) {
                        cropW = Math.round(drawH * targetAspect);
                        cropX = Math.max(0, (drawW - cropW) / 2);
                    } else {
                        cropH = Math.round(drawW / targetAspect);
                        cropY = Math.max(0, (drawH - cropH) / 2);
                    }
                    cropW = Math.min(cropW, drawW - cropX);
                    cropH = Math.min(cropH, drawH - cropY);

                    // Configure resolution and blur params based on blurLevel (1..100)
                    int thumbW;
                    int radius;
                    int passes = 1;
                    float bright;
                    float satBoost;

                    if (blurLevel <= 15) { // Ultra-light (almost pure artwork bokeh)
                        thumbW = 420;
                        radius = 1;
                        bright = 0.86f;
                        satBoost = 1.02f;
                    } else if (blurLevel <= 35) { // Light blur (user favorite default!)
                        thumbW = 360;
                        radius = 2;
                        bright = 0.84f;
                        satBoost = 1.05f;
                    } else if (blurLevel <= 65) { // Medium blur
                        thumbW = 300;
                        radius = 4;
                        bright = 0.82f;
                        satBoost = 1.10f;
                    } else if (blurLevel <= 85) { // Cinematic blur
                        thumbW = 220;
                        radius = 7;
                        bright = 0.80f;
                        satBoost = 1.15f;
                    } else { // Heavy/abstract blur
                        thumbW = 140;
                        radius = 9;
                        passes = 2;
                        bright = 0.78f;
                        satBoost = 1.25f;
                    }

                    int thumbH = Math.max(10, Math.round((float) thumbW * targetH / targetW));

                    Bitmap croppedSrc = Bitmap.createBitmap(pageBmp, cropX, cropY, cropW, cropH);
                    Bitmap thumb = Bitmap.createScaledBitmap(croppedSrc, thumbW, thumbH, true);
                    if (croppedSrc != thumb && !croppedSrc.isRecycled()) {
                        croppedSrc.recycle();
                    }

                    Bitmap blurredThumb = blurAndDimThumbnail(thumb, radius, passes, bright, satBoost);
                    if (thumb != blurredThumb && !thumb.isRecycled()) {
                        thumb.recycle();
                    }

                    if (blurredThumb != null) {
                        Rect srcRect = new Rect(0, 0, blurredThumb.getWidth(), blurredThumb.getHeight());
                        Rect dstRect = new Rect(0, 0, targetW, targetH);
                        Paint bgPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
                        fullCanvas.drawBitmap(blurredThumb, srcRect, dstRect, bgPaint);
                        blurredThumb.recycle();
                    }
                }

                // Feather pageBmp borders (44px) so they dissolve seamlessly into backdrop
                int feather = 44;
                Paint fadePaint = new Paint();
                fadePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
                Canvas pageC = new Canvas(pageBmp);

                if (top > 0) {
                    fadePaint.setShader(new LinearGradient(
                            0, 0, 0, feather,
                            0xFF000000, 0x00000000, Shader.TileMode.CLAMP));
                    pageC.drawRect(0, 0, drawW, feather, fadePaint);

                    fadePaint.setShader(new LinearGradient(
                            0, drawH - feather, 0, drawH,
                            0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
                    pageC.drawRect(0, drawH - feather, drawW, drawH, fadePaint);
                }

                if (left > 0) {
                    fadePaint.setShader(new LinearGradient(
                            0, 0, feather, 0,
                            0xFF000000, 0x00000000, Shader.TileMode.CLAMP));
                    pageC.drawRect(0, 0, feather, drawH, fadePaint);

                    fadePaint.setShader(new LinearGradient(
                            drawW - feather, 0, drawW, 0,
                            0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
                    pageC.drawRect(drawW - feather, 0, drawW, drawH, fadePaint);
                }
            }

            fullCanvas.drawBitmap(pageBmp, left, top, null);
            pageBmp.recycle();

            memoryCache.put(index, fullBmp);
            return fullBmp;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static Bitmap blurAndDimThumbnail(Bitmap src, int radius, int passes, float bright, float satBoost) {
        if (src == null || src.isRecycled()) return null;
        try {
            int w = src.getWidth();
            int h = src.getHeight();
            int[] pix = new int[w * h];
            src.getPixels(pix, 0, w, 0, 0, w, h);

            float[] hsv = new float[3];
            for (int i = 0; i < pix.length; i++) {
                int p = pix[i];
                Color.colorToHSV(p, hsv);
                hsv[1] = Math.min(1.0f, hsv[1] * satBoost);
                hsv[2] = hsv[2] * bright;
                pix[i] = Color.HSVToColor(255, hsv);
            }

            for (int p = 0; p < passes; p++) {
                fastBoxBlur(pix, w, h, radius);
            }

            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            out.setPixels(pix, 0, w, 0, 0, w, h);
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static void fastBoxBlur(int[] pix, int w, int h, int radius) {
        if (w <= 0 || h <= 0 || radius <= 0) return;
        int[] temp = new int[pix.length];
        int div = 2 * radius + 1;

        // Horizontal pass
        for (int y = 0; y < h; y++) {
            int yOffset = y * w;
            int rSum = 0, gSum = 0, bSum = 0;

            for (int i = -radius; i <= radius; i++) {
                int p = pix[yOffset + Math.min(Math.max(i, 0), w - 1)];
                rSum += (p >> 16) & 0xFF;
                gSum += (p >> 8) & 0xFF;
                bSum += p & 0xFF;
            }

            for (int x = 0; x < w; x++) {
                temp[yOffset + x] = 0xFF000000 | ((rSum / div) << 16) | ((gSum / div) << 8) | (bSum / div);
                int leftP = pix[yOffset + Math.max(0, x - radius)];
                int rightP = pix[yOffset + Math.min(w - 1, x + radius + 1)];
                rSum += ((rightP >> 16) & 0xFF) - ((leftP >> 16) & 0xFF);
                gSum += ((rightP >> 8) & 0xFF) - ((leftP >> 8) & 0xFF);
                bSum += (rightP & 0xFF) - (leftP & 0xFF);
            }
        }

        // Vertical pass
        for (int x = 0; x < w; x++) {
            int rSum = 0, gSum = 0, bSum = 0;

            for (int i = -radius; i <= radius; i++) {
                int p = temp[Math.min(Math.max(i, 0), h - 1) * w + x];
                rSum += (p >> 16) & 0xFF;
                gSum += (p >> 8) & 0xFF;
                bSum += p & 0xFF;
            }

            for (int y = 0; y < h; y++) {
                pix[y * w + x] = 0xFF000000 | ((rSum / div) << 16) | ((gSum / div) << 8) | (bSum / div);
                int topP = temp[Math.max(0, y - radius) * w + x];
                int bottomP = temp[Math.min(h - 1, y + radius + 1) * w + x];
                rSum += ((bottomP >> 16) & 0xFF) - ((topP >> 16) & 0xFF);
                gSum += ((bottomP >> 8) & 0xFF) - ((topP >> 8) & 0xFF);
                bSum += (bottomP & 0xFF) - (topP & 0xFF);
            }
        }
    }

    public void getPageAsync(int index, int targetW, int targetH, PageCallback callback) {
        Bitmap cached = memoryCache.get(index);
        if (cached != null && !cached.isRecycled()) {
            callback.onPageRendered(index, cached);
            return;
        }

        renderExecutor.execute(() -> {
            Bitmap bmp = getPageSync(index, targetW, targetH);
            if (bmp != null) {
                mainHandler.post(() -> callback.onPageRendered(index, bmp));
            }
        });
    }

    public void close() {
        if (renderExecutor != null) {
            renderExecutor.shutdownNow();
        }
        if (memoryCache != null) {
            memoryCache.evictAll();
        }
        if (renderer != null) {
            try { renderer.close(); } catch (Exception ignored) {}
            renderer = null;
        }
        if (pfd != null) {
            try { pfd.close(); } catch (Exception ignored) {}
            pfd = null;
        }
    }
}
