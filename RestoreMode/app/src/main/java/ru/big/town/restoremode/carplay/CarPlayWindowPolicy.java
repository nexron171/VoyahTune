package ru.big.town.restoremode.carplay;

import android.app.Activity;
import android.content.res.Resources;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.View;

import androidx.core.graphics.Insets;

import com.shilapi.xcertplay.CarPlayWindowUi;

import ru.big.town.restoremode.R;

/** Same physical dock/status reservations as VoyahTune, without padding embedded displays. */
public final class CarPlayWindowPolicy implements CarPlayWindowUi.Policy {
    @Override
    public int connectionIcon() {
        return R.drawable.carplay_home_logo;
    }

    @Override
    public boolean darkHostAppearance() {
        return true;
    }

    @Override
    public Insets insets(Activity activity, View view, Insets system) {
        Display display = view.getDisplay();
        if (display == null) display = activity.getWindowManager().getDefaultDisplay();
        if (display.getDisplayId() != 0 && display.getDisplayId() != 1) return system;
        // DiPlay can scale its own UI density. OEM chrome still uses the display's density.
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        Resources resources = activity.getApplicationContext().createDisplayContext(display).getResources();
        int statusId = resources.getIdentifier("status_bar_height", "dimen", "android");
        int status = statusId == 0 ? 0 : resources.getDimensionPixelSize(statusId);
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        int[] safe = CarPlaySafeArea.resolve(display.getDisplayId(), location[0], location[1],
                Math.round(metrics.density * 145f), status,
                system.left, system.top, system.right, system.bottom);
        return Insets.of(safe[0], safe[1], safe[2], safe[3]);
    }

    @Override
    public String authenticationError(Activity activity) {
        return activity.getString(R.string.carplay_authentication_error);
    }
}
