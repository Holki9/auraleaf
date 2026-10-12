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
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

public class PageCurlView extends View {

    public interface OnPageListener {
        void onPageChanged(int newPageIndex, Bitmap currentBitmap);
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

    // Touch & Drag state
    private float downX = 0f;
    private float downY = 0f;
    private float currentTouchX = 0f;
    private float currentTouchY = 0f;
    private boolean isDragging = false;
    private boolean isCurling = false;
    private boolean isFlipForward = true;
    private final float touchSlop = 28f; // px

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
    private Paint highlightPaint;
    private Paint innerShadowPaint;
    private Path clipPath;
    private Path flapPath;

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
        highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        innerShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        clipPath = new Path();
        flapPath = new Path();

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

        if (pageListener != null) {
            pageListener.onPageChanged(currentPageIndex, currentBitmap);
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

        // When zoomed in: touch is PAN only (no curl)
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

        float w = getWidth();
        float h = getHeight();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (curlAnimator != null && curlAnimator.isRunning()) {
                    curlAnimator.cancel();
                }
                downX = event.getX();
                downY = event.getY();
                currentTouchX = downX;
                currentTouchY = downY;
                isDragging = false;
                isCurling = false;
                return true;

            case MotionEvent.ACTION_MOVE:
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;

                // Check horizontal drag intent
                if (!isDragging) {
                    // Must be horizontal gesture (> slop and more horizontal than vertical)
                    if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.25f) {
                        isFlipForward = dx < 0; // swiping left = next page; swiping right = prev page

                        if (isFlipForward && currentPageIndex >= totalPages - 1) {
                            return true; // No next page
                        }
                        if (!isFlipForward && currentPageIndex <= 0) {
                            return true; // No prev page
                        }

                        isDragging = true;
                        isCurling = true;
                    }
                }

                if (isCurling) {
                    currentTouchX = event.getX();
                    currentTouchY = event.getY();
                    invalidate();
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!isDragging || !isCurling) {
                    // IT WAS A TAP, NOT A DRAG!
                    // Do NOT flip page on tap!
                    isDragging = false;
                    isCurling = false;
                    return true;
                }

                finishCurlGesture();
                return true;
        }

        return super.onTouchEvent(event);
    }

    private void finishCurlGesture() {
        float w = getWidth();
        float totalDrag = isFlipForward ? (downX - currentTouchX) : (currentTouchX - downX);
        boolean shouldFlip = totalDrag > (w * 0.20f);

        float startFoldX = currentTouchX;
        float endFoldX;

        if (shouldFlip) {
            endFoldX = isFlipForward ? -w * 0.2f : w * 1.2f;
        } else {
            endFoldX = isFlipForward ? w : 0f;
        }

        curlAnimator = ValueAnimator.ofFloat(0f, 1f);
        curlAnimator.setDuration(240);
        curlAnimator.setInterpolator(new DecelerateInterpolator());

        curlAnimator.addUpdateListener(animation -> {
            float frac = animation.getAnimatedFraction();
            currentTouchX = startFoldX + (endFoldX - startFoldX) * frac;
            invalidate();
        });

        curlAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                isCurling = false;
                isDragging = false;

                if (shouldFlip) {
                    if (isFlipForward) {
                        currentPageIndex++;
                    } else {
                        currentPageIndex--;
                    }
                    if (soundHelper != null) soundHelper.playFlip();
                    loadPageBitmaps();
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
            isCurling = true;
            isDragging = true;
            startAnimatedFlip(true);
        }
    }

    public void flipPrev() {
        if (currentPageIndex > 0 && !isCurling) {
            isFlipForward = false;
            isCurling = true;
            isDragging = true;
            startAnimatedFlip(false);
        }
    }

    public void flipToPage(int targetPage) {
        if (targetPage >= 0 && targetPage < totalPages && targetPage != currentPageIndex) {
            currentPageIndex = targetPage;
            loadPageBitmaps();
            if (soundHelper != null) soundHelper.playFlip();
        }
    }

    private void startAnimatedFlip(boolean forward) {
        float w = getWidth();
        float h = getHeight();

        currentTouchY = h / 2f;
        downY = h / 2f;

        float startX = forward ? w : 0f;
        float endX = forward ? -w * 0.2f : w * 1.2f;

        curlAnimator = ValueAnimator.ofFloat(0f, 1f);
        curlAnimator.setDuration(280);
        curlAnimator.setInterpolator(new DecelerateInterpolator());

        curlAnimator.addUpdateListener(animation -> {
            float frac = animation.getAnimatedFraction();
            currentTouchX = startX + (endX - startX) * frac;
            invalidate();
        });

        curlAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                isCurling = false;
                isDragging = false;
                if (forward) currentPageIndex++; else currentPageIndex--;
                if (soundHelper != null) soundHelper.playFlip();
                loadPageBitmaps();
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

        if (isZoomed) {
            canvas.translate(panX, panY);
            canvas.scale(zoomScale, zoomScale, w / 2f, h / 2f);
        }

        Bitmap topPage = isFlipForward ? currentBitmap : prevBitmap;
        Bitmap bottomPage = isFlipForward ? nextBitmap : currentBitmap;

        // Static Page View
        if (!isCurling || topPage == null) {
            if (currentBitmap != null && !currentBitmap.isRecycled()) {
                drawPageCentered(canvas, currentBitmap, w, h);
            }
            canvas.restore();
            return;
        }

        // =========================================================
        // Dynamic 3D Cylindrical Page Curl Physics
        // =========================================================
        float foldX = Math.max(0f, Math.min(w, currentTouchX));

        // Tilt angle based slightly on vertical drag offset, clamped to prevent upside-down distortion
        float dy = currentTouchY - downY;
        float tiltAngleDeg = Math.max(-18f, Math.min(18f, (dy / h) * 35f));

        // 1. Draw bottom page revealed underneath
        if (bottomPage != null && !bottomPage.isRecycled()) {
            drawPageCentered(canvas, bottomPage, w, h);
        }

        // 2. Draw deep 3D drop shadow onto revealed bottom page along fold line
        float shadowWidth = 65f;
        shadowPaint.setShader(new LinearGradient(
                foldX, 0,
                foldX + (isFlipForward ? shadowWidth : -shadowWidth), 0,
                new int[]{Color.argb(160, 0, 0, 0), Color.argb(60, 0, 0, 0), Color.TRANSPARENT},
                new float[]{0.0f, 0.4f, 1.0f},
                Shader.TileMode.CLAMP
        ));
        canvas.drawRect(
                isFlipForward ? foldX : foldX - shadowWidth, 0,
                isFlipForward ? foldX + shadowWidth : foldX, h,
                shadowPaint
        );

        // 3. Draw remaining uncurled portion of top page
        canvas.save();
        clipPath.reset();
        if (isFlipForward) {
            clipPath.moveTo(0, 0);
            clipPath.lineTo(foldX, 0);
            clipPath.lineTo(foldX, h);
            clipPath.lineTo(0, h);
        } else {
            clipPath.moveTo(foldX, 0);
            clipPath.lineTo(w, 0);
            clipPath.lineTo(w, h);
            clipPath.lineTo(foldX, h);
        }
        clipPath.close();
        canvas.clipPath(clipPath);

        drawPageCentered(canvas, topPage, w, h);

        // 4. Inner crease shadow on top page leading into the fold
        float creaseWidth = 45f;
        innerShadowPaint.setShader(new LinearGradient(
                isFlipForward ? foldX - creaseWidth : foldX + creaseWidth, 0,
                foldX, 0,
                new int[]{Color.TRANSPARENT, Color.argb(90, 0, 0, 0)},
                new float[]{0f, 1.0f},
                Shader.TileMode.CLAMP
        ));
        canvas.drawRect(
                isFlipForward ? foldX - creaseWidth : foldX, 0,
                isFlipForward ? foldX : foldX + creaseWidth, h,
                innerShadowPaint
        );
        canvas.restore();

        // 5. Draw the curled flap (bent curl edge with specular highlight)
        float curlFlapWidth = Math.min(100f, Math.abs(w - foldX) * 0.45f);
        if (curlFlapWidth > 4f) {
            canvas.save();
            flapPath.reset();

            float flapLeft = isFlipForward ? foldX : foldX - curlFlapWidth;
            float flapRight = isFlipForward ? foldX + curlFlapWidth : foldX;

            flapPath.moveTo(flapLeft, 0);
            flapPath.lineTo(flapRight, 0);
            flapPath.lineTo(flapRight, h);
            flapPath.lineTo(flapLeft, h);
            flapPath.close();

            canvas.clipPath(flapPath);

            // Specular paper highlight along crest of the curl
            highlightPaint.setShader(new LinearGradient(
                    flapLeft, 0, flapRight, 0,
                    new int[]{Color.argb(80, 255, 255, 255), Color.argb(140, 255, 255, 255), Color.TRANSPARENT},
                    new float[]{0.0f, 0.45f, 1.0f},
                    Shader.TileMode.CLAMP
            ));
            canvas.drawRect(flapLeft, 0, flapRight, h, highlightPaint);

            canvas.restore();
        }

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
