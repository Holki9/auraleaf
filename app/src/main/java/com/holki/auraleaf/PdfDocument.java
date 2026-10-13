package com.holki.auraleaf;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

public class PdfDocument implements ComicDocument {
    private ParcelFileDescriptor pfd;
    private PdfRenderer renderer;
    private int pageCount = 0;

    public PdfDocument(ParcelFileDescriptor pfd) throws Exception {
        this.pfd = pfd;
        this.renderer = new PdfRenderer(pfd);
        this.pageCount = renderer.getPageCount();
    }

    @Override
    public int getPageCount() {
        return pageCount;
    }

    @Override
    public synchronized Bitmap renderPage(int index, int maxW, int maxH) {
        if (index < 0 || index >= pageCount || renderer == null) return null;
        try {
            PdfRenderer.Page page = renderer.openPage(index);
            float pageW = page.getWidth();
            float pageH = page.getHeight();
            float scale = Math.min((float) maxW / pageW, (float) maxH / pageH);
            int drawW = Math.max(10, Math.round(pageW * scale));
            int drawH = Math.max(10, Math.round(pageH * scale));

            Bitmap bmp = Bitmap.createBitmap(drawW, drawH, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            canvas.drawColor(Color.WHITE);
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            page.close();
            return bmp;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public synchronized float getPageAspectRatio(int index) {
        if (index < 0 || index >= pageCount || renderer == null) return 1.414f;
        try {
            PdfRenderer.Page page = renderer.openPage(index);
            float ratio = (float) page.getHeight() / Math.max(1f, page.getWidth());
            page.close();
            return ratio;
        } catch (Exception e) {
            return 1.414f;
        }
    }

    @Override
    public synchronized void close() {
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
