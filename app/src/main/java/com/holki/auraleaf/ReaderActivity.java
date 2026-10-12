package com.holki.auraleaf;

import android.app.Dialog;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

public class ReaderActivity extends AppCompatActivity {

    public static final String EXTRA_BOOK_ID = "extra_book_id";
    public static final String EXTRA_URI = "extra_uri";
    public static final String EXTRA_LAST_PAGE = "extra_last_page";

    private long bookId;
    private String uriString;
    private int startPage;

    private PdfManager pdfManager;
    private SoundHelper soundHelper;
    private DatabaseHelper dbHelper;
    private SharedPreferences prefs;

    private PageCurlView curlView;
    private ImageView imgAmbient;
    private LinearLayout navGlassPill;
    private LinearLayout layoutZoomIndicator;
    private TextView tvZoomLevel;
    private ImageView btnZoomReset;

    private ImageView btnShelf;
    private ImageView btnPrev;
    private ImageView btnNext;
    private TextView tvPageCounter;
    private ImageView btnAmbient;
    private ImageView btnSound;

    private boolean isAmbientEnabled = true;
    private boolean isNavVisible = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Edge-to-edge immersive full screen
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        hideSystemUI();

        setContentView(R.layout.activity_reader);

        bookId = getIntent().getLongExtra(EXTRA_BOOK_ID, -1);
        uriString = getIntent().getStringExtra(EXTRA_URI);
        startPage = getIntent().getIntExtra(EXTRA_LAST_PAGE, 0);

        dbHelper = new DatabaseHelper(this);
        soundHelper = new SoundHelper(this);
        prefs = getSharedPreferences("auraleaf_prefs", MODE_PRIVATE);
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
        btnAmbient = findViewById(R.id.btn_ambient);
        btnSound = findViewById(R.id.btn_sound);

        btnShelf.setOnClickListener(v -> finish());
        btnPrev.setOnClickListener(v -> curlView.flipPrev());
        btnNext.setOnClickListener(v -> curlView.flipNext());

        btnZoomReset.setOnClickListener(v -> {
            curlView.resetZoom();
            updateZoomUI();
        });

        btnAmbient.setOnClickListener(v -> toggleAmbient());
        btnSound.setOnClickListener(v -> toggleSound());

        tvPageCounter.setOnClickListener(v -> showPageJumpDialog());

        updateSoundUI();
        updateAmbientUI();
    }

    private void setupAmbientEffect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Authentic GPU hardware blur
            imgAmbient.setRenderEffect(RenderEffect.createBlurEffect(90f, 90f, Shader.TileMode.CLAMP));
        }
    }

    private void openPdf() {
        try {
            Uri uri = Uri.parse(uriString);
            pdfManager = new PdfManager(this, uri);

            curlView.setup(pdfManager, soundHelper, startPage, new PageCurlView.OnPageListener() {
                @Override
                public void onPageChanged(int newPageIndex, Bitmap currentBitmap) {
                    updatePageUI(newPageIndex);
                    dbHelper.updateProgress(bookId, newPageIndex);
                    updateAmbientGlow(currentBitmap);
                }

                @Override
                public void onSingleTap() {
                    toggleNavigation();
                }

                @Override
                public void onDoubleTap() {
                    showNavigation();
                    updateZoomUI();
                }
            });

            updatePageUI(startPage);
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Не удалось открыть PDF: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void updatePageUI(int pageIndex) {
        if (pdfManager != null) {
            int total = pdfManager.getPageCount();
            tvPageCounter.setText((pageIndex + 1) + " / " + total);
        }
    }

    private void updateAmbientGlow(Bitmap bitmap) {
        if (!isAmbientEnabled || bitmap == null || bitmap.isRecycled()) return;

        runOnUiThread(() -> {
            imgAmbient.setImageBitmap(bitmap);
            imgAmbient.setAlpha(0f);
            imgAmbient.animate().alpha(0.85f).setDuration(350).start();
        });
    }

    private void toggleAmbient() {
        isAmbientEnabled = !isAmbientEnabled;
        prefs.edit().putBoolean("ambient_enabled", isAmbientEnabled).apply();
        updateAmbientUI();
        if (isAmbientEnabled) {
            updateAmbientGlow(curlView.getCurrentBitmap());
        } else {
            imgAmbient.animate().alpha(0f).setDuration(250).start();
        }
    }

    private void updateAmbientUI() {
        btnAmbient.setColorFilter(isAmbientEnabled ? 0xFF3B82F6 : 0xFF94A3B8);
        imgAmbient.setVisibility(isAmbientEnabled ? View.VISIBLE : View.GONE);
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
        navGlassPill.animate()
                .translationY(navGlassPill.getHeight() + 100f)
                .alpha(0f)
                .setDuration(220)
                .start();
    }

    private void showNavigation() {
        isNavVisible = true;
        navGlassPill.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(220)
                .start();
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
        int current = curlView.getCurrentPageIndex() + 1;

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_page_jump);
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);

        TextView tvVal = dialog.findViewById(R.id.tv_jump_page_val);
        SeekBar seekBar = dialog.findViewById(R.id.seekbar_jump);
        View btnConfirm = dialog.findViewById(R.id.btn_jump_confirm);

        tvVal.setText(String.valueOf(current));
        seekBar.setMax(total);
        seekBar.setProgress(current);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int p = Math.max(1, progress);
                tvVal.setText(String.valueOf(p));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        btnConfirm.setOnClickListener(v -> {
            int p = Math.max(1, seekBar.getProgress()) - 1;
            curlView.flipToPage(p);
            dialog.dismiss();
        });

        dialog.show();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUI();
    }

    @Override
    protected void onDestroy() {
        if (soundHelper != null) soundHelper.release();
        if (pdfManager != null) pdfManager.close();
        super.onDestroy();
    }
}
