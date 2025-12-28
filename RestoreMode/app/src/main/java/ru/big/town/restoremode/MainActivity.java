package ru.big.town.restoremode;


import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Toast;

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

    private Messenger serviceMessenger;
    private boolean isBound;
    static final int MSG_RESULT = 4;
    static final int MSG_ALLPY_DRIVE_MODES = 1;


    class IncomingHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_RESULT:
                    //Bundle data = msg.getData();
                    //String result = data.getString("result");
                    //Log.i("$$$ IncomingHandler $$$", result);
                    Log.i("$$$ DRIVE MODE $$$", "handleMessage() MSG_RESULT");

                    break;
                default:
                    Log.i("$$$ DRIVE MODE $$$", "handleMessage() default");
                    super.handleMessage(msg);
            }
        }
    }

    final Messenger clientMessenger = new Messenger(new IncomingHandler());

    private ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i("$$$ DRIVE MODE $$$", "onServiceConnected()");
            serviceMessenger = new Messenger(service);
            isBound = true;
//
//            // Register client with service
//            try {
//                Message registerMsg = Message.obtain(null, MSG_ALLPY_DRIVE_MODES);
//                registerMsg.replyTo = clientMessenger;
//                serviceMessenger.send(registerMsg);
//            } catch (RemoteException e) {
//                e.printStackTrace();
//            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceMessenger = null;
            isBound = false;
        }
    };

    private void bindToMessengerService() {
        Log.i("$$$ DRIVE MODE $$$", "bindToMessengerService() begin");

        Intent intent = new Intent();
        intent.setComponent(new ComponentName(
                "ru.big.town.anative",
                "ru.big.town.anative.SetModesService"
        ));
        //intent.setPackage("ru.big.town.anative");
        bindService(intent, connection, Context.BIND_AUTO_CREATE);
        Log.i("$$$ DRIVE MODE $$$", "bindToMessengerService() end");

    }

    private void sendMessageToService() {
        if (!isBound) return;

        try {
            Message msg = Message.obtain(null, MSG_ALLPY_DRIVE_MODES);
//            Bundle data = new Bundle();
//            data.putString("data", "Hello from client");
//            msg.setData(data);
            msg.replyTo = clientMessenger;
            serviceMessenger.send(msg);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        bindToMessengerService();

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
        //sendBroadcast(new Intent("ru.big.town.anative.APPLY_DRIVE_MODES"));
//        Intent intent = new Intent("ru.big.town.anative.APPLY_DRIVE_MODES");
//        intent.setComponent(new ComponentName(
//                "ru.big.town.anative",
//                "ru.big.town.anative.SetModesService"
//        ));
//        intent.setPackage("ru.big.town.anative");

        //sendBroadcast(intent);
        //startService(intent);
        //startForegroundService(intent);
        sendMessageToService();
        Log.i("$$$ TAG $$$", "Click" );
        // isAppInForeground(getApplicationContext(),  "com.qinggan.canbus.service")
    }
    public void onButtonClickClose(View v){
        finish();
    }

    @Override
    protected void onDestroy() {
//        // Unregister client
//        if (isBound && serviceMessenger != null) {
//            try {
//                Message unregisterMsg = Message.obtain(null, MessengerService.MSG_UNREGISTER_CLIENT);
//                serviceMessenger.send(unregisterMsg);
//            } catch (RemoteException e) {
//                e.printStackTrace();
//            }
//        }

        super.onDestroy();
        if (isBound) {
            unbindService(connection);
            isBound = false;
        }
    }
}

