package com.holki.auraleaf;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class CbzDocument implements ComicDocument {
    private final ZipFile zipFile;
    private final List<String> imageEntryNames = new ArrayList<>();

    public CbzDocument(File file) throws Exception {
        this.zipFile = new ZipFile(file);
        Enumeration<? extends ZipEntry> entries = zipFile.entries();

        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) continue;
            String name = entry.getName();
            String lower = name.toLowerCase();

            // Ignore macOS metadata & hidden files
            if (lower.contains("__macosx") || lower.startsWith(".") || lower.contains("/.")) continue;

            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".bmp")) {
                imageEntryNames.add(name);
            }
        }

        // Sort images in natural comic page order
        Collections.sort(imageEntryNames, new NaturalOrderComparator());
    }

    @Override
    public int getPageCount() {
        return imageEntryNames.size();
    }

    @Override
    public synchronized Bitmap renderPage(int index, int maxW, int maxH) {
        if (index < 0 || index >= imageEntryNames.size() || zipFile == null) return null;
        try {
            String entryName = imageEntryNames.get(index);
            ZipEntry entry = zipFile.getEntry(entryName);
            if (entry == null) return null;

            // 1. Decode bounds
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            try (InputStream is = zipFile.getInputStream(entry)) {
                BitmapFactory.decodeStream(is, null, opts);
            }

            int srcW = opts.outWidth;
            int srcH = opts.outHeight;
            if (srcW <= 0 || srcH <= 0) return null;

            // 2. Compute inSampleSize for efficient RAM usage
            int sampleSize = 1;
            while ((srcW / sampleSize) > (maxW * 1.5f) && (srcH / sampleSize) > (maxH * 1.5f)) {
                sampleSize *= 2;
            }

            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sampleSize;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;

            Bitmap decoded;
            try (InputStream is = zipFile.getInputStream(entry)) {
                decoded = BitmapFactory.decodeStream(is, null, opts);
            }
            if (decoded == null) return null;

            // 3. Scale preserving 100% original aspect ratio
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
        if (index < 0 || index >= imageEntryNames.size() || zipFile == null) return 1.414f;
        try {
            String entryName = imageEntryNames.get(index);
            ZipEntry entry = zipFile.getEntry(entryName);
            if (entry == null) return 1.414f;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            try (InputStream is = zipFile.getInputStream(entry)) {
                BitmapFactory.decodeStream(is, null, opts);
            }
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
        if (zipFile != null) {
            try { zipFile.close(); } catch (Exception ignored) {}
        }
    }
}
