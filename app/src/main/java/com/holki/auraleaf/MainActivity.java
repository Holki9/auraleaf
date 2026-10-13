package com.holki.auraleaf;

import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private DatabaseHelper dbHelper;
    private SharedPreferences prefs;
    private BookAdapter adapter;
    private RecyclerView recyclerBooks;
    private LinearLayout layoutEmptyState;
    private EditText etSearch;
    private View btnOpenPdf;
    private ImageView btnSettings;
    private Button btnEmptyChoose;
    private HorizontalScrollView scrollSeriesChips;
    private LinearLayout layoutSeriesChips;

    private List<BookItem> allBooks = new ArrayList<>();
    private String selectedSeries = null;
    private ActivityResultLauncher<String[]> filePickerLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        hideSystemUI();

        setContentView(R.layout.activity_main);

        dbHelper = new DatabaseHelper(this);
        prefs = getSharedPreferences("auraleaf_prefs", MODE_PRIVATE);

        initViews();
        initPicker();
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
        recyclerBooks = findViewById(R.id.recycler_books);
        layoutEmptyState = findViewById(R.id.layout_empty_state);
        etSearch = findViewById(R.id.et_search);
        btnOpenPdf = findViewById(R.id.btn_open_pdf);
        btnSettings = findViewById(R.id.btn_settings);
        btnEmptyChoose = findViewById(R.id.btn_empty_choose);
        scrollSeriesChips = findViewById(R.id.scroll_series_chips);
        layoutSeriesChips = findViewById(R.id.layout_series_chips);

        recyclerBooks.setLayoutManager(new GridLayoutManager(this, 2));
        adapter = new BookAdapter(this, new BookAdapter.OnBookClickListener() {
            @Override
            public void onBookClick(BookItem book) {
                openReader(book);
            }

            @Override
            public void onBookRename(BookItem book) {
                showRenameDialog(book);
            }

            @Override
            public void onBookDelete(BookItem book) {
                showDeleteDialog(book);
            }
        });
        recyclerBooks.setAdapter(adapter);

        btnOpenPdf.setOnClickListener(v -> launchPicker());
        btnEmptyChoose.setOnClickListener(v -> launchPicker());
        if (btnSettings != null) {
            btnSettings.setOnClickListener(v -> showAppSettingsDialog());
        }

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterBooks(s.toString().trim());
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void initPicker() {
        filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) {
                        try {
                            getContentResolver().takePersistableUriPermission(
                                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                            );
                        } catch (Exception ignored) {}

                        importComic(uri);
                    }
                }
        );
    }

    private void launchPicker() {
        filePickerLauncher.launch(new String[]{
                "application/pdf",
                "application/x-cbz",
                "application/x-cbr",
                "application/vnd.comicbook+zip",
                "application/vnd.comicbook-rar",
                "application/zip",
                "*/*"
        });
    }

    private void importComic(Uri uri) {
        Toast.makeText(this, "Импорт комикса...", Toast.LENGTH_SHORT).show();

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                String displayName = getFileName(uri);
                String title = displayName != null ? displayName.replaceFirst("[.][^.]+$", "") : "Комикс";

                // Universal cover and page extraction (PDF, CBZ, CBR)
                CoverExtractor.ComicInfo info = CoverExtractor.extract(this, uri, displayName);

                long bookId = dbHelper.insertBook(title, uri.toString(), info.totalPages, 0, info.coverPath);

                runOnUiThread(() -> {
                    loadBooks();
                    BookItem newBook = new BookItem(bookId, title, uri.toString(), info.totalPages, 0, info.coverPath, System.currentTimeMillis());
                    openReader(newBook);
                });

            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Ошибка импорта: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private String getFileName(Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index >= 0) result = cursor.getString(index);
                }
            } catch (Exception ignored) {}
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result != null ? result.lastIndexOf('/') : -1;
            if (cut != -1) result = result.substring(cut + 1);
        }
        return result;
    }

    private void loadBooks() {
        allBooks = dbHelper.getAllBooks();
        updateSeriesChips();
        filterBooks(etSearch.getText().toString().trim());
    }

    private String extractSeriesName(String title) {
        if (title == null || title.trim().isEmpty()) return "Разное";
        String t = title.trim();
        // Remove leading volume/chapter numbers: "07_", "01 - ", "#1 ", "Vol 1 - "
        t = t.replaceAll("^(?:\\d+[\\s_.-]+|#\\d+[\\s_.-]*|[Vv]ol\\.?\\s*\\d+[\\s_.-]*)", "");
        // Split by common title separators
        String[] parts = t.split("(?:\\s+-\\s+|\\s+—\\s+|\\s*:\\s*|_|\\[|\\()", 2);
        if (parts.length > 0) {
            String candidate = parts[0].trim();
            if (candidate.length() >= 2) {
                return candidate;
            }
        }
        return t.length() > 18 ? t.substring(0, 18) : t;
    }

    private void updateSeriesChips() {
        if (layoutSeriesChips == null || scrollSeriesChips == null) return;
        layoutSeriesChips.removeAllViews();

        if (allBooks.isEmpty()) {
            scrollSeriesChips.setVisibility(View.GONE);
            return;
        }

        // Count books per series
        Map<String, Integer> seriesCounts = new LinkedHashMap<>();
        for (BookItem b : allBooks) {
            String s = extractSeriesName(b.title);
            seriesCounts.put(s, seriesCounts.getOrDefault(s, 0) + 1);
        }

        // Show chips if multiple series exist or if at least 1 distinct group has books
        if (seriesCounts.size() < 2 && (selectedSeries == null || selectedSeries.isEmpty())) {
            scrollSeriesChips.setVisibility(View.GONE);
            return;
        }

        scrollSeriesChips.setVisibility(View.VISIBLE);

        // "Все" Chip
        addSeriesChip("Все (" + allBooks.size() + ")", null, selectedSeries == null);

        // Series Chips
        for (Map.Entry<String, Integer> entry : seriesCounts.entrySet()) {
            final String seriesName = entry.getKey();
            boolean isSelected = seriesName.equalsIgnoreCase(selectedSeries);
            addSeriesChip(seriesName + " (" + entry.getValue() + ")", seriesName, isSelected);
        }
    }

    private void addSeriesChip(String title, String seriesFilter, boolean isSelected) {
        TextView chip = new TextView(this);
        chip.setText(title);
        chip.setTextSize(12f);
        chip.setGravity(Gravity.CENTER);
        int padH = dpToPx(14);
        int padV = dpToPx(6);
        chip.setPadding(padH, padV, padH, padV);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.setMarginEnd(dpToPx(8));
        chip.setLayoutParams(lp);

        if (isSelected) {
            chip.setBackgroundResource(R.drawable.bg_button_glow);
            chip.setTextColor(0xFFFFFFFF);
            chip.setTypeface(null, android.graphics.Typeface.BOLD);
        } else {
            chip.setBackgroundResource(R.drawable.bg_glass_pill);
            chip.setTextColor(0xFF94A3B8);
            chip.setTypeface(null, android.graphics.Typeface.NORMAL);
        }

        chip.setClickable(true);
        chip.setFocusable(true);
        chip.setOnClickListener(v -> {
            selectedSeries = seriesFilter;
            updateSeriesChips();
            filterBooks(etSearch.getText().toString().trim());
        });

        layoutSeriesChips.addView(chip);
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void filterBooks(String query) {
        List<BookItem> filtered = new ArrayList<>();
        String qLower = query.toLowerCase();

        for (BookItem b : allBooks) {
            boolean matchesSeries = (selectedSeries == null) ||
                    extractSeriesName(b.title).equalsIgnoreCase(selectedSeries);
            boolean matchesQuery = qLower.isEmpty() || b.title.toLowerCase().contains(qLower);

            if (matchesSeries && matchesQuery) {
                filtered.add(b);
            }
        }

        adapter.setBooks(filtered);

        boolean isEmpty = allBooks.isEmpty();
        layoutEmptyState.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        recyclerBooks.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }

    private void showAppSettingsDialog() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_app_settings);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        TextView tvBlurStatus = dialog.findViewById(R.id.tv_blur_status);
        TextView tvBlurPercent = dialog.findViewById(R.id.tv_blur_percent);
        SeekBar seekBlur = dialog.findViewById(R.id.seek_blur);

        TextView chipOff = dialog.findViewById(R.id.chip_blur_off);
        TextView chipLight = dialog.findViewById(R.id.chip_blur_light);
        TextView chipMedium = dialog.findViewById(R.id.chip_blur_medium);
        TextView chipStrong = dialog.findViewById(R.id.chip_blur_strong);

        SwitchCompat switchHaptic = dialog.findViewById(R.id.switch_haptic);
        SwitchCompat switchSound = dialog.findViewById(R.id.switch_sound);
        SwitchCompat switchTwoPage = dialog.findViewById(R.id.switch_two_page);

        View btnDone = dialog.findViewById(R.id.btn_settings_done);
        View btnCloseIcon = dialog.findViewById(R.id.btn_settings_close_icon);

        // Load values
        int currentBlur = prefs.getInt("blur_level", 25);
        boolean currentHaptic = prefs.getBoolean("haptic_enabled", true);
        boolean currentSound = prefs.getBoolean("sound_enabled", true);
        boolean currentTwoPage = prefs.getBoolean("two_page_landscape", true);

        seekBlur.setProgress(currentBlur);
        switchHaptic.setChecked(currentHaptic);
        switchSound.setChecked(currentSound);
        switchTwoPage.setChecked(currentTwoPage);

        Runnable updateBlurUI = () -> {
            int val = seekBlur.getProgress();
            tvBlurPercent.setText(val + "%");
            if (val == 0) {
                tvBlurStatus.setText("Выкл (тёмный фон)");
                tvBlurStatus.setTextColor(0xFF94A3B8);
            } else if (val <= 35) {
                tvBlurStatus.setText("Лёгкое (картинка чёткая)");
                tvBlurStatus.setTextColor(0xFF38BDF8);
            } else if (val <= 65) {
                tvBlurStatus.setText("Среднее (кинематографичное)");
                tvBlurStatus.setTextColor(0xFF38BDF8);
            } else {
                tvBlurStatus.setText("Сильное (абстрактное)");
                tvBlurStatus.setTextColor(0xFF818CF8);
            }

            chipOff.setBackgroundResource(val == 0 ? R.drawable.bg_button_glow : R.drawable.bg_glass_pill);
            chipOff.setTextColor(val == 0 ? 0xFFFFFFFF : 0xFF94A3B8);

            chipLight.setBackgroundResource((val > 0 && val <= 35) ? R.drawable.bg_button_glow : R.drawable.bg_glass_pill);
            chipLight.setTextColor((val > 0 && val <= 35) ? 0xFFFFFFFF : 0xFF94A3B8);

            chipMedium.setBackgroundResource((val > 35 && val <= 65) ? R.drawable.bg_button_glow : R.drawable.bg_glass_pill);
            chipMedium.setTextColor((val > 35 && val <= 65) ? 0xFFFFFFFF : 0xFF94A3B8);

            chipStrong.setBackgroundResource(val > 65 ? R.drawable.bg_button_glow : R.drawable.bg_glass_pill);
            chipStrong.setTextColor(val > 65 ? 0xFFFFFFFF : 0xFF94A3B8);
        };

        updateBlurUI.run();

        seekBlur.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateBlurUI.run();
                if (fromUser) {
                    prefs.edit().putInt("blur_level", progress).apply();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                prefs.edit().putInt("blur_level", seekBar.getProgress()).apply();
            }
        });

        chipOff.setOnClickListener(v -> {
            seekBlur.setProgress(0);
            prefs.edit().putInt("blur_level", 0).apply();
            updateBlurUI.run();
        });

        chipLight.setOnClickListener(v -> {
            seekBlur.setProgress(25);
            prefs.edit().putInt("blur_level", 25).apply();
            updateBlurUI.run();
        });

        chipMedium.setOnClickListener(v -> {
            seekBlur.setProgress(50);
            prefs.edit().putInt("blur_level", 50).apply();
            updateBlurUI.run();
        });

        chipStrong.setOnClickListener(v -> {
            seekBlur.setProgress(80);
            prefs.edit().putInt("blur_level", 80).apply();
            updateBlurUI.run();
        });

        switchHaptic.setOnCheckedChangeListener((btn, isChecked) -> {
            prefs.edit().putBoolean("haptic_enabled", isChecked).apply();
        });

        switchSound.setOnCheckedChangeListener((btn, isChecked) -> {
            prefs.edit().putBoolean("sound_enabled", isChecked).apply();
        });

        switchTwoPage.setOnCheckedChangeListener((btn, isChecked) -> {
            prefs.edit().putBoolean("two_page_landscape", isChecked).apply();
        });

        btnDone.setOnClickListener(v -> dialog.dismiss());
        if (btnCloseIcon != null) {
            btnCloseIcon.setOnClickListener(v -> dialog.dismiss());
        }

        dialog.show();
    }

    private void openReader(BookItem book) {
        Intent intent = new Intent(this, ReaderActivity.class);
        intent.putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id);
        intent.putExtra(ReaderActivity.EXTRA_URI, book.uriString);
        intent.putExtra(ReaderActivity.EXTRA_LAST_PAGE, book.lastPage);
        startActivity(intent);
    }

    private void showRenameDialog(BookItem book) {
        EditText input = new EditText(this);
        input.setText(book.title);
        input.setTextColor(Color.WHITE);

        new AlertDialog.Builder(this)
                .setTitle("Переименовать")
                .setView(input)
                .setPositiveButton("Сохранить", (d, w) -> {
                    String newTitle = input.getText().toString().trim();
                    if (!newTitle.isEmpty()) {
                        dbHelper.updateTitle(book.id, newTitle);
                        loadBooks();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void showDeleteDialog(BookItem book) {
        new AlertDialog.Builder(this)
                .setTitle("Удалить книгу")
                .setMessage("Удалить «" + book.title + "» с полки?")
                .setPositiveButton("Удалить", (d, w) -> {
                    dbHelper.deleteBook(book.id);
                    loadBooks();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();
        loadBooks();
    }
}
