package com.holki.auraleaf;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

public class PageCurlView extends View {

    public interface OnPageListener {
        void onPageChanged(int newPageIndex);
        void onSingleTap();
        void onDoubleTap();
    }

    private PdfManager pdfManager;
    private SoundHelper soundHelper;
    private OnPageListener pageListener;

    private int currentPageIndex = 0;
    private int totalPages = 0;

    // Bitmaps
    private Bitmap currentBitmap;
    private Bitmap nextBitmap;
    private Bitmap prevBitmap;

    // Curl touch & geometry
    private boolean isCurling = false;
    private boolean isFlipForward = true;
    private PointF touchPos = new PointF();
    private PointF cornerPos = new PointF();
    private ValueAnimator curlAnimator;

    // Pinch-to-zoom & Pan
    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;
    private float zoomScale = 1.0f;
    private float panX = 0f;
    private float panY = 0f;
    private boolean isZoomed = false;
    private float lastPanTouchX = 0f;
    private float lastPanTouchY = 0f;

    // Drawing paints & paths
    private Paint bitmapPaint;
    private Paint shadowPaint;
    private Paint curlHighlightPaint;
    private Paint backPagePaint;
    private Path clipPath;
    private Path flapPath;
    private Matrix reflectionMatrix;

    public PageCurlView(Context context) {
        super(context);
        init(context);
    }

    public PageCurlView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        curlHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        backPagePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        backPagePaint.setColor(Color.WHITE);

        clipPath = new Path();
        flapPath = new Path();
        reflectionMatrix = new Matrix();

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float factor = detector.getScaleFactor();
                zoomScale = Math.max(1.0f, Math.min(zoomScale * factor, 4.5f));
                isZoomed = zoomScale > 1.05f;
                clampPan();
                invalidate();
                return true;
            }
        });

        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (pageListener != null) pageListener.onSingleTap();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (isZoomed) {
                    resetZoom();
                } else {
                    zoomScale = 2.0f;
                    isZoomed = true;
                    panX = (getWidth() / 2f - e.getX()) * 0.7f;
                    panY = (getHeight() / 2f - e.getY()) * 0.7f;
                    clampPan();
                    invalidate();
                }
                if (pageListener != null) pageListener.onDoubleTap();
                return true;
            }
        });
    }

    public void setup(PdfManager pdfManager, SoundHelper soundHelper, int startPage, OnPageListener listener) {
        this.pdfManager = pdfManager;
        this.soundHelper = soundHelper;
        this.currentPageIndex = startPage;
        this.totalPages = pdfManager.getPageCount();
        this.pageListener = listener;

        post(this::loadPageBitmaps);
    }

    private void loadPageBitmaps() {
        if (pdfManager == null || getWidth() <= 0 || getHeight() <= 0) return;

        int w = getWidth();
        int h = getHeight();

        currentBitmap = pdfManager.getPageSync(currentPageIndex, w, h);
        if (currentPageIndex + 1 < totalPages) {
            nextBitmap = pdfManager.getPageSync(currentPageIndex + 1, w, h);
        } else {
            nextBitmap = null;
        }

        if (currentPageIndex - 1 >= 0) {
            prevBitmap = pdfManager.getPageSync(currentPageIndex - 1, w, h);
        } else {
            prevBitmap = null;
        }

        invalidate();
    }

    public void resetZoom() {
        zoomScale = 1.0f;
        panX = 0f;
        panY = 0f;
        isZoomed = false;
        invalidate();
    }

    public boolean isZoomed() {
        return isZoomed;
    }

    public float getZoomScale() {
        return zoomScale;
    }

    private void clampPan() {
        if (!isZoomed) {
            panX = 0f;
            panY = 0f;
            return;
        }
        float maxPanX = (getWidth() * (zoomScale - 1f)) / 2f;
        float maxPanY = (getHeight() * (zoomScale - 1f)) / 2f;
        panX = Math.max(-maxPanX, Math.min(panX, maxPanX));
        panY = Math.max(-maxPanY, Math.min(panY, maxPanY));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);

        if (scaleDetector.isInProgress()) {
            return true;
        }

        // When zoomed in, touches perform 2D panning, not page curls
        if (isZoomed) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastPanTouchX = event.getX();
                    lastPanTouchY = event.getY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getX() - lastPanTouchX;
                    float dy = event.getY() - lastPanTouchY;
                    panX += dx;
                    panY += dy;
                    clampPan();
                    lastPanTouchX = event.getX();
                    lastPanTouchY = event.getY();
                    invalidate();
                    break;
            }
            return true;
        }

        // Page curl handling
        float w = getWidth();
        float h = getHeight();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (curlAnimator != null && curlAnimator.isRunning()) {
                    curlAnimator.cancel();
                }

                float startX = event.getX();
                float startY = event.getY();

                // Determine curl direction: right side -> forward, left side -> backward
                isFlipForward = startX > w * 0.45f;

                if (isFlipForward && currentPageIndex >= totalPages - 1) {
                    return true; // No more forward pages
                }
                if (!isFlipForward && currentPageIndex <= 0) {
                    return true; // No more previous pages
                }

                isCurling = true;
                touchPos.set(startX, startY);

                // Set origin corner
                float cornerY = (startY > h / 2f) ? h : 0f;
                float cornerX = isFlipForward ? w : 0f;
                cornerPos.set(cornerX, cornerY);

                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (isCurling) {
                    touchPos.set(event.getX(), event.getY());
                    invalidate();
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (isCurling) {
                    finishCurlGesture();
                }
                return true;
        }

        return super.onTouchEvent(event);
    }

    private void finishCurlGesture() {
        float w = getWidth();
        float dist = Math.abs(touchPos.x - cornerPos.x);
        boolean shouldFlip = dist > (w * 0.22f);

        float targetX;
        float targetY = cornerPos.y;

        if (shouldFlip) {
            targetX = isFlipForward ? -w * 0.3f : w * 1.3f;
        } else {
            targetX = cornerPos.x;
            targetY = cornerPos.y;
        }

        final float startX = touchPos.x;
        final float startY = touchPos.y;
        final float endX = targetX;
        final float endY = targetY;

        curlAnimator = ValueAnimator.ofFloat(0f, 1f);
        curlAnimator.setDuration(280);
        curlAnimator.setInterpolator(new DecelerateInterpolator());

        curlAnimator.addUpdateListener(animation -> {
            float frac = animation.getAnimatedFraction();
            touchPos.x = startX + (endX - startX) * frac;
            touchPos.y = startY + (endY - startY) * frac;
            invalidate();
        });

        curlAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                isCurling = false;
                if (shouldFlip) {
                    if (isFlipForward) {
                        currentPageIndex++;
                    } else {
                        currentPageIndex--;
                    }
                    if (soundHelper != null) soundHelper.playFlip();
                    loadPageBitmaps();
                    if (pageListener != null) pageListener.onPageChanged(currentPageIndex);
                } else {
                    invalidate();
                }
            }
        });

        curlAnimator.start();
    }

    public void flipNext() {
        if (currentPageIndex < totalPages - 1 && !isCurling) {
            isFlipForward = true;
            cornerPos.set(getWidth(), getHeight());
            touchPos.set(getWidth(), getHeight());
            isCurling = true;

            startProgrammaticFlip(true);
        }
    }

    public void flipPrev() {
        if (currentPageIndex > 0 && !isCurling) {
            isFlipForward = false;
            cornerPos.set(0, getHeight());
            touchPos.set(0, getHeight());
            isCurling = true;

            startProgrammaticFlip(false);
        }
    }

    public void flipToPage(int targetPage) {
        if (targetPage >= 0 && targetPage < totalPages && targetPage != currentPageIndex) {
            currentPageIndex = targetPage;
            loadPageBitmaps();
            if (soundHelper != null) soundHelper.playFlip();
            if (pageListener != null) pageListener.onPageChanged(currentPageIndex);
        }
    }

    private void startProgrammaticFlip(boolean forward) {
        float w = getWidth();
        float h = getHeight();

        curlAnimator = ValueAnimator.ofFloat(0f, 1f);
        curlAnimator.setDuration(320);
        curlAnimator.setInterpolator(new DecelerateInterpolator());

        float startX = forward ? w : 0f;
        float endX = forward ? -w * 0.3f : w * 1.3f;

        curlAnimator.addUpdateListener(animation -> {
            float frac = animation.getAnimatedFraction();
            touchPos.x = startX + (endX - startX) * frac;
            touchPos.y = h * (0.85f - 0.2f * frac);
            invalidate();
        });

        curlAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                isCurling = false;
                if (forward) currentPageIndex++; else currentPageIndex--;
                if (soundHelper != null) soundHelper.playFlip();
                loadPageBitmaps();
                if (pageListener != null) pageListener.onPageChanged(currentPageIndex);
            }
        });

        curlAnimator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        canvas.save();

        // Apply Zoom & Pan
        if (isZoomed) {
            canvas.translate(panX, panY);
            canvas.scale(zoomScale, zoomScale, w / 2f, h / 2f);
        }

        Bitmap topPage = isFlipForward ? currentBitmap : prevBitmap;
        Bitmap bottomPage = isFlipForward ? nextBitmap : currentBitmap;

        if (!isCurling || topPage == null) {
            // Static view: draw current page centered
            if (currentBitmap != null && !currentBitmap.isRecycled()) {
                drawPageCentered(canvas, currentBitmap, w, h);
            }
            canvas.restore();
            return;
        }

        // ==========================================
        // Dynamic Page Curl Geometric Drawing
        // ==========================================
        float px = touchPos.x;
        float py = touchPos.y;
        float cx = cornerPos.x;
        float cy = cornerPos.y;

        // Midpoint on fold line
        float mx = (cx + px) / 2f;
        float my = (cy + py) / 2f;

        // Normal vector to fold line
        float dx = px - cx;
        float dy = py - cy;
        float len = (float) Math.hypot(dx, dy);
        if (len < 1f) {
            drawPageCentered(canvas, currentBitmap, w, h);
            canvas.restore();
            return;
        }
        dx /= len;
        dy /= len;

        // Tangent vector along fold line
        float tx = -dy;
        float ty = dx;

        // 1. Draw bottom page revealed under the curl
        if (bottomPage != null && !bottomPage.isRecycled()) {
            drawPageCentered(canvas, bottomPage, w, h);
        }

        // 2. Compute fold line intersections with page bounds
        clipPath.reset();
        clipPath.moveTo(0, 0);
        clipPath.lineTo(w, 0);
        clipPath.lineTo(w, h);
        clipPath.lineTo(0, h);
        clipPath.close();

        // Compute fold line polygon
        float reach = (float) Math.hypot(w, h) * 1.5f;
        float p1x = mx - tx * reach;
        float p1y = my - ty * reach;
        float p2x = mx + tx * reach;
        float p2y = my + ty * reach;

        // 3. Draw drop shadow along the fold crease onto bottom page
        shadowPaint.setShader(new LinearGradient(
                mx, my,
                mx + dx * 45f, my + dy * 45f,
                Color.argb(120, 0, 0, 0), Color.TRANSPARENT,
                Shader.TileMode.CLAMP
        ));
        canvas.save();
        canvas.drawCircle(mx, my, reach, shadowPaint);
        canvas.restore();

        // 4. Draw remaining uncurled part of top page
        canvas.save();
        Path uncurledPath = new Path();
        uncurledPath.moveTo(p1x, p1y);
        uncurledPath.lineTo(p2x, p2y);
        uncurledPath.lineTo(p2x - dx * reach, p2y - dy * reach);
        uncurledPath.lineTo(p1x - dx * reach, p1y - dy * reach);
        uncurledPath.close();

        canvas.clipPath(uncurledPath);
        drawPageCentered(canvas, topPage, w, h);
        canvas.restore();

        // 5. Draw the curled flap (reflected back of page)
        canvas.save();
        flapPath.reset();
        flapPath.moveTo(p1x, p1y);
        flapPath.lineTo(p2x, p2y);
        flapPath.lineTo(p2x + dx * reach, p2y + dy * reach);
        flapPath.lineTo(p1x + dx * reach, p1y + dy * reach);
        flapPath.close();

        canvas.clipPath(flapPath);

        // Reflection transformation across fold line
        reflectionMatrix.reset();
        reflectionMatrix.postTranslate(-mx, -my);
        float angleRad = (float) Math.atan2(dy, dx);
        float angleDeg = (float) Math.toDegrees(angleRad);
        reflectionMatrix.postRotate(-angleDeg);
        reflectionMatrix.postScale(-1f, 1f);
        reflectionMatrix.postRotate(angleDeg);
        reflectionMatrix.postTranslate(mx, my);

        canvas.concat(reflectionMatrix);
        // Draw back of page with realistic subtle paper shading
        drawPageCentered(canvas, topPage, w, h);
        canvas.drawColor(Color.argb(40, 255, 255, 255)); // Paper highlight

        // Curl highlight gradient along fold crease
        curlHighlightPaint.setShader(new LinearGradient(
                mx, my,
                mx - dx * 35f, my - dy * 35f,
                Color.argb(90, 255, 255, 255), Color.TRANSPARENT,
                Shader.TileMode.CLAMP
        ));
        canvas.drawPaint(curlHighlightPaint);

        canvas.restore();

        canvas.restore();
    }

    private void drawPageCentered(Canvas canvas, Bitmap bitmap, int viewW, int viewH) {
        if (bitmap == null || bitmap.isRecycled()) return;

        float bw = bitmap.getWidth();
        float bh = bitmap.getHeight();

        float scale = Math.min((float) viewW / bw, (float) viewH / bh);
        float dw = bw * scale;
        float dh = bh * scale;

        float left = (viewW - dw) / 2f;
        float top = (viewH - dh) / 2f;

        Matrix m = new Matrix();
        m.postScale(scale, scale);
        m.postTranslate(left, top);

        canvas.drawBitmap(bitmap, m, bitmapPaint);
    }

    public Bitmap getCurrentBitmap() {
        return currentBitmap;
    }

    public int getCurrentPageIndex() {
        return currentPageIndex;
    }

    public int getTotalPages() {
        return totalPages;
    }
}
