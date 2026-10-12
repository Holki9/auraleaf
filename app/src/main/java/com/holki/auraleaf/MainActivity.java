package com.holki.auraleaf;

import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private DatabaseHelper dbHelper;
    private BookAdapter adapter;
    private RecyclerView recyclerBooks;
    private LinearLayout layoutEmptyState;
    private EditText etSearch;
    private View btnOpenPdf;
    private Button btnEmptyChoose;

    private List<BookItem> allBooks = new ArrayList<>();
    private ActivityResultLauncher<String[]> pdfPickerLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        hideSystemUI();

        setContentView(R.layout.activity_main);

        dbHelper = new DatabaseHelper(this);

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
        btnEmptyChoose = findViewById(R.id.btn_empty_choose);

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

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterBooks(s.toString().trim());
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void initPicker() {
        pdfPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) {
                        try {
                            getContentResolver().takePersistableUriPermission(
                                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                            );
                        } catch (Exception ignored) {}

                        importPdf(uri);
                    }
                }
        );
    }

    private void launchPicker() {
        pdfPickerLauncher.launch(new String[]{"application/pdf"});
    }

    private void importPdf(Uri uri) {
        Toast.makeText(this, "Загрузка книги...", Toast.LENGTH_SHORT).show();

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                String displayName = getFileName(uri);
                String title = displayName != null ? displayName.replaceFirst("[.][^.]+$", "") : "Комикс";

                // Open with PdfRenderer
                ParcelFileDescriptor pfd;
                try {
                    pfd = getContentResolver().openFileDescriptor(uri, "r");
                } catch (Exception e) {
                    File temp = new File(getCacheDir(), "import_temp.pdf");
                    try (InputStream in = getContentResolver().openInputStream(uri);
                         FileOutputStream out = new FileOutputStream(temp)) {
                        byte[] buffer = new byte[8192];
                        int r;
                        while ((r = in.read(buffer)) != -1) out.write(buffer, 0, r);
                    }
                    pfd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY);
                }

                PdfRenderer renderer = new PdfRenderer(pfd);
                int totalPages = renderer.getPageCount();

                // Generate cover thumbnail from page 1
                File coversDir = new File(getFilesDir(), "covers");
                coversDir.mkdirs();
                File coverFile = new File(coversDir, "cover_" + System.currentTimeMillis() + ".jpg");

                if (totalPages > 0) {
                    PdfRenderer.Page page0 = renderer.openPage(0);
                    int tw = 320;
                    int th = (int) (tw * ((float) page0.getHeight() / page0.getWidth()));
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

                long bookId = dbHelper.insertBook(title, uri.toString(), totalPages, 0, coverFile.getAbsolutePath());

                runOnUiThread(() -> {
                    loadBooks();
                    BookItem newBook = new BookItem(bookId, title, uri.toString(), totalPages, 0, coverFile.getAbsolutePath(), System.currentTimeMillis());
                    openReader(newBook);
                });

            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show());
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
        filterBooks(etSearch.getText().toString().trim());
    }

    private void filterBooks(String query) {
        if (query.isEmpty()) {
            adapter.setBooks(allBooks);
        } else {
            List<BookItem> filtered = new ArrayList<>();
            for (BookItem b : allBooks) {
                if (b.title.toLowerCase().contains(query.toLowerCase())) {
                    filtered.add(b);
                }
            }
            adapter.setBooks(filtered);
        }

        boolean isEmpty = allBooks.isEmpty();
        layoutEmptyState.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        recyclerBooks.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
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
