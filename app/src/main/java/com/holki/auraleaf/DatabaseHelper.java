package com.holki.auraleaf;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

public class DatabaseHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "auraleaf.db";
    private static final int DATABASE_VERSION = 1;

    public static final String TABLE_BOOKS = "books";
    public static final String COL_ID = "id";
    public static final String COL_TITLE = "title";
    public static final String COL_URI = "uri";
    public static final String COL_TOTAL_PAGES = "total_pages";
    public static final String COL_LAST_PAGE = "last_page";
    public static final String COL_COVER_PATH = "cover_path";
    public static final String COL_UPDATED_AT = "updated_at";

    public DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String createTable = "CREATE TABLE " + TABLE_BOOKS + " (" +
                COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COL_TITLE + " TEXT, " +
                COL_URI + " TEXT, " +
                COL_TOTAL_PAGES + " INTEGER, " +
                COL_LAST_PAGE + " INTEGER, " +
                COL_COVER_PATH + " TEXT, " +
                COL_UPDATED_AT + " INTEGER)";
        db.execSQL(createTable);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_BOOKS);
        onCreate(db);
    }

    public long insertBook(String title, String uri, int totalPages, int lastPage, String coverPath) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_TITLE, title);
        cv.put(COL_URI, uri);
        cv.put(COL_TOTAL_PAGES, totalPages);
        cv.put(COL_LAST_PAGE, lastPage);
        cv.put(COL_COVER_PATH, coverPath);
        cv.put(COL_UPDATED_AT, System.currentTimeMillis());
        return db.insert(TABLE_BOOKS, null, cv);
    }

    public void updateProgress(long id, int lastPage) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_LAST_PAGE, lastPage);
        cv.put(COL_UPDATED_AT, System.currentTimeMillis());
        db.update(TABLE_BOOKS, cv, COL_ID + "=?", new String[]{String.valueOf(id)});
    }

    public void updateTitle(long id, String newTitle) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_TITLE, newTitle);
        cv.put(COL_UPDATED_AT, System.currentTimeMillis());
        db.update(TABLE_BOOKS, cv, COL_ID + "=?", new String[]{String.valueOf(id)});
    }

    public void deleteBook(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_BOOKS, COL_ID + "=?", new String[]{String.valueOf(id)});
    }

    public List<BookItem> getAllBooks() {
        List<BookItem> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_BOOKS, null, null, null, null, null, COL_UPDATED_AT + " DESC");
        if (cursor != null) {
            while (cursor.moveToNext()) {
                long id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID));
                String title = cursor.getString(cursor.getColumnIndexOrThrow(COL_TITLE));
                String uri = cursor.getString(cursor.getColumnIndexOrThrow(COL_URI));
                int totalPages = cursor.getInt(cursor.getColumnIndexOrThrow(COL_TOTAL_PAGES));
                int lastPage = cursor.getInt(cursor.getColumnIndexOrThrow(COL_LAST_PAGE));
                String coverPath = cursor.getString(cursor.getColumnIndexOrThrow(COL_COVER_PATH));
                long updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_UPDATED_AT));
                list.add(new BookItem(id, title, uri, totalPages, lastPage, coverPath, updatedAt));
            }
            cursor.close();
        }
        return list;
    }
}
