package ru.big.town.restoremode;


import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.RadioButton;
import android.widget.RadioGroup;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private RadioGroup radioGroupDriveModes, radioGroupEnergy, RadioGroupRecyles;
    private String driveMode="INDIVIDUAL";
    private String energy="SREV";
    private  String recycle="LOW";

    private SharedPreferences sharedPreferences;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_main);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
        radioGroupDriveModes = findViewById(R.id.drive_modes_group);
        radioGroupDriveModes.setOnCheckedChangeListener(powerModeListener());
        radioGroupEnergy = findViewById(R.id.energy_modes_group);
        radioGroupEnergy.setOnCheckedChangeListener(powerModeListener());
        radioGroupDriveModes = findViewById(R.id.recycle_modes_group);
        radioGroupDriveModes.setOnCheckedChangeListener(powerModeListener());

        getModes();
        initRadioButonDriveMode(driveMode);
        initRadioButonEnergy(energy);
        initRadioButonRecycle(recycle);
    }
    public void initRadioButonDriveMode(String driveMode){
        switch (driveMode) {
            case ("ECO"):
                ((RadioButton)findViewById(R.id.ECO)).setChecked(true);
                break;
            case ("COMFORT"):
                ((RadioButton)findViewById(R.id.COMFORT)).setChecked(true);
                break;
            case ("SPORT"):
                ((RadioButton)findViewById(R.id.SPORT)).setChecked(true);;
                break;
            case ("OUTING"):
                ((RadioButton)findViewById(R.id.OUTING)).setChecked(true);
                break;
            case ("SNOW"):
                ((RadioButton)findViewById(R.id.SNOW)).setChecked(true);
                break;
            case ("INDIVIDUAL"):
                ((RadioButton)findViewById(R.id.INDIVIDUAL)).setChecked(true);
                break;
        }
    }
    public void initRadioButonEnergy(String energy){
        switch (energy) {
            case ("SMART"):
                ((RadioButton)findViewById(R.id.SMART)).setChecked(true);
                break;
            case ("EV"):
                ((RadioButton)findViewById(R.id.EV)).setChecked(true);
                break;
            case ("REV"):
                ((RadioButton)findViewById(R.id.REV)).setChecked(true);;
                break;
            case ("SREV"):
                ((RadioButton)findViewById(R.id.SREV)).setChecked(true);;
                break;

        }
    }

    public void initRadioButonRecycle(String recycle){
        switch (recycle) {
            case ("LOW"):
                ((RadioButton)findViewById(R.id.LOW)).setChecked(true);
                break;
            case ("MEDIUM"):
                ((RadioButton)findViewById(R.id.MEDIUM)).setChecked(true);
                break;
            case ("HIGH"):
                ((RadioButton)findViewById(R.id.HIGH)).setChecked(true);;
                break;
        }
    }
    private void getModes(){

        Cursor cursor = getContentResolver().query(Uri
                        .parse("content://ru.big.town.restoremode.restoremodecontentprovider/"),
                null, null,
                null, null);
        if(cursor.getCount() != 0){
            cursor.moveToFirst();
            driveMode=cursor.getString(0);
            energy=cursor.getString(1);
            recycle=cursor.getString(2);

            Log.i("$$$ Restore Mode on Create $$$", "Query Result:" +
                    "\ndriveMode: " + driveMode +
                    "\nenergy: " + energy +
                    "\nrecycle: " + recycle
            );
        }
        cursor.close();    }
    private RadioGroup.OnCheckedChangeListener powerModeListener() {
        return new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                sharedPreferences = getSharedPreferences("DrivePreferences", Context.MODE_PRIVATE);

                if(sharedPreferences==null){
                    Log.w("###$$$$$","PIZDEC");} else {Log.w("###$$$$$","HUYNY");}

                SharedPreferences.Editor editor = sharedPreferences.edit();

                RadioButton radioButton = findViewById(checkedId);
                if(group.getId() == R.id.drive_modes_group) {
                    editor.putString("driveMode",radioButton.getTag().toString());
                    editor.apply();
                    Log.i("$$$ DRIVE MODE $$$", radioButton.getTag().toString());
                }
                if(group.getId() == R.id.energy_modes_group) {
                    editor.putString("energy",radioButton.getTag().toString());
                    editor.apply();
                    Log.i("$$$ ENERGY MODE $$$", radioButton.getTag().toString());
                }
                if(group.getId() == R.id.recycle_modes_group) {
                    editor.putString("recycle",radioButton.getTag().toString());
                    editor.apply();
                    Log.i("$$$ RECYCLE MODE $$$", radioButton.getTag().toString());
                }
            }
        };
    }
    public void onButtonClickApply(View v){
        sendBroadcast(new Intent("ru.big.town.anative.APPLY_DRIVE_MODES"));
        Log.i("$$$ TAG $$$", "Click" );
        // isAppInForeground(getApplicationContext(),  "com.qinggan.canbus.service")
    }
    public void onButtonClickClose(View v){
        finish();
    }
}

