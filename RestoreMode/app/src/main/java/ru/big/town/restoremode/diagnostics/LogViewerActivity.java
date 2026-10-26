package ru.big.town.restoremode.diagnostics;

import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import ru.big.town.restoremode.R;

public class LogViewerActivity extends AppCompatActivity {

    private TextView textLog;

    private ScrollView scrollLog;
    private SystemLogController systemLog;

    private String lastContent = "";
    private boolean firstLoad = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        setContentView(R.layout.activity_logging);
        textLog = findViewById(R.id.textLog);
        scrollLog = findViewById(R.id.scrollLog);

        systemLog = new SystemLogController(this, this::displayLog);
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

    @Override
    protected void onResume() {
        super.onResume();
        systemLog.resume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        systemLog.pause();
    }

    @Override
    protected void onDestroy() {
        if (systemLog != null) {
            systemLog.close();
        }
        super.onDestroy();
    }
}
