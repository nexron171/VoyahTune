package ru.big.town.restoremode;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
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

/** Отдельный экран истории поездок (лог последних 10). Данные — broadcast TRIP_UPDATE из Native. */
public class TripHistoryActivity extends AppCompatActivity {

    private static final String TAG = "$$$ TripHistory $$$";
    private static final String ACTION_TRIP_DELETE = "ru.big.town.anative.TRIP_DELETE";
    private LinearLayout tripLogContainer;
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("dd.MM  HH:mm", Locale.getDefault());

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
        registerReceiver(tripReceiver, new IntentFilter(MainActivity.ACTION_TRIP_UPDATE), RECEIVER_EXPORTED);
        Intent req = new Intent(MainActivity.ACTION_REQUEST_TRIP_UPDATE);
        req.setPackage("ru.big.town.anative");
        sendBroadcast(req);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(tripReceiver); } catch (Exception ignored) {}
    }

    private void renderTripLog(String json) {
        if (tripLogContainer == null) return;
        tripLogContainer.removeAllViews();
        try {
            JSONArray arr = new JSONArray(json == null ? "[]" : json);
            if (arr.length() == 0) {
                TextView empty = new TextView(this);
                empty.setText("Поездок пока нет");
                empty.setTextColor(0xff888888);
                empty.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 24f);
                empty.setPadding(8, 8, 8, 8);
                tripLogContainer.addView(empty);
                return;
            }
            LayoutInflater inf = LayoutInflater.from(this);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject t = arr.getJSONObject(i);
                final long start = t.getLong("start");
                View row = inf.inflate(R.layout.item_trip, tripLogContainer, false);
                ((TextView) row.findViewById(R.id.tripDate))
                        .setText(dateFmt.format(new Date(start)));
                ((TextView) row.findViewById(R.id.tripDuration))
                        .setText(fmtDurationShort(t.getLong("durationMs")));
                ImageButton del = row.findViewById(R.id.tripDelete);
                if (del != null) del.setOnClickListener(v -> confirmDelete(start));
                tripLogContainer.addView(row);
            }
        } catch (Exception e) {
            Log.w(TAG, "renderTripLog: " + e.getMessage());
        }
    }

    /** Подтверждение → broadcast удаления поездки в Native (тот перепишет лог и разошлёт TRIP_UPDATE). */
    private void confirmDelete(long start) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.DarkDialog)
                .setTitle("Удалить поездку")
                .setMessage("Удалить эту поездку из истории? Действие необратимо.")
                .setPositiveButton("Удалить", (d, w) -> {
                    Intent i = new Intent(ACTION_TRIP_DELETE);
                    i.setPackage("ru.big.town.anative");
                    i.putExtra("deleteStart", start);
                    sendBroadcast(i);
                    Log.i(TAG, "TRIP_DELETE отправлен start=" + start);
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
