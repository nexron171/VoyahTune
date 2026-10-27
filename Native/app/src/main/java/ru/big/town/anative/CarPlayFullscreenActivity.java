package ru.big.town.anative;

import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import ru.big.town.common.CarPlaySystemDock;
import ru.big.town.common.CarPlayTarget;

/** A distinct physical host lets the OEM launcher hide its dock only for fullscreen CarPlay. */
public final class CarPlayFullscreenActivity extends SplitHostActivity {
    private CarPlaySystemDock systemDock;
    private final ViewTreeObserver.OnGlobalLayoutListener layoutListener =
            this::reportCarPlayHostDisplay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout root = findViewById(R.id.splitHostRoot);
        systemDock = new CarPlaySystemDock(this);
        int dockWidth = systemDock.stockDockWidth();
        ViewCompat.setOnApplyWindowInsetsListener(
                root,
                (view, insets) -> {
                    boolean hidden =
                            Settings.Global.getInt(
                                            getContentResolver(),
                                            CarPlayTarget.HIDE_SYSTEM_BARS_SETTING,
                                            0)
                                    == 1;
                    Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                    int statusId =
                            getResources().getIdentifier("status_bar_height", "dimen", "android");
                    int status = statusId == 0 ? 0 : getResources().getDimensionPixelSize(statusId);
                    view.setPadding(
                            hidden ? 0 : Math.max(dockWidth, bars.left),
                            hidden ? 0 : Math.max(status, bars.top),
                            hidden ? 0 : bars.right,
                            hidden ? 0 : bars.bottom);
                    return WindowInsetsCompat.CONSUMED;
                });
        root.addView(
                systemDock,
                new FrameLayout.LayoutParams(
                        systemDock.containerWidth(),
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.RIGHT | Gravity.CENTER_VERTICAL));
        ViewCompat.requestApplyInsets(root);
        BackButtonService.refreshCarPlayNavigation(this);
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
    }

    @Override
    protected void onDestroy() {
        if (systemDock != null) systemDock.stop();
        getWindow()
                .getDecorView()
                .getViewTreeObserver()
                .removeOnGlobalLayoutListener(layoutListener);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (systemDock != null && !isFinishing()) systemDock.start();
    }

    @Override
    protected void onPause() {
        if (systemDock != null) systemDock.stop();
        super.onPause();
    }

    @Override
    protected boolean usesFullPhysicalDisplay() {
        return true;
    }
}
