package com.holki.auraleaf;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import com.github.junrar.Archive;
import com.github.junrar.rarfile.FileHeader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class CoverExtractor {

    public static class ComicInfo {
        public final int totalPages;
        public final String coverPath;

        public ComicInfo(int totalPages, String coverPath) {
            this.totalPages = totalPages;
            this.coverPath = coverPath;
        }
    }

    public static ComicInfo extract(Context context, Uri uri, String fileName) throws Exception {
        String lower = (fileName != null ? fileName : "").toLowerCase();
        File coversDir = new File(context.getFilesDir(), "covers");
        coversDir.mkdirs();
        File coverFile = new File(coversDir, "cover_" + System.currentTimeMillis() + ".jpg");

        if (lower.endsWith(".cbz") || lower.endsWith(".zip")) {
            File tempFile = getFileFromUri(context, uri, "temp_cover.cbz");
            try (ZipFile zip = new ZipFile(tempFile)) {
                List<String> names = new ArrayList<>();
                Enumeration<? extends ZipEntry> en = zip.entries();
                while (en.hasMoreElements()) {
                    ZipEntry ze = en.nextElement();
                    if (ze.isDirectory()) continue;
                    String zn = ze.getName();
                    String zl = zn.toLowerCase();
                    if (zl.contains("__macosx") || zl.startsWith(".") || zl.contains("/.")) continue;
                    if (zl.endsWith(".jpg") || zl.endsWith(".jpeg") || zl.endsWith(".png") || zl.endsWith(".webp") || zl.endsWith(".bmp")) {
                        names.add(zn);
                    }
                }
                Collections.sort(names, new NaturalOrderComparator());
                if (!names.isEmpty()) {
                    ZipEntry first = zip.getEntry(names.get(0));
                    try (InputStream is = zip.getInputStream(first);
                         FileOutputStream fos = new FileOutputStream(coverFile)) {
                        Bitmap bmp = android.graphics.BitmapFactory.decodeStream(is);
                        if (bmp != null) {
                            int tw = 320;
                            int th = Math.max(10, Math.round(tw * ((float) bmp.getHeight() / bmp.getWidth())));
                            Bitmap scaled = Bitmap.createScaledBitmap(bmp, tw, th, true);
                            scaled.compress(Bitmap.CompressFormat.JPEG, 85, fos);
                            if (scaled != bmp) scaled.recycle();
                            bmp.recycle();
                        }
                    }
                }
                return new ComicInfo(names.size(), coverFile.getAbsolutePath());
            }
        } else if (lower.endsWith(".cbr") || lower.endsWith(".rar")) {
            File tempFile = getFileFromUri(context, uri, "temp_cover.cbr");
            try (Archive arch = new Archive(tempFile)) {
                List<FileHeader> headers = new ArrayList<>();
                for (FileHeader fh : arch.getFileHeaders()) {
                    if (fh.isDirectory()) continue;
                    String fn = fh.getFileName().toLowerCase();
                    if (fn.contains("__macosx") || fn.startsWith(".") || fn.contains("/.")) continue;
                    if (fn.endsWith(".jpg") || fn.endsWith(".jpeg") || fn.endsWith(".png") || fn.endsWith(".webp") || fn.endsWith(".bmp")) {
                        headers.add(fh);
                    }
                }
                NaturalOrderComparator comp = new NaturalOrderComparator();
                Collections.sort(headers, (h1, h2) -> comp.compare(h1.getFileName(), h2.getFileName()));
                if (!headers.isEmpty()) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    arch.extractFile(headers.get(0), baos);
                    byte[] bytes = baos.toByteArray();
                    Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (bmp != null) {
                        int tw = 320;
                        int th = Math.max(10, Math.round(tw * ((float) bmp.getHeight() / bmp.getWidth())));
                        Bitmap scaled = Bitmap.createScaledBitmap(bmp, tw, th, true);
                        try (FileOutputStream fos = new FileOutputStream(coverFile)) {
                            scaled.compress(Bitmap.CompressFormat.JPEG, 85, fos);
                        }
                        if (scaled != bmp) scaled.recycle();
                        bmp.recycle();
                    }
                }
                return new ComicInfo(headers.size(), coverFile.getAbsolutePath());
            }
        } else {
            // Default: PDF
            ParcelFileDescriptor pfd = null;
            try {
                if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
                    pfd = ParcelFileDescriptor.open(new File(uri.getPath()), ParcelFileDescriptor.MODE_READ_ONLY);
                } else {
                    pfd = context.getContentResolver().openFileDescriptor(uri, "r");
                }
            } catch (Exception e) {
                File temp = new File(context.getCacheDir(), "import_temp.pdf");
                try (InputStream in = context.getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(temp)) {
                    byte[] buffer = new byte[8192];
                    int r;
                    while ((r = in.read(buffer)) != -1) out.write(buffer, 0, r);
                }
                pfd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY);
            }

            if (pfd != null) {
                PdfRenderer renderer = new PdfRenderer(pfd);
                int total = renderer.getPageCount();
                if (total > 0) {
                    PdfRenderer.Page page0 = renderer.openPage(0);
                    int tw = 320;
                    int th = Math.max(10, (int) (tw * ((float) page0.getHeight() / page0.getWidth())));
                    Bitmap coverBmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(coverBmp);
                    canvas.drawColor(Color.WHITE);
                    page0.render(coverBmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    page0.close();

                    try (FileOutputStream fos = new FileOutputStream(coverFile)) {
                        coverBmp.compress(Bitmap.CompressFormat.JPEG, 85, fos);
                    }
                    coverBmp.recycle();
                }
                renderer.close();
                pfd.close();
                return new ComicInfo(total, coverFile.getAbsolutePath());
            }
            return new ComicInfo(0, coverFile.getAbsolutePath());
        }
    }

    public static File getFileFromUri(Context context, Uri uri, String fallbackName) throws Exception {
        if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
            File f = new File(uri.getPath());
            if (f.exists()) return f;
        }
        File temp = new File(context.getCacheDir(), fallbackName);
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(temp)) {
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
            }
        }
        return temp;
    }
}
