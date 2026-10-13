package com.holki.auraleaf;

import android.graphics.Bitmap;

public interface ComicDocument {
    int getPageCount();
    Bitmap renderPage(int index, int maxW, int maxH);
    float getPageAspectRatio(int index);
    void close();
}
