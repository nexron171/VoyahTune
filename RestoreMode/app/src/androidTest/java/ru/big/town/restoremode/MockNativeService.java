package ru.big.town.restoremode;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import java.util.*;
import ru.big.town.common.TaskManagerProtocol;

/** Independent test APK process: no OEM calls or vehicle permissions. */
public final class MockNativeService extends Service {
    static final int CONTROL=9000, TRACE=9001, READY=9002;
    private Messenger observer,energy,suspension,apply,tasksClient;
    private final ArrayList<String> tasks=new ArrayList<>(Arrays.asList("mock.navigation","mock.music","mock.podcasts","mock.browser","mock.weather"));
    private final Set<String> pins=new HashSet<>(Collections.singletonList("mock.navigation"));
    private boolean noTaskReply,shortTaskLabels;
    private final ArrayList<Bundle> history=new ArrayList<>();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Messenger endpoint=new Messenger(new Handler(Looper.getMainLooper(),msg->{
        if(msg.what==CONTROL){
            observer=msg.replyTo;
            for(Bundle b:history)send(observer,TRACE,b);history.clear();
            Bundle c=msg.getData();
            if(c.getBoolean("finishApply"))send(apply,4,new Bundle());
            if(c.getBoolean("emit"))emit(c.getInt("schema",8),c.getInt("height",7));
            if(c.getBoolean("die"))handler.postDelayed(()->android.os.Process.killProcess(android.os.Process.myPid()),100);
            if(c.getBoolean("resetTasks")){tasks.clear();tasks.addAll(Arrays.asList("mock.navigation","mock.music","mock.podcasts","mock.browser","mock.weather"));pins.clear();pins.add("mock.navigation");noTaskReply=false;shortTaskLabels=false;}
            if(c.getBoolean("emptyTasks"))tasks.clear();
            if(c.containsKey("noTaskReply"))noTaskReply=c.getBoolean("noTaskReply");
            if(c.containsKey("shortTaskLabels"))shortTaskLabels=c.getBoolean("shortTaskLabels");
            if(c.getBoolean("emitTasks"))emitTasks();
            Bundle result=new Bundle();
            if(c.getBoolean("provider")) {
                android.net.Uri uri=android.net.Uri.parse("content://ru.big.town.restoremode.restoremodecontentprovider/");
                if(c.containsKey("mode")) {
                    android.content.ContentValues values=new android.content.ContentValues();
                    values.put("driveSelectionSource","widget");values.put("driveSelectionMode",c.getString("mode"));
                    result.putInt("changed",getContentResolver().update(uri,values,null,null));
                }
                try(android.database.Cursor cursor=getContentResolver().query(uri,null,null,null,null)) {
                    if(cursor!=null&&cursor.moveToFirst())for(String column:cursor.getColumnNames())result.putString(column,cursor.getString(cursor.getColumnIndexOrThrow(column)));
                }
                try{getContentResolver().call(uri,"otaHealth",null,null);result.putBoolean("healthDenied",false);}
                catch(SecurityException expected){result.putBoolean("healthDenied",true);}
            }
            if(c.containsKey("split"))sendBroadcast(new Intent("ru.big.town.restoremode.SPLIT_RATIO_SAVE")
                .setClassName("ru.big.town.restoremode","ru.big.town.restoremode.SplitRatioSaveReceiver")
                .putExtra("presetId",c.getString("presetId")).putExtra("split",c.getFloat("split")));
            result.putInt("serviceUid",android.os.Process.myUid());
            send(observer,READY,result);return true;
        }
        Bundle record=new Bundle(msg.getData());record.putInt("what",msg.what);record.putInt("arg1",msg.arg1);record.putInt("callerUid",msg.sendingUid);
        if(observer==null)history.add(record);else send(observer,TRACE,record);
        if(msg.what==TaskManagerProtocol.REQUEST){tasksClient=msg.replyTo;emitTasks();}
        if(msg.what==TaskManagerProtocol.PIN){String pkg=msg.getData().getString(TaskManagerProtocol.PACKAGE);if(msg.getData().getBoolean(TaskManagerProtocol.PINNED))pins.add(pkg);else pins.remove(pkg);tasksClient=msg.replyTo;emitTasks();}
        if(msg.what==TaskManagerProtocol.CLOSE){tasks.remove(msg.getData().getString(TaskManagerProtocol.PACKAGE));tasksClient=msg.replyTo;emitTasks();}
        if(msg.what==TaskManagerProtocol.CLOSE_ALL)tasks.removeIf(pkg->!pins.contains(pkg));
        if(msg.what==1)apply=msg.replyTo;
        if(msg.what==100){energy=msg.replyTo;emit(8,7);}
        if(msg.what==101 && Objects.equals(energy,msg.replyTo))energy=null;
        if(msg.what==90){suspension=msg.replyTo;emit(8,7);}
        if(msg.what==91 && Objects.equals(suspension,msg.replyTo))suspension=null;
        if(msg.what==92){suspension=msg.replyTo;emit(8,new int[]{9,7,5,1}[msg.arg1]);}
        return true;
    }));
    static void send(Messenger client,int what,Bundle b){if(client==null)return;try{Message m=Message.obtain(null,what);m.setData(b);client.send(m);}catch(RemoteException ignored){}}
    private void emitTasks(){
        if(noTaskReply)return;
        Bundle data=new Bundle();data.putStringArrayList(TaskManagerProtocol.PACKAGES,new ArrayList<>(tasks));
        ArrayList<String> labels=new ArrayList<>();boolean[] pinned=new boolean[tasks.size()],widgets=new boolean[tasks.size()];
        Map<String,String> names=new HashMap<>();names.put("mock.navigation","Навигация");names.put("mock.music","Музыка");names.put("mock.podcasts","Подкасты");names.put("mock.browser","Браузер");names.put("mock.weather","Погода");
        for(int i=0;i<tasks.size();i++){labels.add(names.get(tasks.get(i)));pinned[i]=pins.contains(tasks.get(i));widgets[i]="mock.navigation".equals(tasks.get(i));}
        data.putStringArrayList(TaskManagerProtocol.LABELS,shortTaskLabels?new ArrayList<>():labels);
        data.putBooleanArray(TaskManagerProtocol.PINNED,pinned);data.putBooleanArray(TaskManagerProtocol.WIDGETS,widgets);
        send(tasksClient,TaskManagerProtocol.LIST,data);
    }
    private void emit(int schema,int height){
        Bundle b=new Bundle();b.putInt("schema",schema);b.putBoolean("connected",true);b.putFloatArray("levels",new float[]{66,44});b.putFloat("batteryKwh",50);b.putFloat("tankLiters",60);send(energy,102,b);
        Bundle s=new Bundle();s.putInt("height",height);s.putInt("maintenance",1);s.putInt("drive",2);s.putInt("pending",-1);s.putBoolean("available",true);s.putString("message","Mock Native");send(suspension,93,s);
    }
    @Override public IBinder onBind(Intent intent){return endpoint.getBinder();}
}
