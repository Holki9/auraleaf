package com.holki.auraleaf;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.widget.PopupMenu;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class BookAdapter extends RecyclerView.Adapter<BookAdapter.BookViewHolder> {

    public interface OnBookClickListener {
        void onBookClick(BookItem book);
        void onBookRename(BookItem book);
        void onBookDelete(BookItem book);
    }

    private Context context;
    private List<BookItem> books = new ArrayList<>();
    private OnBookClickListener listener;

    public BookAdapter(Context context, OnBookClickListener listener) {
        this.context = context;
        this.listener = listener;
    }

    public void setBooks(List<BookItem> list) {
        this.books = list;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public BookViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context).inflate(R.layout.item_book, parent, false);
        return new BookViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull BookViewHolder holder, int position) {
        BookItem book = books.get(position);
        holder.tvTitle.setText(book.title);

        int pct = book.totalPages > 0 ? (int) (((book.lastPage + 1) / (float) book.totalPages) * 100) : 0;
        holder.tvBadgePercent.setText(pct + "%");
        holder.progressBar.setProgress(pct);
        holder.tvPageInfo.setText("Стр. " + (book.lastPage + 1) + " / " + book.totalPages);

        if (book.coverPath != null && new File(book.coverPath).exists()) {
            Bitmap bmp = BitmapFactory.decodeFile(book.coverPath);
            holder.imgCover.setImageBitmap(bmp);
        } else {
            holder.imgCover.setImageResource(R.drawable.ic_shelf);
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onBookClick(book);
        });

        holder.btnMore.setOnClickListener(v -> {
            PopupMenu popup = new PopupMenu(context, holder.btnMore);
            popup.getMenu().add(0, 1, 0, "Переименовать");
            popup.getMenu().add(0, 2, 1, "Удалить");
            popup.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == 1 && listener != null) {
                    listener.onBookRename(book);
                    return true;
                } else if (item.getItemId() == 2 && listener != null) {
                    listener.onBookDelete(book);
                    return true;
                }
                return false;
            });
            popup.show();
        });
    }

    @Override
    public int getItemCount() {
        return books.size();
    }

    static class BookViewHolder extends RecyclerView.ViewHolder {
        ImageView imgCover;
        TextView tvBadgePercent;
        TextView tvTitle;
        ProgressBar progressBar;
        TextView tvPageInfo;
        ImageView btnMore;

        public BookViewHolder(@NonNull View itemView) {
            super(itemView);
            imgCover = itemView.findViewById(R.id.img_cover);
            tvBadgePercent = itemView.findViewById(R.id.tv_badge_percent);
            tvTitle = itemView.findViewById(R.id.tv_title);
            progressBar = itemView.findViewById(R.id.progress_bar);
            tvPageInfo = itemView.findViewById(R.id.tv_page_info);
            btnMore = itemView.findViewById(R.id.btn_more);
        }
    }
}
