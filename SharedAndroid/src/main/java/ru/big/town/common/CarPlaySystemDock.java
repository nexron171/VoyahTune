package ru.big.town.common;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** OEM shortcuts belong to the physical host, so parking the projection also hides this dock. */
public final class CarPlaySystemDock extends LinearLayout {
    private static final String TAG = "CarPlaySystemDock";
    private static final String LAUNCHER = "com.qinggan.app.launcher";
    private final Activity activity;
    private final TextView airQuality;
    private final ContentObserver observer;
    private boolean started;

    public CarPlaySystemDock(Activity activity) {
        super(activity);
        this.activity = activity;
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
        setBackground(null);
        int padding = dp(16);
        setPadding(padding, padding, padding, padding);
        addShortcut("home_icon_bg", "Главный экран", this::openHome);
        addShortcut(
                "vehicle_second_up_air_ac_normal",
                "Климат",
                () ->
                        open(
                                new Intent("com.qinggan.app.vehicle.SHOW_AIR")
                                        .setPackage("com.qinggan.app.vehicle")));
        addShortcut(
                "vehicle_icon_bg",
                "Автомобиль",
                () ->
                        open(
                                new Intent("com.qinggan.app.vehiclesetting.START_VEHICLE")
                                        .setPackage("com.qinggan.app.vehiclesetting")));
        LinearLayout air = new LinearLayout(activity);
        air.setOrientation(VERTICAL);
        air.setGravity(Gravity.CENTER);
        ImageView icon = new ImageView(activity);
        icon.setImageDrawable(oemDrawable("com.qinggan.systemui", "icon_pm25"));
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        air.addView(icon, new LayoutParams(dp(48), dp(32)));
        airQuality = new TextView(activity);
        airQuality.setTextColor(Color.WHITE);
        airQuality.setTextSize(22);
        airQuality.setGravity(Gravity.CENTER);
        air.addView(
                airQuality, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        addView(air, new LayoutParams(dp(100), dp(84)));
        observer =
                new ContentObserver(new Handler(Looper.getMainLooper())) {
                    @Override
                    public void onChange(boolean selfChange) {
                        refresh();
                    }
                };
        setVisibility(GONE);
    }

    public int containerWidth() {
        return dp(132);
    }

    public int stockDockWidth() {
        try {
            Resources resources = activity.getPackageManager().getResourcesForApplication(LAUNCHER);
            int identifier = resources.getIdentifier("navigation_bar_width", "dimen", LAUNCHER);
            if (identifier != 0) return resources.getDimensionPixelSize(identifier);
        } catch (PackageManager.NameNotFoundException | Resources.NotFoundException unavailable) {
            Log.w(TAG, "OEM dock width unavailable", unavailable);
        }
        return dp(145);
    }

    public void start() {
        if (!started) {
            started = true;
            for (String key :
                    new String[] {
                        CarPlayTarget.ASTON_MARTIN_SETTING,
                        CarPlayTarget.FULLSCREEN_SETTING,
                        CarPlayTarget.AIR_QUALITY_SETTING
                    }) {
                activity.getContentResolver()
                        .registerContentObserver(Settings.Global.getUriFor(key), false, observer);
            }
        }
        refresh();
    }

    public void stop() {
        if (started) activity.getContentResolver().unregisterContentObserver(observer);
        started = false;
        setVisibility(GONE);
    }

    private void refresh() {
        boolean enabled =
                started
                        && Settings.Global.getInt(
                                        activity.getContentResolver(),
                                        CarPlayTarget.FULLSCREEN_SETTING,
                                        0)
                                == 1
                        && Settings.Global.getInt(
                                        activity.getContentResolver(),
                                        CarPlayTarget.ASTON_MARTIN_SETTING,
                                        0)
                                == 1;
        setVisibility(enabled ? VISIBLE : GONE);
        int boot =
                Settings.Global.getInt(
                        activity.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        String value =
                airQualityText(
                        Settings.Global.getString(
                                activity.getContentResolver(), CarPlayTarget.AIR_QUALITY_SETTING),
                        boot);
        airQuality.setText(value);
        airQuality.setContentDescription("Качество воздуха PM2.5: " + value);
    }

    public static String airQualityText(String sample, int bootCount) {
        if (sample == null || bootCount < 0) return "—";
        String[] parts = sample.split(":", -1);
        if (parts.length != 2) return "—";
        try {
            int value = Integer.parseInt(parts[1]);
            return Integer.parseInt(parts[0]) == bootCount && value >= 0
                    ? Integer.toString(value)
                    : "—";
        } catch (NumberFormatException invalid) {
            return "—";
        }
    }

    private void addShortcut(String drawable, String label, Runnable action) {
        ImageButton button = new ImageButton(activity);
        button.setImageDrawable(oemDrawable(LAUNCHER, drawable));
        button.setBackground(null);
        button.setPadding(0, 0, 0, 0);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setContentDescription(label);
        button.setOnClickListener(view -> action.run());
        addView(button, new LayoutParams(dp(100), dp(100)));
    }

    private Drawable oemDrawable(String packageName, String name) {
        try {
            Resources resources =
                    activity.getPackageManager().getResourcesForApplication(packageName);
            int identifier = resources.getIdentifier(name, "drawable", packageName);
            if (identifier != 0) return resources.getDrawable(identifier, null);
        } catch (PackageManager.NameNotFoundException | Resources.NotFoundException unavailable) {
            Log.w(TAG, "OEM icon unavailable: " + packageName + "/" + name);
        }
        return null;
    }

    private void openHome() {
        activity.sendBroadcast(new Intent("com.qinggan.navigation.home").setPackage(LAUNCHER));
        int display = displayId();
        open(
                new Intent(Intent.ACTION_MAIN)
                        .setComponent(
                                new ComponentName(
                                        LAUNCHER,
                                        display == 1
                                                ? "com.qinggan.secondlauncher.activity.SecondMainActivity"
                                                : "com.qinggan.app.launcher.activity.MainActivity"))
                        .putExtra("showAllApp", false));
    }

    private void open(Intent intent) {
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(displayId());
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(intent, options.toBundle());
        } catch (RuntimeException unavailable) {
            Log.w(TAG, "OEM shortcut unavailable: " + intent.getAction(), unavailable);
        }
    }

    private int displayId() {
        return getDisplay() == null
                ? activity.getWindowManager().getDefaultDisplay().getDisplayId()
                : getDisplay().getDisplayId();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
