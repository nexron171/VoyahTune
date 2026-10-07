package ru.big.town.anative;
import android.app.Service;
import android.content.*;
import android.os.*;
import java.util.ArrayList;

/** Runs under the test APK UID, independently of the instrumented Native process. */
public final class MockRestoreService extends Service {
    private Messenger nativeService;
    private final ArrayList<Message> pending=new ArrayList<>();
    private final Messenger endpoint=new Messenger(new Handler(Looper.getMainLooper(),msg->{
        if(msg.what==9003) {
            sendBroadcast(new Intent(ru.big.town.common.ScenarioProtocol.ACTION_CONFIG)
                    .setClassName("ru.big.town.anative","ru.big.town.anative.ScenarioConfigReceiver")
                    .putExtra(ru.big.town.common.ScenarioProtocol.EXTRA_JSON,msg.getData().getString("json")));
            return true;
        }
        Message copy=Message.obtain(msg);
        if(nativeService==null)pending.add(copy);else forward(copy);
        return true;
    }));
    private void forward(Message msg){try{nativeService.send(msg);}catch(RemoteException e){throw new IllegalStateException(e);}}
    private final ServiceConnection connection=new ServiceConnection(){
        public void onServiceConnected(ComponentName name,IBinder binder){nativeService=new Messenger(binder);for(Message m:pending)forward(m);pending.clear();}
        public void onServiceDisconnected(ComponentName name){nativeService=null;}
    };
    @Override public void onCreate(){super.onCreate();bindService(new Intent().setClassName("ru.big.town.anative","ru.big.town.anative.NativeContractService"),connection,BIND_AUTO_CREATE);}
    @Override public IBinder onBind(Intent intent){return endpoint.getBinder();}
    @Override public void onDestroy(){unbindService(connection);super.onDestroy();}
}
