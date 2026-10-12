package com.holki.auraleaf;

public class BookItem {
    public long id;
    public String title;
    public String uriString;
    public int totalPages;
    public int lastPage;
    public String coverPath;
    public long updatedAt;

    public BookItem(long id, String title, String uriString, int totalPages, int lastPage, String coverPath, long updatedAt) {
        this.id = id;
        this.title = title;
        this.uriString = uriString;
        this.totalPages = totalPages;
        this.lastPage = lastPage;
        this.coverPath = coverPath;
        this.updatedAt = updatedAt;
    }
}
