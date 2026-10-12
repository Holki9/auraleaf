package com.holki.auraleaf;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
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
            pfd = context.getContentResolver().openFileDescriptor(uri, "r");
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

            // Maintain exact aspect ratio
            int w = targetW;
            int h = (int) (w * ((float) page.getHeight() / page.getWidth()));
            if (h > targetH) {
                h = targetH;
                w = (int) (h * ((float) page.getWidth() / page.getHeight()));
            }
            w = Math.max(10, w);
            h = Math.max(10, h);

            Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.WHITE); // Solid white backdrop for paper
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            page.close();

            memoryCache.put(index, bitmap);
            return bitmap;
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
