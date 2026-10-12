package com.holki.auraleaf;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
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

            // Create target bitmap with transparent background for edge-to-edge ambient bleed
            Bitmap fullBmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
            Canvas fullCanvas = new Canvas(fullBmp);

            int left = (targetW - drawW) / 2;
            int top = (targetH - drawH) / 2;
            fullCanvas.drawBitmap(pageBmp, left, top, null);
            pageBmp.recycle();

            // Soft feathered fade on the comic boundaries to melt into ambient glow
            int feather = 32; // ~8mm feather gradient
            Paint fadePaint = new Paint();
            fadePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));

            // Feather top edge if there is top margin
            if (top > 0) {
                fadePaint.setShader(new LinearGradient(
                        0, top, 0, top + feather,
                        0xFF000000, 0x00000000, Shader.TileMode.CLAMP));
                fullCanvas.drawRect(left, top, left + drawW, top + feather, fadePaint);
            }
            // Feather bottom edge if there is bottom margin
            if (top + drawH < targetH) {
                fadePaint.setShader(new LinearGradient(
                        0, top + drawH - feather, 0, top + drawH,
                        0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
                fullCanvas.drawRect(left, top + drawH - feather, left + drawW, top + drawH, fadePaint);
            }
            // Feather left edge if there is left margin
            if (left > 0) {
                fadePaint.setShader(new LinearGradient(
                        left, 0, left + feather, 0,
                        0xFF000000, 0x00000000, Shader.TileMode.CLAMP));
                fullCanvas.drawRect(left, top, left + feather, top + drawH, fadePaint);
            }
            // Feather right edge if there is right margin
            if (left + drawW < targetW) {
                fadePaint.setShader(new LinearGradient(
                        left + drawW - feather, 0, left + drawW, 0,
                        0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
                fullCanvas.drawRect(left + drawW - feather, top, left + drawW, top + drawH, fadePaint);
            }

            memoryCache.put(index, fullBmp);
            return fullBmp;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
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
