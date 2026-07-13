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
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.RadioGroup;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;


public class MainActivity extends AppCompatActivity {
    private String driveMode="INDIVIDUAL";
    private String energy="SREV";
    private  String recycle="LOW";
    private String StarButton="";
    private int StarButtonCount=1;

    private String StarButtonStarButton1="";
    private String StarButtonStarButton2="";

    private String customCommand="";
    private int customCommandCount=1;

    private SharedPreferences sharedPreferences;
    static final int MSG_RESULT             = 4;
    static final int MSG_APPLY_DRIVE_MODES  = 1;
    static final int REQUEST_CODE           = 1;
    private Intent resultIntent=null;
    private Intent resultIntentStarButton=null;
    private SharedPreferences.Editor editor=null;

    // Блокировка «Применить» + прогрессбар на время цикла отправки
    private Button buttonApply = null;
    private ProgressBar applyProgress = null;
    private boolean applying = false;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable applyTimeout = () -> setApplying(false);

    static final String TAG = "$$$ MainActivityRestoreMode $$$";


    // Handling result
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE && resultCode == RESULT_OK) {
            Log.i("onActivityResult",String.format("requestCode - %d resultCode - %d data %s",requestCode,resultCode,data.toString()));

            customCommand      = data.getStringExtra("customCommand");
            customCommandCount = data.getIntExtra("customCommandCount", 1);

            Log.i("onActivityResult", String.format(
                    "customCommand=%s count=%d", customCommand, customCommandCount));

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
                    Log.i(TAG, "handleMessage() MSG_RESULT");
                    setApplying(false);   // цикл отправки завершён — разблокируем кнопку
                    break;
                default:
                    Log.i(TAG, "handleMessage() default");
                    super.handleMessage(msg);
            }
        }
    }

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
        bindService(intent, connection, Context.BIND_AUTO_CREATE);
        Log.i(TAG, "bindToMessengerService() end");

    }

    public boolean sendMessageToService(int message) {
        if (!GlobalVars.isBound || GlobalVars.serviceMessenger == null) return false;

        try {
            Message msg = Message.obtain(null, message);
            msg.replyTo = GlobalVars.clientMessenger;
            GlobalVars.serviceMessenger.send(msg);
            return true;
        } catch (RemoteException e) {
            e.printStackTrace();
            return false;
        }
    }

    /** Блокирует «Применить» и показывает прогрессбар, пока идёт цикл отправки. */
    private void setApplying(boolean on) {
        applying = on;
        if (buttonApply != null) buttonApply.setEnabled(!on);
        if (applyProgress != null) applyProgress.setVisibility(on ? View.VISIBLE : View.GONE);
        uiHandler.removeCallbacks(applyTimeout);
        if (on) uiHandler.postDelayed(applyTimeout, 12000); // страховка, если MSG_RESULT не придёт
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

        sharedPreferences = getSharedPreferences("DrivePreferences", Context.MODE_PRIVATE);
        GlobalVars.sharedPreferences=sharedPreferences;

        buttonApply   = findViewById(R.id.button);
        applyProgress = findViewById(R.id.applyProgress);

        editor = sharedPreferences.edit();
        GlobalVars.editor=editor;

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
            customCommand=cursor.getString(3);
            customCommandCount=cursor.getInt(4);
            Log.i("$$$ getModes() $$$", "Query Result:" +
                    "\ndriveMode: " + driveMode +
                    "\nenergy: " + energy +
                    "\nrecycle: " + recycle
            );
        }
        cursor.close();    }

    public void onButtonClickApply(View v){
        if (applying) return;                       // уже идёт цикл — игнорируем
        if (sendMessageToService(MSG_APPLY_DRIVE_MODES)) {
            setApplying(true);                      // блок кнопки + прогресс до MSG_RESULT
        }
        Log.i(TAG, "Click" );
    }
    public void onButtonClickClose(View v){
        finish();
    }

    public void onButtonClickAdvance(View v){
        getModes();
        initIntent();
        Log.i("$$$ Main onButtonClickAdvance $$$", String.format("%s %d", StarButton, StarButtonCount));
        startActivityForResult(resultIntent,REQUEST_CODE);
    }
    public void onButtonClickAdvanceStarButton(View v){
        getModes();
        initIntentStarButton();
        Log.i("$$$ Main onButtonClickAdvanceStarButton $$$", String.format("%s %s", StarButtonStarButton1, StarButtonStarButton2));
        startActivity(resultIntentStarButton);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        uiHandler.removeCallbacks(applyTimeout);
        if (GlobalVars.isBound) {
            unbindService(connection);
            GlobalVars.isBound = false;
        }
    }
}
