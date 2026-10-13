package com.holki.auraleaf;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import com.github.junrar.Archive;
import com.github.junrar.rarfile.FileHeader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CbrDocument implements ComicDocument {
    private final File rarFile;
    private Archive archive;
    private final List<FileHeader> imageHeaders = new ArrayList<>();

    public CbrDocument(File file) throws Exception {
        this.rarFile = file;
        this.archive = new Archive(file);

        List<FileHeader> headers = archive.getFileHeaders();
        for (FileHeader fh : headers) {
            if (fh.isDirectory()) continue;
            String name = fh.getFileName();
            String lower = name.toLowerCase();
            if (lower.contains("__macosx") || lower.startsWith(".") || lower.contains("/.")) continue;

            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".bmp")) {
                imageHeaders.add(fh);
            }
        }

        NaturalOrderComparator comparator = new NaturalOrderComparator();
        Collections.sort(imageHeaders, (h1, h2) -> comparator.compare(h1.getFileName(), h2.getFileName()));
    }

    @Override
    public int getPageCount() {
        return imageHeaders.size();
    }

    @Override
    public synchronized Bitmap renderPage(int index, int maxW, int maxH) {
        if (index < 0 || index >= imageHeaders.size() || archive == null) return null;
        try {
            FileHeader fh = imageHeaders.get(index);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            archive.extractFile(fh, baos);
            byte[] bytes = baos.toByteArray();
            if (bytes.length == 0) return null;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);

            int srcW = opts.outWidth;
            int srcH = opts.outHeight;
            if (srcW <= 0 || srcH <= 0) return null;

            int sampleSize = 1;
            while ((srcW / sampleSize) > (maxW * 1.5f) && (srcH / sampleSize) > (maxH * 1.5f)) {
                sampleSize *= 2;
            }

            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sampleSize;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;

            Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
            if (decoded == null) return null;

            float scale = Math.min((float) maxW / decoded.getWidth(), (float) maxH / decoded.getHeight());
            int drawW = Math.max(10, Math.round(decoded.getWidth() * scale));
            int drawH = Math.max(10, Math.round(decoded.getHeight() * scale));

            if (drawW != decoded.getWidth() || drawH != decoded.getHeight()) {
                Bitmap scaled = Bitmap.createScaledBitmap(decoded, drawW, drawH, true);
                if (scaled != decoded && !decoded.isRecycled()) {
                    decoded.recycle();
                }
                return scaled;
            }
            return decoded;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public synchronized float getPageAspectRatio(int index) {
        if (index < 0 || index >= imageHeaders.size() || archive == null) return 1.414f;
        try {
            FileHeader fh = imageHeaders.get(index);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            archive.extractFile(fh, baos);
            byte[] bytes = baos.toByteArray();
            if (bytes.length == 0) return 1.414f;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
            if (opts.outWidth > 0 && opts.outHeight > 0) {
                return (float) opts.outHeight / opts.outWidth;
            }
            return 1.414f;
        } catch (Exception e) {
            return 1.414f;
        }
    }

    @Override
    public synchronized void close() {
        if (archive != null) {
            try { archive.close(); } catch (Exception ignored) {}
            archive = null;
        }
    }
}
