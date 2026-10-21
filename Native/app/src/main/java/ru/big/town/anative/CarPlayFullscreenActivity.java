package ru.big.town.anative;

import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import ru.big.town.common.InfrastructureProfile;

/** A distinct physical host lets the OEM launcher hide its dock only for fullscreen CarPlay. */
public final class CarPlayFullscreenActivity extends SplitHostActivity {
    private static final String TAG = "CarPlayChrome";
    private static final String REVEAL_SETTING = "voyahtune_carplay_chrome_reveal_until_";
    private static final long REVEAL_DURATION_MS = 10_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable expireReveal = this::cancelReveal;
    private final ViewTreeObserver.OnGlobalLayoutListener layoutListener =
            this::synchronizeHostDisplay;
    private boolean accessEnabled;
    private int hostDisplayId = -1;
    private long revealUntil;
    private View accessButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        accessEnabled = InfrastructureProfile.read(this) == InfrastructureProfile.OD;
        if (accessEnabled) {
            FrameLayout root = findViewById(R.id.splitHostRoot);
            accessButton = getLayoutInflater().inflate(R.layout.carplay_chrome_access, root, false);
            accessButton.setOnClickListener(view -> revealChrome());
            root.addView(accessButton);
            applyAccessRail();
        }
        getWindow()
                .getDecorView()
                .getViewTreeObserver()
                .addOnGlobalLayoutListener(layoutListener);
    }

    private void applyAccessRail() {
        if (!accessEnabled) return;
        int railWidth = getResources().getDimensionPixelSize(R.dimen.carplay_chrome_access_width);
        View panes = findViewById(R.id.splitPanes);
        FrameLayout.LayoutParams paneLayout = (FrameLayout.LayoutParams) panes.getLayoutParams();
        // Resize the VD with its TextureView; translation alone would crop the right edge.
        paneLayout.leftMargin = railWidth;
        panes.setLayoutParams(paneLayout);

        FrameLayout.LayoutParams buttonLayout =
                (FrameLayout.LayoutParams) accessButton.getLayoutParams();
        buttonLayout.width = railWidth;
        buttonLayout.height = getResources().getDimensionPixelSize(R.dimen.carplay_chrome_access_height);
        accessButton.setLayoutParams(buttonLayout);
        View dot = accessButton.findViewById(R.id.carplayChromeAccessDot);
        int dotSize = getResources().getDimensionPixelSize(R.dimen.carplay_chrome_access_dot_size);
        FrameLayout.LayoutParams dotLayout = (FrameLayout.LayoutParams) dot.getLayoutParams();
        dotLayout.width = dotSize;
        dotLayout.height = dotSize;
        dot.setLayoutParams(dotLayout);
    }

    private void synchronizeHostDisplay() {
        reportCarPlayHostDisplay();
        if (!accessEnabled) return;
        Display display = getWindow().getDecorView().getDisplay();
        if (display == null) return;
        int nextDisplayId = display.getDisplayId();
        if (nextDisplayId != 0 && nextDisplayId != 1) nextDisplayId = -1;
        if (hostDisplayId == nextDisplayId) return;
        cancelReveal();
        if (hostDisplayId >= 0) writeRevealDeadline(0L);
        hostDisplayId = nextDisplayId;
        // A new host or destination must not inherit a lease from a retired Activity.
        if (hostDisplayId >= 0) writeRevealDeadline(0L);
    }

    private void revealChrome() {
        synchronizeHostDisplay();
        if (!accessEnabled || hostDisplayId < 0 || !hasWindowFocus()) return;
        long deadline = SystemClock.elapsedRealtime() + REVEAL_DURATION_MS;
        if (!writeRevealDeadline(deadline)) return;
        revealUntil = deadline;
        handler.removeCallbacks(expireReveal);
        handler.postDelayed(expireReveal, REVEAL_DURATION_MS);
    }

    private boolean writeRevealDeadline(long deadline) {
        try {
            return Settings.Global.putLong(
                    getContentResolver(), REVEAL_SETTING + hostDisplayId, deadline);
        } catch (RuntimeException error) {
            Log.w(TAG, "Cannot update temporary chrome visibility", error);
            return false;
        }
    }

    private void cancelReveal() {
        handler.removeCallbacks(expireReveal);
        if (hostDisplayId >= 0 && revealUntil > 0L) {
            try {
                long current = Settings.Global.getLong(
                        getContentResolver(), REVEAL_SETTING + hostDisplayId, 0L);
                if (current == revealUntil) writeRevealDeadline(0L);
            } catch (RuntimeException error) {
                Log.w(TAG, "Cannot clear temporary chrome visibility", error);
            }
        }
        revealUntil = 0L;
    }

    @Override
    protected void onResume() {
        super.onResume();
        synchronizeHostDisplay();
    }

    @Override
    protected void onPause() {
        cancelReveal();
        // The Hiboard anchor can start the same lease outside this Activity.
        if (accessEnabled && hostDisplayId >= 0) writeRevealDeadline(0L);
        super.onPause();
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        synchronizeHostDisplay();
        applyAccessRail();
    }

    @Override
    protected void onDestroy() {
        getWindow().getDecorView().getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
        cancelReveal();
        super.onDestroy();
    }

    @Override
    protected boolean usesFullPhysicalDisplay() {
        return true;
    }
}
