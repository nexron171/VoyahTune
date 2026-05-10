package ru.big.town.restoremode;


import static android.view.View.GONE;
import static android.view.View.VISIBLE;

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
import android.widget.CheckBox;
import android.widget.RadioButton;
import android.widget.RadioGroup;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;


public class MainActivity extends AppCompatActivity {
    private RadioGroup radioGroupDriveModes, radioGroupEnergy, RadioGroupRecyles;
    private String driveMode="INDIVIDUAL";
    private String energy="SREV";
    private  String recycle="LOW";
    private String StarButton="";
    private int StarButtonCount=1;

    private String StarButtonStarButton1="";
    private String StarButtonStarButton2="";

    private SharedPreferences sharedPreferences;
    //private boolean isBound;
    static final int MSG_RESULT = 4;
    static final int MSG_APPLY_DRIVE_MODES = 1;
    static final int REQUEST_CODE=1;
    private Intent resultIntent=null;
    private Intent resultIntentStarButton=null;
    private SharedPreferences.Editor editor=null;
    private CheckBox checkBox34 = null;

    static final String TAG = "$$$ MainActivityRestoreMode $$$";


    // Handling result
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE && resultCode == RESULT_OK) {
            Log.i("onActivityResult",String.format("requestCode - %d resultCode - %d data %s",requestCode,resultCode,data.toString()));

            StarButton = data.getStringExtra("StarButton");
            StarButtonCount = data.getIntExtra("StarButtonCount",1);
            Log.i("onActivityResult",String.format("StarButton - %s StarButtonCount - %d ",StarButton, StarButtonCount));

            editor.putString("StarButton", StarButton);
            editor.putInt("StarButtonCount", StarButtonCount);
            editor.apply();
        }
    }

    class IncomingHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_RESULT:
                    //Bundle data = msg.getData();
                    //String result = data.getString("result");
                    //Log.i("$$$ IncomingHandler $$$", result);
                    Log.i(TAG, "handleMessage() MSG_RESULT");

                    break;
                default:
                    Log.i(TAG, "handleMessage() default");
                    super.handleMessage(msg);
            }
        }
    }

    //final Messenger clientMessenger = new Messenger(new IncomingHandler());
    private ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i(TAG, "onServiceConnected()");
            GlobalVars.serviceMessenger = new Messenger(service);
            GlobalVars.isBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            GlobalVars.serviceMessenger = null;
            GlobalVars.isBound = false;
        }
    };

    private void bindToMessengerService() {
        Log.i(TAG, "bindToMessengerService() begin");

        Intent intent = new Intent();
        intent.setComponent(new ComponentName(
                "ru.big.town.anative",
                "ru.big.town.anative.SetModesService"
        ));
        //intent.setPackage("ru.big.town.anative");
        bindService(intent, connection, Context.BIND_AUTO_CREATE);
        Log.i(TAG, "bindToMessengerService() end");

    }

    public void sendMessageToService(int message) {
        if (!GlobalVars.isBound) return;

        try {
            Message msg = Message.obtain(null, message);
//            Bundle data = new Bundle();
//            data.putString("data", "Hello from client");
//            msg.setData(data);
            msg.replyTo = GlobalVars.clientMessenger;
            GlobalVars.serviceMessenger.send(msg);
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

        sharedPreferences = getSharedPreferences("DrivePreferences", Context.MODE_PRIVATE);
        GlobalVars.sharedPreferences=sharedPreferences;

        checkBox34 = findViewById(R.id.checkBox34);


        if(sharedPreferences==null){
            Log.w("###$$$$$","PIZDEC");} else {Log.w("###$$$$$","HUYNY");}

        editor = sharedPreferences.edit();
        GlobalVars.editor=editor;

        initCheckBox34();

        getModes();
        initRadioButonDriveMode(driveMode);
        initRadioButonEnergy(energy);
        initRadioButonRecycle(recycle);
        initIntent();
        GlobalVars.clientMessenger = new Messenger(new IncomingHandler());
    }
    public void initIntent(){
        if(resultIntent==null){
            resultIntent = new Intent(this, AdvanceActivity.class);
        }
        
        resultIntent.putExtra("StarButton", StarButton);
        resultIntent.putExtra("StarButtonCount", StarButtonCount);
    }
    public void initIntentStarButton(){
        if(resultIntentStarButton==null){
            resultIntentStarButton = new Intent(this, AdvanceActivityStarButton.class);
        }

        resultIntent.putExtra("StarButtonStarButton1", StarButtonStarButton1);
        resultIntent.putExtra("StarButtonStarButton2", StarButtonStarButton2);
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
            StarButton=cursor.getString(3);
            StarButtonCount=cursor.getInt(4);
            StarButtonStarButton1=cursor.getString(5);
            StarButtonStarButton2=cursor.getString(6);



            Log.i("$$$ getModes() $$$", "Query Result:" +
                    "\ndriveMode: " + driveMode +
                    "\nenergy: " + energy +
                    "\nrecycle: " + recycle +
                    "\nStarButton: " + StarButton +
                    "\nStarButtonCount: " + StarButtonCount +
                    "\nStarButtonStarButton1: " + StarButtonStarButton1 +
                    "\nStarButtonStarButton2: " + StarButtonStarButton2
            );
        }
        cursor.close();    }
    private RadioGroup.OnCheckedChangeListener powerModeListener() {
        return new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {

                RadioButton radioButton = findViewById(checkedId);
                if(group.getId() == R.id.drive_modes_group) {
                    editor.putString("driveMode",radioButton.getTag().toString());
                    editor.apply();
                    Log.i(TAG, radioButton.getTag().toString());
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
        sendMessageToService(MSG_APPLY_DRIVE_MODES);
        Log.i(TAG, "Click" );
        // isAppInForeground(getApplicationContext(),  "com.qinggan.canbus.service")
    }
    public void onButtonClickClose(View v){
        finish();
    }

    public void onButtonClickAdvance(View v){
        //startActivity(new Intent(this, AdvanceActivity.class));
        getModes();
        initIntent();
        Log.i("$$$ Main onButtonClickAdvance $$$", String.format("%s %d", StarButton, StarButtonCount));
        startActivityForResult(resultIntent,REQUEST_CODE);
    }
    public void onButtonClickAdvanceStarButton(View v){
        //startActivity(new Intent(this, AdvanceActivity.class));
        getModes();
        initIntentStarButton();
        Log.i("$$$ Main onButtonClickAdvanceStarButton $$$", String.format("%s %s", StarButtonStarButton1, StarButtonStarButton2));
        startActivity(resultIntentStarButton);
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
        if (GlobalVars.isBound) {
            unbindService(connection);
            GlobalVars.isBound = false;
        }
    }

    private void initCheckBox34(){
        boolean checkBox34State=sharedPreferences.getBoolean("checkBox34", false);
        checkBox34.setChecked(checkBox34State);
        Log.i("$$$ initCheckBox34 $$$", checkBox34.isChecked()?"true":"false");
        setCheckBox34State();
        Log.i("$$$ 2 initCheckBox34 $$$", checkBox34State?"true":"false");

    }
    public void onCheckBox34Click(View v){
        setCheckBox34State();
    }
    public void setCheckBox34State(){

        RadioButton smart = findViewById(R.id.SMART);

        if(checkBox34.isChecked()){
            smart.setVisibility(GONE);
            checkBox34.setText("4 кнопки");
            editor.putBoolean("checkBox34",true);
        } else {
            smart.setVisibility(VISIBLE);
            checkBox34.setText("3 кнопки");
            editor.putBoolean("checkBox34",false);
        }
        editor.commit();
        Log.i("$$$ onCheckBox34Click $$$", checkBox34.isChecked()?"true":"false");
    }

}

