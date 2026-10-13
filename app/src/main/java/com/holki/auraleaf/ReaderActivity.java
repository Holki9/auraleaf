package com.holki.auraleaf;

import android.app.Dialog;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.holki.auraleaf.curl.CurlPage;
import com.holki.auraleaf.curl.CurlView;

public class ReaderActivity extends AppCompatActivity {

    public static final String EXTRA_BOOK_ID = "extra_book_id";
    public static final String EXTRA_URI = "extra_uri";
    public static final String EXTRA_LAST_PAGE = "extra_last_page";

    private long bookId;
    private String uriString;
    private int startPage;

    private PdfManager pdfManager;
    private SoundHelper soundHelper;
    private HapticHelper hapticHelper;
    private DatabaseHelper dbHelper;
    private SharedPreferences prefs;

    private CurlView curlView;
    private ImageView imgAmbient;
    private LinearLayout navGlassPill;
    private LinearLayout layoutZoomIndicator;
    private TextView tvZoomLevel;
    private ImageView btnZoomReset;

    private ImageView btnShelf;
    private ImageView btnPrev;
    private ImageView btnNext;
    private TextView tvPageCounter;
    private ImageView btnSound;

    private boolean isAmbientEnabled = true;
    private boolean isNavVisible = true;
    private final java.util.concurrent.ExecutorService ambientExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile int currentAmbientPage = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Edge-to-edge immersive full screen penetrating camera cutout
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        hideSystemUI();

        setContentView(R.layout.activity_reader);

        bookId = getIntent().getLongExtra(EXTRA_BOOK_ID, -1);
        uriString = getIntent().getStringExtra(EXTRA_URI);
        if (uriString == null && getIntent().getData() != null) {
            uriString = getIntent().getData().toString();
        }
        startPage = getIntent().getIntExtra(EXTRA_LAST_PAGE, 0);

        dbHelper = new DatabaseHelper(this);
        soundHelper = new SoundHelper(this);
        hapticHelper = new HapticHelper(this);
        prefs = getSharedPreferences("auraleaf_prefs", MODE_PRIVATE);
        hapticHelper.setEnabled(prefs.getBoolean("haptic_enabled", true));
        isAmbientEnabled = prefs.getBoolean("ambient_enabled", true);

        initViews();
        setupAmbientEffect();
        openPdf();
    }

    private void hideSystemUI() {
        Window window = getWindow();
        window.getDecorView().setBackgroundColor(0xFF0A0C10);
        window.setStatusBarColor(0x00000000);
        window.setNavigationBarColor(0x00000000);

        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(window, window.getDecorView());
        if (controller != null) {
            controller.hide(WindowInsetsCompat.Type.systemBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    private void initViews() {
        curlView = findViewById(R.id.curl_view);
        imgAmbient = findViewById(R.id.img_ambient);
        navGlassPill = findViewById(R.id.nav_glass_pill);
        layoutZoomIndicator = findViewById(R.id.layout_zoom_indicator);
        tvZoomLevel = findViewById(R.id.tv_zoom_level);
        btnZoomReset = findViewById(R.id.btn_zoom_reset);

        btnShelf = findViewById(R.id.btn_shelf);
        btnPrev = findViewById(R.id.btn_prev);
        btnNext = findViewById(R.id.btn_next);
        tvPageCounter = findViewById(R.id.tv_page_counter);
        ImageView btnSearch = findViewById(R.id.btn_search);
        btnSound = findViewById(R.id.btn_sound);

        btnShelf.setOnClickListener(v -> finish());
        btnPrev.setOnClickListener(v -> curlView.flipPrev());
        btnNext.setOnClickListener(v -> curlView.flipNext());
        btnSearch.setOnClickListener(v -> showPageJumpDialog());

        btnZoomReset.setOnClickListener(v -> {
            curlView.resetZoom();
            updateZoomUI();
        });

        btnSound.setOnClickListener(v -> toggleSound());

        tvPageCounter.setOnClickListener(v -> showPageJumpDialog());

        updateSoundUI();
    }

    private void setupAmbientEffect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Authentic GPU hardware blur on Android 12+
            imgAmbient.setRenderEffect(RenderEffect.createBlurEffect(110f, 110f, Shader.TileMode.CLAMP));
        }
    }

    private void openPdf() {
        try {
            Uri uri = Uri.parse(uriString);
            pdfManager = new PdfManager(this, uri);
            int savedBlur = prefs.getInt("blur_level", 25);
            pdfManager.setBlurLevel(savedBlur);

            curlView.setPageProvider(new CurlView.PageProvider() {
                @Override
                public int getPageCount() {
                    return pdfManager != null ? pdfManager.getPageCount() : 0;
                }

                @Override
                public void updatePage(CurlPage page, int width, int height, int index) {
                    if (pdfManager == null || index < 0 || index >= pdfManager.getPageCount()) return;
                    Bitmap bmp = pdfManager.getPageSync(index, width, height);
                    if (bmp != null) {
                        page.setTexture(bmp, CurlPage.SIDE_FRONT);
                        Bitmap backBmp = createPaperBackside(bmp);
                        page.setTexture(backBmp, CurlPage.SIDE_BACK);
                    }
                }
            });

            curlView.setSoundHelper(soundHelper);
            curlView.setHapticHelper(hapticHelper);
            updateViewModeForOrientation(getResources().getConfiguration().orientation);
            curlView.setCurrentIndex(startPage);

            curlView.setOnPageListener(new CurlView.OnPageListener() {
                @Override
                public void onPageChanged(int newPageIndex) {
                    updatePageUI(newPageIndex);
                    dbHelper.updateProgress(bookId, newPageIndex);
                    updateAmbientGlow(newPageIndex);
                }
            });

            curlView.setOnTapListener(new CurlView.OnTapListener() {
                @Override
                public void onSingleTap() {
                    if (isNavVisible) {
                        hideNavigation();
                    }
                    // If already hidden, single tap does nothing per strict user rule
                }

                @Override
                public void onDoubleTap() {
                    showNavigation();
                    curlView.resetZoom();
                    updateZoomUI();
                }
            });

            updatePageUI(startPage);
            updateAmbientGlow(startPage);
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Не удалось открыть PDF: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private Bitmap createPaperBackside(Bitmap front) {
        if (front == null || front.isRecycled()) return null;
        try {
            Bitmap back = Bitmap.createBitmap(front.getWidth(), front.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(back);
            Matrix m = new Matrix();
            m.preScale(-1f, 1f);
            m.postTranslate(front.getWidth(), 0f);

            // Semi-opaque paper base that respects transparent margins
            Paint paperBase = new Paint();
            paperBase.setColorFilter(new PorterDuffColorFilter(0xFFF7F7F7, PorterDuff.Mode.SRC_IN));
            c.drawBitmap(front, m, paperBase);

            // Subtle bleed-through of mirrored content
            Paint bleedPaint = new Paint();
            bleedPaint.setAlpha(32);
            c.drawBitmap(front, m, bleedPaint);
            return back;
        } catch (Exception e) {
            return null;
        }
    }

    private void updatePageUI(int pageIndex) {
        if (pdfManager != null) {
            int total = pdfManager.getPageCount();
            tvPageCounter.setText((pageIndex + 1) + " / " + total);
        }
    }

    private void updateAmbientGlow(int pageIndex) {
        if (pdfManager == null) return;
        currentAmbientPage = pageIndex;
        ambientExecutor.execute(() -> {
            if (pageIndex != currentAmbientPage || pdfManager == null) return;
            Bitmap pageBmp = pdfManager.getPageSync(pageIndex, 400, 600);
            if (pageBmp == null || pageIndex != currentAmbientPage) return;

            Bitmap ambientBmp = createAmbientBackdrop(pageBmp);
            if (ambientBmp == null || pageIndex != currentAmbientPage) return;

            runOnUiThread(() -> {
                if (pageIndex != currentAmbientPage) {
                    ambientBmp.recycle();
                    return;
                }
                imgAmbient.setImageBitmap(ambientBmp);
                imgAmbient.setAlpha(0f);
                imgAmbient.animate().alpha(0.85f).setDuration(350).start();
            });
        });
    }

    private Bitmap createAmbientBackdrop(Bitmap source) {
        if (source == null || source.isRecycled()) return null;
        try {
            int smallW = 100;
            int smallH = 180;
            Bitmap small = Bitmap.createScaledBitmap(source, smallW, smallH, true);
            int[] pixels = new int[smallW * smallH];
            small.getPixels(pixels, 0, smallW, 0, 0, smallW, smallH);

            long sumR = 0, sumG = 0, sumB = 0;
            int chromaticCount = 0;
            for (int p : pixels) {
                int a = (p >> 24) & 0xFF;
                if (a < 50) continue;
                int r = (p >> 16) & 0xFF;
                int g = (p >> 8) & 0xFF;
                int b = p & 0xFF;
                int max = Math.max(r, Math.max(g, b));
                int min = Math.min(r, Math.min(g, b));
                int diff = max - min;
                boolean isWhite = (r > 205 && g > 205 && b > 205);
                boolean isBlack = (r < 25 && g < 25 && b < 25);
                if (!isWhite && !isBlack && diff > 15) {
                    sumR += r;
                    sumG += g;
                    sumB += b;
                    chromaticCount++;
                }
            }

            int avgR = chromaticCount > 0 ? (int)(sumR / chromaticCount) : 45;
            int avgG = chromaticCount > 0 ? (int)(sumG / chromaticCount) : 40;
            int avgB = chromaticCount > 0 ? (int)(sumB / chromaticCount) : 50;

            float[] hsv = new float[3];
            for (int i = 0; i < pixels.length; i++) {
                int p = pixels[i];
                int a = (p >> 24) & 0xFF;
                if (a < 50) {
                    pixels[i] = Color.argb(255, avgR, avgG, avgB);
                    continue;
                }
                int r = (p >> 16) & 0xFF;
                int g = (p >> 8) & 0xFF;
                int b = p & 0xFF;

                if (r > 205 && g > 205 && b > 205) {
                    // Suppress white paper gutter and tint with page artwork colors
                    int nr = Math.min(255, (int)(r * 0.15f + avgR * 0.85f));
                    int ng = Math.min(255, (int)(g * 0.15f + avgG * 0.85f));
                    int nb = Math.min(255, (int)(b * 0.15f + avgB * 0.85f));
                    pixels[i] = Color.argb(255, nr, ng, nb);
                } else {
                    // Boost saturation of comic art colors for glowing backdrop
                    Color.colorToHSV(p, hsv);
                    hsv[1] = Math.min(1.0f, hsv[1] * 1.6f);
                    if (hsv[2] > 0.2f) {
                        hsv[2] = Math.min(1.0f, hsv[2] * 1.15f);
                    }
                    pixels[i] = Color.HSVToColor(255, hsv);
                }
            }

            Bitmap ambient = Bitmap.createBitmap(smallW, smallH, Bitmap.Config.ARGB_8888);
            ambient.setPixels(pixels, 0, smallW, 0, 0, smallW, smallH);
            small.recycle();
            return ambient;
        } catch (Exception e) {
            return null;
        }
    }

    private void toggleSound() {
        boolean enabled = soundHelper.toggleSound();
        btnSound.setImageResource(enabled ? R.drawable.ic_sound_on : R.drawable.ic_sound_off);
        btnSound.setColorFilter(enabled ? 0xFF3B82F6 : 0xFF94A3B8);
    }

    private void updateSoundUI() {
        boolean enabled = soundHelper.isSoundEnabled();
        btnSound.setImageResource(enabled ? R.drawable.ic_sound_on : R.drawable.ic_sound_off);
        btnSound.setColorFilter(enabled ? 0xFF3B82F6 : 0xFF94A3B8);
    }

    private void toggleNavigation() {
        if (isNavVisible) {
            hideNavigation();
        } else {
            showNavigation();
        }
    }

    private void hideNavigation() {
        isNavVisible = false;
        runOnUiThread(() -> {
            navGlassPill.animate().cancel();
            navGlassPill.animate()
                    .translationY(140f)
                    .alpha(0f)
                    .setDuration(220)
                    .withEndAction(() -> {
                        if (!isNavVisible) {
                            navGlassPill.setVisibility(View.GONE);
                        }
                    })
                    .start();
        });
    }

    private void showNavigation() {
        isNavVisible = true;
        runOnUiThread(() -> {
            navGlassPill.animate().cancel();
            navGlassPill.setVisibility(View.VISIBLE);
            navGlassPill.setTranslationY(140f);
            navGlassPill.setAlpha(0f);
            navGlassPill.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(220)
                    .start();
        });
    }

    private void updateZoomUI() {
        if (curlView.isZoomed()) {
            layoutZoomIndicator.setVisibility(View.VISIBLE);
            tvZoomLevel.setText(String.format("%.1fx", curlView.getZoomScale()));
        } else {
            layoutZoomIndicator.setVisibility(View.GONE);
        }
    }

    private void showPageJumpDialog() {
        if (pdfManager == null) return;
        int total = pdfManager.getPageCount();
        int current = curlView.getCurrentIndex() + 1;

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_page_jump);
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);

        TextView tvHint = dialog.findViewById(R.id.tv_jump_page_hint);
        android.widget.EditText etPage = dialog.findViewById(R.id.et_jump_page);
        View btnConfirm = dialog.findViewById(R.id.btn_jump_confirm);
        View btnCancel = dialog.findViewById(R.id.btn_jump_cancel);

        tvHint.setText("Всего страниц: " + total);
        etPage.setText(String.valueOf(current));
        etPage.selectAll();

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnConfirm.setOnClickListener(v -> {
            String text = etPage.getText().toString().trim();
            if (!text.isEmpty()) {
                try {
                    int p = Integer.parseInt(text);
                    if (p >= 1 && p <= total) {
                        int pageIdx = p - 1;
                        curlView.setCurrentIndex(pageIdx);
                        updatePageUI(pageIdx);
                        dbHelper.updateProgress(bookId, pageIdx);
                        updateAmbientGlow(pageIdx);
                        dialog.dismiss();
                    } else {
                        Toast.makeText(this, "Введите страницу от 1 до " + total, Toast.LENGTH_SHORT).show();
                    }
                } catch (NumberFormatException ignored) {}
            }
        });

        dialog.show();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateViewModeForOrientation(newConfig.orientation);
    }

    private void updateViewModeForOrientation(int orientation) {
        if (curlView == null) return;
        boolean twoPageEnabled = prefs.getBoolean("two_page_landscape", true);
        if (orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE && twoPageEnabled) {
            curlView.setViewMode(CurlView.SHOW_TWO_PAGES);
        } else {
            curlView.setViewMode(CurlView.SHOW_ONE_PAGE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();
        if (curlView != null) {
            curlView.onResume();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (curlView != null) {
            curlView.onPause();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUI();
    }

    @Override
    protected void onDestroy() {
        if (ambientExecutor != null) ambientExecutor.shutdownNow();
        if (soundHelper != null) soundHelper.release();
        if (pdfManager != null) pdfManager.close();
        super.onDestroy();
    }
}
