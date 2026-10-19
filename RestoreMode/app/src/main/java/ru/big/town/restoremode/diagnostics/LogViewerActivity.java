package ru.big.town.restoremode.diagnostics;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import ru.big.town.common.InfrastructureProfile;
import ru.big.town.restoremode.R;

public class LogViewerActivity extends AppCompatActivity {
    private static final String ACTION_REQUEST_LOG = "ru.big.town.anative.REQUEST_LOG";
    private static final String ACTION_LOG_UPDATE = "ru.big.town.anative.LOG_UPDATE";
    private static final String ACTION_LOGGING_SET = "ru.big.town.anative.LOGGING_SET";
    private static final String ACTION_LOGGING_SHARE = "ru.big.town.anative.LOGGING_SHARE";
    private static final String NATIVE_PACKAGE = "ru.big.town.anative";
    private static final long POLL_INTERVAL_MS = 1000;

    private TextView textLog;
    private TextView textLogPath;
    private ScrollView scrollLog;
    private SystemLogController systemLog;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String lastContent = "";
    private boolean firstLoad = true;

    private final BroadcastReceiver logReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String path = intent.getStringExtra("path");
                    if (path != null) {
                        textLogPath.setText("Файл: " + path);
                    }
                    displayLog(intent.getStringExtra("log"));
                }
            };

    private final Runnable pollNative =
            new Runnable() {
                @Override
                public void run() {
                    sendBroadcast(new Intent(ACTION_REQUEST_LOG).setPackage(NATIVE_PACKAGE));
                    handler.postDelayed(this, POLL_INTERVAL_MS);
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        boolean systemLogging = InfrastructureProfile.read(this) == InfrastructureProfile.OD;
        setContentView(
                systemLogging ? R.layout.activity_logging : R.layout.activity_native_logging);
        textLog = findViewById(R.id.textLog);
        textLogPath = findViewById(R.id.textLogPath);
        scrollLog = findViewById(R.id.scrollLog);
        if (systemLogging) {
            systemLog = new SystemLogController(this, this::displayLog);
        } else {
            SharedPreferences preferences = getSharedPreferences("DrivePreferences", MODE_PRIVATE);
            Switch loggingSwitch = findViewById(R.id.switchLogging);
            loggingSwitch.setChecked(preferences.getBoolean("loggingEnabled", false));
            loggingSwitch.setOnCheckedChangeListener(
                    (button, checked) -> {
                        preferences.edit().putBoolean("loggingEnabled", checked).apply();
                        Intent request = new Intent(ACTION_LOGGING_SET).setPackage(NATIVE_PACKAGE);
                        request.putExtra("on", checked);
                        sendBroadcast(request);
                    });
        }
    }

    private void displayLog(String log) {
        if (log == null || log.equals(lastContent)) {
            return;
        }
        // Anchor to the bottom while the oldest lines leave the bounded live view.
        int contentHeight = scrollLog.getChildCount() > 0 ? scrollLog.getChildAt(0).getHeight() : 0;
        int distanceFromBottom =
                Math.max(0, contentHeight - scrollLog.getScrollY() - scrollLog.getHeight());
        boolean follow =
                firstLoad
                        || distanceFromBottom
                                <= Math.round(12 * getResources().getDisplayMetrics().density);
        lastContent = log;
        firstLoad = false;
        textLog.setText(log);
        scrollLog.post(
                () -> {
                    if (follow) {
                        scrollLog.fullScroll(View.FOCUS_DOWN);
                    } else {
                        int newHeight =
                                scrollLog.getChildCount() > 0
                                        ? scrollLog.getChildAt(0).getHeight()
                                        : 0;
                        scrollLog.scrollTo(
                                0,
                                Math.max(
                                        0, newHeight - scrollLog.getHeight() - distanceFromBottom));
                    }
                });
    }

    public void onButtonBackLogging(View view) {
        finish();
    }

    public void onButtonShareLog(View view) {
        sendBroadcast(new Intent(ACTION_LOGGING_SHARE).setPackage(NATIVE_PACKAGE));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (systemLog != null) {
            systemLog.resume();
            return;
        }
        registerReceiver(logReceiver, new IntentFilter(ACTION_LOG_UPDATE), RECEIVER_EXPORTED);
        handler.removeCallbacks(pollNative);
        handler.post(pollNative);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (systemLog != null) {
            systemLog.pause();
            return;
        }
        try {
            unregisterReceiver(logReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        handler.removeCallbacks(pollNative);
    }

    @Override
    protected void onDestroy() {
        if (systemLog != null) {
            systemLog.close();
        }
        super.onDestroy();
    }
}
