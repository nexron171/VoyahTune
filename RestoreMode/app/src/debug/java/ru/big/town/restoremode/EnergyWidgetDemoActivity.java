package ru.big.town.restoremode;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import ru.big.town.common.EnergyWidgetSettings;

/** TEMPORARY emulator entry; uses the actual MainActivity, grid, settings and Canvas widgets. */
public final class EnergyWidgetDemoActivity extends MainActivity {
    private EnergyWidgetDemoData data;

    @Override public void onCreate(Bundle savedInstanceState) {
        if(!BuildConfig.DEBUG||!("ranchu".equals(Build.HARDWARE)||"goldfish".equals(Build.HARDWARE)))
            throw new SecurityException("Energy fixture is restricted to Android emulators");
        SharedPreferences prefs=getSharedPreferences("DrivePreferences",Context.MODE_PRIVATE);
        if(!prefs.getBoolean("temporaryEnergyDemoInitialized",false)) {
            SharedPreferences.Editor edit=prefs.edit().putBoolean("temporaryEnergyDemoInitialized",true)
                    .putString("energyCarColor","burgundy").putInt(EnergyWidgetSettings.WINDOW_KEY,75)
                    .putFloat(EnergyWidgetSettings.BATTERY_KEY,43).putFloat(EnergyWidgetSettings.TANK_KEY,56)
                    .putBoolean("fullscreenGrid",false).putInt("tileSpacingDp",4);
            for(String key:new String[]{"showTripTimer","showPowerHold","showWashMode","showAutoLight",
                    "showPedestrian","showBatteryHeat","showSuspensionWidget","showForcedEv",
                    "showSuspensionMaintenance","showVoiceCommand","showLaunchAppsWidget"})edit.putBoolean(key,false);
            List<TileOrderStore.Tile> order=new ArrayList<>();
            for(String id:EnergyWidgetView.IDS){edit.putBoolean("show_"+id,true);
                order.add(new TileOrderStore.Tile(TileOrderStore.Tile.TYPE_WIDGET,id));}
            edit.apply();TileOrderStore.save(prefs,order);
        }
        super.onCreate(savedInstanceState);
        // Automotive's climate/navigation area must not cover the last grid row in this preview.
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarContrastEnforced(false);
        getWindow().getDecorView().post(()->{
            WindowInsetsController controller=getWindow().getInsetsController();
            if(controller!=null){controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.systemBars());}
        });
        FrameLayout content=findViewById(android.R.id.content);
        TextView badge=new TextView(this);badge.setText("ДЕМО\nЭмулятор");badge.setTextSize(14);
        badge.setTextColor(Color.rgb(225,196,132));badge.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams badgePosition=new FrameLayout.LayoutParams(dp(125),dp(60),Gravity.TOP|Gravity.START);
        badgePosition.leftMargin=dp(10);badgePosition.topMargin=dp(85);content.addView(badge,badgePosition);
        Button settings=new Button(this);settings.setText("Настройки");settings.setAllCaps(false);settings.setTextSize(12);
        settings.setOnClickListener(v->startActivity(new Intent(this,AdvanceActivity.class)));
        FrameLayout.LayoutParams position=new FrameLayout.LayoutParams(dp(125),dp(48),Gravity.BOTTOM|Gravity.START);
        position.leftMargin=dp(10);position.bottomMargin=dp(24);content.addView(settings,position);
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    @Override protected Bundle energyWidgetPreviewState() {
        if(data==null)data=new EnergyWidgetDemoData();
        SharedPreferences p=getSharedPreferences("DrivePreferences",Context.MODE_PRIVATE);
        return data.snapshot(EnergyWidgetPreferences.capacity(p,EnergyWidgetSettings.BATTERY_KEY,43),
                EnergyWidgetPreferences.capacity(p,EnergyWidgetSettings.TANK_KEY,56));
    }
}
