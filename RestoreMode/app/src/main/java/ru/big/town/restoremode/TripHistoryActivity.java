package ru.big.town.restoremode;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.database.Cursor;
import android.net.Uri;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import ru.big.town.common.TripProtocol;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Last ten trips. Small summaries arrive via broadcast; samples load through a protected provider. */
public class TripHistoryActivity extends AppCompatActivity {

    private static final String TAG = "$$$ TripHistory $$$";
    private LinearLayout tripLogContainer;
    private View deleteAllButton;
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault());

    private final ExecutorService reader = Executors.newSingleThreadExecutor();
    private String renderedJson;
    private int renderGeneration;

    private final BroadcastReceiver tripReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            renderTripLog(intent.getStringExtra("tripsJson"));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        setContentView(R.layout.activity_trip_history);
        applyWindowInsets();
        tripLogContainer = findViewById(R.id.tripLogContainer);
        deleteAllButton = findViewById(R.id.tripDeleteAll);
        deleteAllButton.setOnClickListener(v -> confirmDelete(0, true));
        // Снимок из интента (если передали) — чтобы список был сразу
        renderTripLog(getIntent().getStringExtra("tripsJson"));
    }

    /**
     * Родной док головы висит поверх окна слева и не попадает в system bar insets.
     * Резервируем его полосу и добавляем системные insets к штатным отступам layout.
     */
    private void applyWindowInsets() {
        final View root = findViewById(R.id.tripHistoryRoot);
        if (root == null) return;

        final int nativeDock = Math.round(getResources().getDisplayMetrics().density * 145f);
        final int baseLeft = root.getPaddingLeft();
        final int baseTop = root.getPaddingTop();
        final int baseRight = root.getPaddingRight();
        final int baseBottom = root.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sb = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            int top = sb.top;
            if (top == 0) {
                int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
                if (id > 0) top = getResources().getDimensionPixelSize(id);
            }
            v.setPadding(
                    baseLeft + nativeDock + sb.left,
                    baseTop + top,
                    baseRight + sb.right,
                    baseBottom + sb.bottom);
            return insets;
        });
    }

    public void onButtonBackHistory(View v) {
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(tripReceiver, new IntentFilter(MainActivity.ACTION_TRIP_UPDATE), TripProtocol.PERMISSION, null, RECEIVER_EXPORTED);
        Intent req = new Intent(MainActivity.ACTION_REQUEST_TRIP_UPDATE);
        req.setPackage("ru.big.town.anative");
        sendBroadcast(req, TripProtocol.PERMISSION);
        loadSummaries();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(tripReceiver); } catch (Exception ignored) {}
    }

    private void loadSummaries() {
        final int generation = renderGeneration;
        reader.execute(() -> {
            try (Cursor cursor = getContentResolver().query(Uri.parse("content://" + TripProtocol.AUTHORITY + "/trips"), null, null, null, null)) {
                if (cursor == null) return;
                JSONArray rows = new JSONArray();
                while (cursor.moveToNext()) rows.put(new JSONObject(cursor.getString(0)));
                String json = rows.toString();
                runOnUiThread(() -> { if (!isDestroyed() && generation == renderGeneration) renderTripLog(json); });
            } catch (Exception unavailable) { Log.w(TAG, "History provider unavailable", unavailable); }
        });
    }

    void renderTripLog(String json) {
        if (tripLogContainer == null || json == null || json.equals(renderedJson)) return;
        try {
            JSONArray arr = new JSONArray(json);
            tripLogContainer.removeAllViews(); renderedJson = json; renderGeneration++;
            deleteAllButton.setEnabled(arr.length() > 0);
            deleteAllButton.setAlpha(arr.length() > 0 ? 1f : .45f);
            if (arr.length() == 0) {
                TextView empty = label("Поездок пока нет");
                empty.setTextSize(24); empty.setPadding(0, dp(24), 0, dp(24));
                tripLogContainer.addView(empty); return;
            }
            LayoutInflater inf = LayoutInflater.from(this);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject t = arr.getJSONObject(i); final long start = t.getLong("start");
                View row = inf.inflate(R.layout.item_trip, tripLogContainer, false);
                String when = dateFmt.format(new Date(start));
                long end = t.optLong("end", 0);
                if (end > 0) when += " — " + dateFmt.format(new Date(end));
                String heading = "Поездка " + when + " · " + fmtDurationShort(t.getLong("durationMs")) + " · " + distance(t.optDouble("distanceKm"));
                if (t.optBoolean("test", false)) heading = "Тестовая · " + t.optString("testLabel", "поездка") + " · " + heading;
                ((TextView)row.findViewById(R.id.tripTitle)).setText(heading);
                ((TextView)row.findViewById(R.id.tripSummary)).setText(
                        "Заряд: " + number(t.optDouble("startSoc"), "%") + " → " + number(t.optDouble("endSoc"), "%")
                        + "    Бензин: " + number(t.optDouble("startFuel"), "%") + " → " + number(t.optDouble("endFuel"), "%"));
                consumption(row.findViewById(R.id.tripElectricity), t.optDouble("electricityKwh"), "кВт·ч", EnergyWidgetStyle.GREEN);
                consumption(row.findViewById(R.id.tripFuel), t.optDouble("fuelLiters"), "л", EnergyWidgetStyle.BLUE);
                consumption(row.findViewById(R.id.tripElectricityPer100),
                        TripHistoryPresentation.averagePer100(t.optDouble("electricityKwh"), t.optDouble("distanceKm"), true),
                        "кВт·ч", EnergyWidgetStyle.GREEN);
                consumption(row.findViewById(R.id.tripFuelPer100),
                        TripHistoryPresentation.averagePer100(t.optDouble("fuelLiters"), t.optDouble("distanceKm")),
                        "л", EnergyWidgetStyle.BLUE);
                row.findViewById(R.id.tripDelete).setOnClickListener(v -> confirmDelete(start, false));
                View header = row.findViewById(R.id.tripHeader);
                View body = row.findViewById(R.id.tripBody);
                LinearLayout details = row.findViewById(R.id.tripDetails);
                LinearLayout route = row.findViewById(R.id.tripRoute);
                final String cardHeading = heading;
                header.setContentDescription("Развернуть поездку: " + cardHeading);
                header.setOnClickListener(v -> {
                    boolean open = body.getVisibility() != View.VISIBLE;
                    body.setVisibility(open ? View.VISIBLE : View.GONE);
                    header.setContentDescription((open ? "Свернуть поездку: " : "Развернуть поездку: ") + cardHeading);
                    if (open && details.getChildCount() == 0) loadDetails(start, t, details, route);
                });
                body.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    int width = Math.min(dp(500), Math.round((right - left) * .475f));
                    if (width > 0 && route.getLayoutParams().width != width) {
                        route.getLayoutParams().width = width;
                        route.requestLayout();
                    }
                });
                tripLogContainer.addView(row);
            }
        } catch (Exception e) { Log.w(TAG, "Invalid trip history", e); }
    }

    private void loadDetails(long start, JSONObject summary, LinearLayout details, LinearLayout route) {
        int generation = renderGeneration;
        details.addView(label("Загрузка статистики…"));
        reader.execute(() -> {
            List<double[]> energy = new ArrayList<>(), track = new ArrayList<>();
            boolean success = false;
            try (Cursor c = getContentResolver().query(Uri.parse("content://" + TripProtocol.AUTHORITY + "/trips/" + start + "/samples"), null, null, null, null)) {
                if (c != null) {
                    while (c.moveToNext()) {
                        String kind = c.getString(0); JSONArray a = new JSONArray(c.getString(1));
                        if (!(kind.equals("energy") && a.length() == 9 || kind.equals("track") && a.length() == 8)) continue;
                        double[] p = new double[a.length()];
                        for (int j = 0; j < p.length; j++) p[j] = a.optDouble(j, Double.NaN);
                        if (kind.equals("energy")) energy.add(p); else track.add(p);
                    }
                    success = true;
                }
            } catch (Exception unavailable) { Log.w(TAG, "Cannot load trip details", unavailable); }
            boolean loaded = success;
            runOnUiThread(() -> {
                if (isDestroyed() || generation != renderGeneration) return;
                details.removeAllViews();
                if (!loaded) { details.addView(label("Не удалось загрузить статистику поездки")); return; }
                showDetails(summary, energy, track, details, route);
            });
        });
    }

    // Package visibility also allows native UI instrumentation to exercise recorded fixtures.
    void showDetails(JSONObject summary, List<double[]> energy, List<double[]> track, LinearLayout details, LinearLayout route) {
        details.removeAllViews(); route.removeAllViews(); route.setVisibility(View.GONE);
        if (energy.size() > 1) {
            addChart(details, new TripChartView(this, energy, false, 0, 0), 0);
            addChart(details, new TripChartView(this, energy, true,
                    summary.optDouble("batteryKwh", 43), summary.optDouble("tankLiters", 56)), 24);
        } else details.addView(label("Подробные данные уровней для этой поездки не записаны"));
        boolean hasSegment = false;
        for (int i = 1; i < track.size(); i++) hasSegment |= TripHistoryPresentation.drawableSegment(track.get(i - 1), track.get(i));
        if (hasSegment) {
            route.setVisibility(View.VISIBLE);
            route.addView(new TripMapView(this, track), new LinearLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
            titleParams.topMargin = dp(12); titleParams.bottomMargin = dp(8);
            route.addView(caption("Средняя скорость · км/ч", 0xffcccccc), titleParams);
            LinearLayout legend = new LinearLayout(this);
            for (int i = 0; i < TripHistoryPresentation.BANDS.length; i++) {
                TextView band = caption("● " + TripHistoryPresentation.BANDS[i], TripHistoryPresentation.COLORS[i]);
                legend.addView(band, new LinearLayout.LayoutParams(0, -2, 1));
            }
            route.addView(legend);
        }
    }
    private void addChart(LinearLayout details, TripChartView chart, int marginTop) {
        LinearLayout group = new LinearLayout(this); group.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams groupParams = new LinearLayout.LayoutParams(-1, -2);
        groupParams.topMargin = dp(marginTop);
        details.addView(group, groupParams);
        group.addView(chart, new LinearLayout.LayoutParams(-1, dp(160)));
        LinearLayout legend = new LinearLayout(this);
        legend.addView(caption("● Электричество", EnergyWidgetStyle.GREEN), new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams fuelParams = new LinearLayout.LayoutParams(-2, -2);
        fuelParams.leftMargin = dp(24);
        legend.addView(caption("● Бензин", EnergyWidgetStyle.BLUE), fuelParams);
        LinearLayout.LayoutParams legendParams = new LinearLayout.LayoutParams(-1, -2);
        legendParams.topMargin = dp(10); legendParams.bottomMargin = dp(6);
        group.addView(legend, legendParams);
        group.addView(caption("Ось X · пробег, км.", 0xffcccccc), new LinearLayout.LayoutParams(-1, -2));
    }
    private TextView caption(String text, int color) {
        TextView view = new TextView(this); view.setTextAppearance(R.style.EnergyCaptionText);
        view.setText(text); view.setTextColor(color); return view;
    }
    private TextView label(String text) {
        TextView view = new TextView(this); view.setTextAppearance(R.style.EnergyLabelText);
        view.setText(text); view.setPadding(0, dp(8), 0, dp(8)); return view;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void consumption(TextView view, double value, String unit, int color) {
        String reading = EnergyWidgetStyle.estimate(value);
        SpannableString text = new SpannableString(reading + " " + unit);
        float unitRatio=getResources().getDimension(R.dimen.energy_unit_text_size)/getResources().getDimension(R.dimen.energy_value_text_size);
        text.setSpan(new RelativeSizeSpan(unitRatio), reading.length() + 1, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        view.setTextAppearance(R.style.EnergyValueText);
        view.setTextColor(color); view.setText(text);
    }
    private static String number(double value, String unit) { return Double.isFinite(value) ? String.format(Locale.getDefault(), "%.1f %s", value, unit) : "—"; }
    private static String distance(double value) {
        return Double.isFinite(value) ? String.format(Locale.getDefault(), value == Math.rint(value) ? "%.0f км" : "%.1f км", value) : "— км";
    }
    @Override protected void onDestroy() { reader.shutdownNow(); super.onDestroy(); }

    /** Подтверждение → broadcast удаления поездки в Native (тот перепишет лог и разошлёт TRIP_UPDATE). */
    private void confirmDelete(long start, boolean all) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.DarkDialog)
                .setTitle(all ? "Удалить все записи" : "Удалить поездку")
                .setMessage(all ? "Удалить все поездки из истории? Действие необратимо." : "Удалить эту поездку из истории? Действие необратимо.")
                .setPositiveButton("Удалить", (d, w) -> {
                    Intent i = new Intent(TripProtocol.DELETE).setPackage(TripProtocol.NATIVE);
                    if (all) i.putExtra(TripProtocol.DELETE_ALL, true);
                    else i.putExtra(TripProtocol.DELETE_START, start);
                    sendBroadcast(i, TripProtocol.PERMISSION);
                    Log.i(TAG, all ? "TRIP_DELETE отправлен для всей истории" : "TRIP_DELETE отправлен start=" + start);
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    /** «1 ч 05 мин» / «45 мин». */
    private static String fmtDurationShort(long ms) {
        long s = ms / 1000, h = s / 3600, m = (s % 3600) / 60;
        return h > 0 ? String.format(Locale.US, "%d ч %02d мин", h, m) : (m + " мин");
    }
}
