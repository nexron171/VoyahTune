package ru.big.town.restoremode;

import android.content.Context;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** A single viewport shared by settings sections; rows retain data, never detached View trees. */
public final class SettingsList extends RecyclerView {
    interface RowFactory {
        View create(LinearLayout parent);
    }

    static final class Row {
        final String key;
        final RowFactory factory;

        Row(String key, RowFactory factory) {
            this.key = key;
            this.factory = factory;
        }
    }

    private final SettingsAdapter adapter = new SettingsAdapter();
    private Consumer<View> releaseRow = view -> {};
    private Runnable onVisibilityChanged = () -> {};
    private int createdRows;
    private final Bundle drafts = new Bundle();

    public SettingsList(Context context, AttributeSet attributes) {
        super(context, attributes);
        LinearLayoutManager layout = new LinearLayoutManager(context);
        layout.setItemPrefetchEnabled(false);
        setLayoutManager(layout);
        setItemViewCacheSize(0);
        setItemAnimator(null);
        setAdapter(adapter);
    }

    void onRelease(Consumer<View> action) {
        releaseRow = action;
    }

    void onVisibilityChanged(Runnable action) {
        onVisibilityChanged = action;
    }

    int createdRows() {
        return createdRows;
    }

    List<String> rowKeys() {
        List<String> keys = new ArrayList<>();
        for (Row row : adapter.rows) {
            keys.add(row.key);
        }
        return keys;
    }

    void submit(List<Row> rows, boolean resetScroll) {
        List<Row> previous = adapter.rows;
        DiffUtil.DiffResult diff =
                DiffUtil.calculateDiff(
                        new DiffUtil.Callback() {
                            public int getOldListSize() {
                                return previous.size();
                            }

                            public int getNewListSize() {
                                return rows.size();
                            }

                            public boolean areItemsTheSame(int oldIndex, int newIndex) {
                                return previous.get(oldIndex).key.equals(rows.get(newIndex).key);
                            }

                            public boolean areContentsTheSame(int oldIndex, int newIndex) {
                                return previous.get(oldIndex) == rows.get(newIndex);
                            }
                        });
        adapter.rows = new ArrayList<>(rows);
        diff.dispatchUpdatesTo(adapter);
        if (resetScroll) {
            ((LinearLayoutManager) getLayoutManager()).scrollToPositionWithOffset(0, 0);
        }
    }

    void scrollToKey(String key) {
        for (int i = 0; i < adapter.rows.size(); i++) {
            if (adapter.rows.get(i).key.equals(key)) {
                ((LinearLayoutManager) getLayoutManager()).scrollToPositionWithOffset(i, 0);
                return;
            }
        }
    }

    void bindDraft(EditText field, String key, String saved) {
        field.setText(drafts.getString(key, saved));
        field.addTextChangedListener(
                new android.text.TextWatcher() {
                    public void beforeTextChanged(
                            CharSequence s, int start, int count, int after) {}

                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                        drafts.putString(key, s.toString());
                    }

                    public void afterTextChanged(android.text.Editable value) {}
                });
    }

    void clearDraft(String key) {
        drafts.remove(key);
    }

    Bundle saveDrafts() {
        return new Bundle(drafts);
    }

    void restoreDrafts(Bundle saved) {
        if (saved != null) {
            drafts.putAll(saved);
        }
    }

    private final class SettingsAdapter extends Adapter<SettingsViewHolder> {
        private List<Row> rows = new ArrayList<>();

        @NonNull
        public SettingsViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            LinearLayout host = new LinearLayout(getContext());
            host.setOrientation(LinearLayout.VERTICAL);
            host.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            return new SettingsViewHolder(host);
        }

        public void onBindViewHolder(@NonNull SettingsViewHolder holder, int position) {
            clear(holder);
            Row row = rows.get(position);
            holder.key = row.key;
            View content = row.factory.create(holder.host);
            holder.host.addView(content);
            createdRows++;
        }

        public int getItemCount() {
            return rows.size();
        }

        public void onViewRecycled(@NonNull SettingsViewHolder holder) {
            clear(holder);
        }

        public void onViewAttachedToWindow(@NonNull SettingsViewHolder holder) {
            onVisibilityChanged.run();
        }

        public void onViewDetachedFromWindow(@NonNull SettingsViewHolder holder) {
            onVisibilityChanged.run();
        }

        private void clear(SettingsViewHolder holder) {
            for (int i = 0; i < holder.host.getChildCount(); i++) {
                releaseRow.accept(holder.host.getChildAt(i));
            }
            holder.host.removeAllViews();
            holder.key = null;
        }
    }

    private static final class SettingsViewHolder extends ViewHolder {
        final LinearLayout host;
        String key;

        SettingsViewHolder(LinearLayout host) {
            super(host);
            this.host = host;
        }
    }
}
