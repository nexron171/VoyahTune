package ru.big.town.restoremode.settings.ui.list;

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

/** A section viewport whose rows retain data instead of detached View trees. */
public final class SettingsList extends RecyclerView {
    public interface RowFactory {
        View create(LinearLayout parent);
    }

    public static final class Row {
        final String key;
        final RowFactory factory;

        public Row(String key, RowFactory factory) {
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

    public void onRelease(Consumer<View> action) {
        releaseRow = action;
    }

    public void onVisibilityChanged(Runnable action) {
        onVisibilityChanged = action;
    }

    public int createdRows() {
        return createdRows;
    }

    public List<String> rowKeys() {
        List<String> keys = new ArrayList<>();
        for (Row row : adapter.rows) {
            keys.add(row.key);
        }
        return keys;
    }

    public void submit(List<Row> rows, boolean resetScroll) {
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

    public void scrollToKey(String key) {
        for (int i = 0; i < adapter.rows.size(); i++) {
            if (adapter.rows.get(i).key.equals(key)) {
                ((LinearLayoutManager) getLayoutManager()).scrollToPositionWithOffset(i, 0);
                return;
            }
        }
    }

    public void bindDraft(EditText field, String key, String saved) {
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

    public void clearDraft(String key) {
        drafts.remove(key);
    }

    public Bundle saveDrafts() {
        return new Bundle(drafts);
    }

    public void restoreDrafts(Bundle saved) {
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
