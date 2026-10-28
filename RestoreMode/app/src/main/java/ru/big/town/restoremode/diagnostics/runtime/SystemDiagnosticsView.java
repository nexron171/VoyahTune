package ru.big.town.restoremode.diagnostics.runtime;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.big.town.restoremode.R;
import ru.big.town.restoremode.settings.ui.layout.SettingsGrid;
import ru.big.town.restoremode.settings.ui.style.SettingsDesign;

/** Presents real observations, retaining row Views between polling ticks. */
public final class SystemDiagnosticsView extends LinearLayout {
    private SettingsGrid infrastructure;
    private SettingsGrid agents;
    private TextView summary;
    private TextView freshness;
    private final Map<String, StatusRow> rendered = new LinkedHashMap<>();
    private long lastRendered;
    private final Runnable expire = () -> {
        if (SystemClock.elapsedRealtime() - lastRendered >= 25_000) {
            for (StatusRow row : rendered.values()) {
                row.indicator.setBackground(dot(RuntimeStatusContract.Level.YELLOW));
                row.setContentDescription(row.title.getText() + ": состояние не обновлено");
            }
            summary.setText("Нет свежего результата диагностики");
            freshness.setText("Проверка задерживается. Предыдущее состояние не подтверждено.");
        }
    };

    public SystemDiagnosticsView(Context context, AttributeSet attributes) {
        super(context, attributes);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        infrastructure = findViewById(R.id.diagnosticsInfrastructure);
        agents = findViewById(R.id.diagnosticsAgents);
        summary = findViewById(R.id.diagnosticsSummary);
        freshness = findViewById(R.id.diagnosticsFreshness);
        LinearLayout legend = findViewById(R.id.diagnosticsLegend);
        addLegend(legend, RuntimeStatusContract.Level.GREEN, "Работает");
        addLegend(legend, RuntimeStatusContract.Level.YELLOW, "Ожидание / отключено");
        addLegend(legend, RuntimeStatusContract.Level.RED, "Ошибка / нет связи");
    }

    public void render(SystemDiagnosticsReader.Result result) {
        List<RuntimeStatusContract.Row> rows = result.rows;
        boolean structureChanged = rendered.size() != rows.size();
        for (RuntimeStatusContract.Row row : rows)
            structureChanged |= !rendered.containsKey(row.id);
        if (structureChanged) {
            infrastructure.removeAllViews();
            agents.removeAllViews();
            rendered.clear();
            for (RuntimeStatusContract.Row row : rows) {
                StatusRow view = new StatusRow(getContext());
                (row.agent ? agents : infrastructure).addView(view);
                rendered.put(row.id, view);
            }
        }
        int healthy = 0;
        int waiting = 0;
        int failed = 0;
        for (RuntimeStatusContract.Row row : rows) {
            rendered.get(row.id).update(row);
            switch (row.level) {
                case GREEN:
                    healthy++;
                    break;
                case YELLOW:
                    waiting++;
                    break;
                case RED:
                    failed++;
                    break;
            }
        }
        summary.setText(
                "Работает: " + healthy + "     Ожидание: " + waiting + "     Ошибки: " + failed);
        freshness.setText(result.freshness);
        lastRendered = SystemClock.elapsedRealtime();
        removeCallbacks(expire);
        postDelayed(expire, 25_000);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(expire);
        super.onDetachedFromWindow();
    }

    private void addLegend(LinearLayout legend, RuntimeStatusContract.Level level, String label) {
        LinearLayout item = new LinearLayout(getContext());
        item.setGravity(Gravity.CENTER_VERTICAL);
        View indicator = new View(getContext());
        indicator.setBackground(dot(level));
        item.addView(indicator, new LayoutParams(dp(10), dp(10)));
        TextView text = text(label, 15, R.color.settings_muted);
        text.setPadding(dp(8), 0, dp(22), 0);
        item.addView(text);
        legend.addView(item);
    }

    private TextView text(String value, int size, int color) {
        TextView text = new TextView(getContext());
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(getContext().getColor(color));
        text.setIncludeFontPadding(false);
        text.setTypeface(getResources().getFont(R.font.settings_arimo_regular));
        return text;
    }

    private int dp(int value) {
        return SettingsDesign.dp(this, value);
    }

    private GradientDrawable dot(RuntimeStatusContract.Level level) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(level == RuntimeStatusContract.Level.GREEN  ? 0xff36b878
                        : level == RuntimeStatusContract.Level.YELLOW ? 0xffe3b341
                                                                      : 0xffe46666);
        return drawable;
    }

    private final class StatusRow extends LinearLayout {
        final View indicator;
        final TextView title;
        final TextView detail;

        StatusRow(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setPadding(dp(12), dp(12), dp(12), dp(12));
            setMinimumHeight(dp(80));
            setBackgroundResource(R.drawable.settings_field);
            indicator = new View(context);
            LayoutParams dotParameters = new LayoutParams(dp(12), dp(12));
            dotParameters.topMargin = dp(4);
            dotParameters.rightMargin = dp(12);
            addView(indicator, dotParameters);
            LinearLayout content = new LinearLayout(context);
            content.setOrientation(VERTICAL);
            title = text("", 18, R.color.settings_text);
            detail = text("", 14, R.color.settings_muted);
            detail.setPadding(0, dp(5), 0, 0);
            detail.setMaxLines(3);
            detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
            content.addView(title);
            content.addView(detail);
            addView(content, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
            setFocusable(true);
        }

        void update(RuntimeStatusContract.Row row) {
            title.setText(row.title);
            detail.setText(row.detail);
            indicator.setBackground(dot(row.level));
            setContentDescription(row.title + ": " + row.detail);
            setOnClickListener(view
                    -> new AlertDialog.Builder(getContext())
                               .setTitle(row.title)
                               .setMessage(row.detail)
                               .setPositiveButton("Закрыть", null)
                               .show());
        }
    }
}
