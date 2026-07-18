package ru.big.town.restoremode;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Экран «Логирование»: тумблер записи логов Native в файл (/sdcard/tmp), живая лента
 * (опрос снимка у Native раз в секунду) и «Выгрузить логи» (share файла из Native).
 * Управление и файл — на стороне Native (там всё логирование). См. SetModesService
 * MSG_LOGGING_ENABLE/SHARE + ACTION_REQUEST_LOG/LOG_UPDATE.
 */
public class LoggingActivity extends AppCompatActivity {

    private static final String TAG = "$$$ LoggingActivity $$$";
    private static final String ACTION_REQUEST_LOG   = "ru.big.town.anative.REQUEST_LOG";
    private static final String ACTION_LOG_UPDATE    = "ru.big.town.anative.LOG_UPDATE";
    private static final String ACTION_LOGGING_SET   = "ru.big.town.anative.LOGGING_SET";
    private static final String ACTION_LOGGING_SHARE = "ru.big.town.anative.LOGGING_SHARE";
    private static final String NATIVE_PKG = "ru.big.town.anative";
    private static final long POLL_MS = 1000L;

    private SharedPreferences prefs;
    private Switch switchLogging;
    private TextView textLog, textLogPath;
    private ScrollView scrollLog;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private String lastContent = "";
    private boolean firstLoad = true;

    private final BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String log = intent.getStringExtra("log");
            String path = intent.getStringExtra("path");
            if (path != null) textLogPath.setText("Файл: " + path);
            if (log == null || log.equals(lastContent)) return; // нет изменений — не трогаем скролл

            // Якорь — расстояние от НИЗА (для tail-лога стабильно: переживает и дозапись снизу,
            // и выпадение старых строк сверху из кольцевого буфера). У низа → следуем за хвостом.
            int contentH = (scrollLog.getChildCount() > 0) ? scrollLog.getChildAt(0).getHeight() : 0;
            int viewportH = scrollLog.getHeight();
            final int prevFromBottom = Math.max(0, contentH - scrollLog.getScrollY() - viewportH);
            final boolean follow = firstLoad || prevFromBottom <= dp(12);
            lastContent = log;
            firstLoad = false;
            textLog.setText(log);
            scrollLog.post(() -> {
                if (follow) { scrollLog.fullScroll(View.FOCUS_DOWN); return; }
                int newH = (scrollLog.getChildCount() > 0) ? scrollLog.getChildAt(0).getHeight() : 0;
                int y = Math.max(0, newH - scrollLog.getHeight() - prevFromBottom);
                scrollLog.scrollTo(0, y);
            });
        }
    };

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            requestSnapshot();
            uiHandler.postDelayed(this, POLL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        setContentView(R.layout.activity_logging);

        prefs = getSharedPreferences("DrivePreferences", MODE_PRIVATE);
        switchLogging = findViewById(R.id.switchLogging);
        textLog       = findViewById(R.id.textLog);
        textLogPath   = findViewById(R.id.textLogPath);
        scrollLog     = findViewById(R.id.scrollLog);

        switchLogging.setChecked(prefs.getBoolean("loggingEnabled", false));
        switchLogging.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean("loggingEnabled", checked).apply();
            Intent i = new Intent(ACTION_LOGGING_SET).setPackage(NATIVE_PKG);
            i.putExtra("on", checked);
            sendBroadcast(i);
        });
    }

    public void onButtonBackLogging(View v) { finish(); }

    /** «Выгрузить логи» → Native открывает share-чузер с лог-файлом. */
    public void onButtonShareLog(View v) {
        sendBroadcast(new Intent(ACTION_LOGGING_SHARE).setPackage(NATIVE_PKG));
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void requestSnapshot() {
        Intent i = new Intent(ACTION_REQUEST_LOG);
        i.setPackage("ru.big.town.anative");
        sendBroadcast(i);
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(logReceiver, new IntentFilter(ACTION_LOG_UPDATE), RECEIVER_EXPORTED);
        uiHandler.removeCallbacks(poll);
        uiHandler.post(poll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(logReceiver); } catch (Exception ignored) {}
        uiHandler.removeCallbacks(poll);
    }
}
