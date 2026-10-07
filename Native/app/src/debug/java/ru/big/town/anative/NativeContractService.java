package ru.big.town.anative;

import android.content.Intent;
import android.os.*;
import android.view.*;
import java.util.*;
import ru.big.town.common.*;

/** Real Binder decoder/controllers with debug-only vehicle and Android-operation ports. */
public final class NativeContractService extends SetModesService {
    public static final int CONTROL=9000, TRACE=9001, READY=9002;
    private Messenger observer;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final IncomingHandler decoder=new IncomingHandler();
    private final Messenger endpoint=new Messenger(new Handler(Looper.getMainLooper(), msg -> {
        if(msg.what==CONTROL) {
            observer=msg.replyTo;
            if(msg.getData().getBoolean("reset"))reset();
            this.vehicle.configure(msg.getData());
            this.scenarios.configure(msg.getData());
            send(observer,READY,new Bundle());
        } else {
            Bundle b=new Bundle(msg.getData());b.putString("op","request");b.putInt("what",msg.what);
            b.putInt("arg1",msg.arg1);b.putInt("callerUid",msg.sendingUid);trace(b);
            decoder.handleMessage(msg);
        }
        return true;
    }));
    private MockVehicle vehicle;
    private MockScenarioEnvironment scenarios;
    private final List<WidgetSupport.RunningApp> taskApps = new ArrayList<>();
    private final Set<String> taskPins = new HashSet<>();
    private EnergyWidgetController energy;
    private SuspensionWidgetController suspension;
    private VoiceCommandController voice;
    private ClearAppsController cleanup;
    @Override public void onCreate() { reset(); }
    private void reset() {
        closeScenarios();
        if(scenarios!=null)scenarios.close();
        if(energy!=null)energy.close();if(suspension!=null)suspension.close();if(voice!=null)voice.close();
        if(vehicle!=null)vehicle.close();
        if(cleanup!=null)cleanup.close();
        vehicle=new MockVehicle(this::trace);
        taskApps.clear(); taskPins.clear();
        taskApps.add(new WidgetSupport.RunningApp("mock.navigation", "Навигация", 1, true));
        taskApps.add(new WidgetSupport.RunningApp("mock.music", "Музыка", 2, false));
        taskApps.add(new WidgetSupport.RunningApp("mock.podcasts", "Подкасты", 3, false));
        taskPins.add("mock.navigation");
        getSharedPreferences("NativePrefs",0).edit().remove(ScenarioProtocol.KEY_SCENARIOS).commit();
        scenarios=new MockScenarioEnvironment(this,vehicle,this::trace);
        initializeScenarios(new ScenarioEngine(scenarios));
        final android.net.Uri mockStore=android.net.Uri.parse("content://ru.big.town.anative.test.restore/");
        final boolean remoteStore=getPackageManager().resolveContentProvider(mockStore.getAuthority(),0)!=null;
        if(remoteStore)getContentResolver().call(mockStore,"reset",null,null);
        energy=new EnergyWidgetController(this,vehicle);
        suspension=new SuspensionWidgetController(this,vehicle,new SuspensionWidgetController.DriveStore() {
            private DriveSelectionPolicy saved=new DriveSelectionPolicy("COMFORT","","COMFORT", "COMFORT");
            public DriveSelectionPolicy read(){return remoteStore?DriveSelectionStore.read(NativeContractService.this,mockStore):saved;}
            public boolean record(String mode){operation("saveDrive",mode,0);return !remoteStore||DriveSelectionStore.record(NativeContractService.this,mockStore,mode,DriveSelectionPolicy.WIDGET);}
        }, (command,completed)->{command.run();completed.run();},600);
        voice=new VoiceCommandController(this);
        cleanup=new ClearAppsController(()->{
            int before=taskApps.size(); taskApps.removeIf(app -> !taskPins.contains(app.packageName));
            Bundle b=new Bundle();b.putInt(SystemWidgetProtocol.SUCCEEDED,before-taskApps.size());b.putInt(SystemWidgetProtocol.FAILED,0);return b;
        });
    }
    @Override public IBinder onBind(Intent intent) { return endpoint.getBinder(); }
    @Override public void onDestroy() {
        closeScenarios();scenarios.close();energy.close();suspension.close();voice.close();cleanup.close();vehicle.close();main.removeCallbacksAndMessages(null);
    }
    private static void send(Messenger client,int what,Bundle data) {
        if(client==null)return;
        try {Message m=Message.obtain(null,what);m.setData(data);client.send(m);}catch(RemoteException ignored){}
    }
    private void trace(Bundle data) { send(observer,TRACE,data); }
    private void operation(String op,String text,int value) {
        Bundle b=new Bundle();b.putString("op",op);b.putString("text",text);b.putInt("value",value);trace(b);
    }
    @Override void updateLightSensorObservation() {
        operation("scenarioConfig", ScenarioConfigReceiver.loadPersisted(this), 0);
    }
    @Override protected List<WidgetSupport.RunningApp> queryTaskApps(){return new ArrayList<>(taskApps);}
    @Override protected void stopTaskApp(String pkg){operation("taskStop",pkg,0);taskApps.removeIf(app -> app.packageName.equals(pkg));}
    @Override protected void pinTaskApp(String pkg,boolean pinned){if(taskApps.stream().noneMatch(app->app.packageName.equals(pkg)))return;if(pinned)taskPins.add(pkg);else taskPins.remove(pkg);}
    @Override protected boolean isTaskPinned(String pkg){return taskPins.contains(pkg);}
    @Override protected boolean isWidgetOnlyTask(String pkg){return taskApps.stream().anyMatch(app->app.packageName.equals(pkg)&&app.widget);}
    @Override protected boolean moveTaskToFront(String pkg){operation("taskFront",pkg,0);return true;}
    @Override protected void launchTaskApp(String pkg){operation("taskLaunch",pkg,0);}
    @Override protected void releaseWidgetInstances(String pkg){operation("taskRelease",pkg,0);}
    @Override protected void handleEnergyRequest(Message m){energy.handle(m);}
    @Override protected void handleSuspensionRequest(Message m){suspension.handle(m);}
    @Override protected void handleVoiceRequest(Bundle b){voice.handle(b);}
    @Override protected OemCommandSender.Transport voiceTransport(){return vehicle;}
    @Override protected void applyModes(Runnable done){operation("apply",null,0);main.postDelayed(done,100);}
    @Override protected void applyStar(int preset){operation("star",null,preset);}
    @Override protected void setAutoLightEnabled(boolean on){operation("autoLight",null,on?1:0);}
    @Override protected void requestPedestrian(boolean on){operation("pedestrian",null,on?1:0);}
    @Override protected void requestMaintenance(boolean on){operation("maintenance",null,on?1:0);}
    @Override protected void requestForcedEv(boolean on){operation("forcedEv",null,on?1:0);}
    @Override protected void setFloatingBackEnabled(boolean on){operation("floatingBack",null,on?1:0);}
    @Override protected void setFloatingBackSide(int side){operation("floatingSide",null,side);}
    @Override protected void grantInstallPermission(String pkg,int uid){operation("grant",pkg,uid);}
    @Override protected void rebootSystem(){operation("reboot",null,0);}
    @Override protected void applyTheme(int theme){operation("theme",null,theme);}
    @Override protected void setLoggingEnabled(boolean on){operation("logging",null,on?1:0);}
    @Override protected void shareLogFile(){operation("share",null,0);}
    @Override protected void requestPowerHold(){
        PowerHoldController controller=new PowerHoldController(action -> {
            if(!vehicle.connected)return PowerHoldPolicy.Outcome.TRANSPORT_FAILURE;
            return action.run(new PowerHoldController.Session(){
                public PowerHoldController.Gear readGear(){OemVehicleStateTransport.GearStatus g=vehicle.readGearStatus();return new PowerHoldController.Gear(g.ordinal,g.value);}
                public Integer readSoc(){return vehicle.readVehicleState(new OemVehicleStateTransport.StateKey("BMS_SOC_DISPLAY",615));}
                public boolean sendActivation(Map<String,Integer> values,String label){
                    Map<OemVehicleStateTransport.StateKey,Integer> keyed=new LinkedHashMap<>();
                    for(Map.Entry<String,Integer> e:values.entrySet())keyed.put(new OemVehicleStateTransport.StateKey(e.getKey(),PowerHoldPolicy.stableIds().get(e.getKey())),e.getValue());
                    return vehicle.sendBundle(keyed,label).accepted();
                }
            });
        });
        operation("powerHoldResult",controller.activate().name(),0);
    }
    @Override protected void requestWash(){operation("wash",null,0);}
    @Override protected void handleClearRequest(Message request){cleanup.handle(request);}
    @Override protected void launchSingleApp(String pkg,int dpi){operation("single",pkg,dpi);}
    @Override protected void launchFreeformApp(String pkg,int dpi){operation("freeform",pkg,dpi);}
    @Override protected void launchVirtualSplit(String left,String right,int ratio,int ldpi,int rdpi,boolean resize,float split,int index,String id){
        Bundle b=new Bundle();b.putString("op","split");b.putString("left",left);b.putString("right",right);b.putInt("ratio",ratio);b.putInt("leftDpi",ldpi);b.putInt("rightDpi",rdpi);b.putBoolean("resizable",resize);b.putFloat("split",split);b.putInt("presetIdx",index);b.putString("presetId",id);trace(b);
    }
    @Override protected void releaseEmbeddedDisplay(String id){operation("release",id,0);}
    @Override protected void startEmbeddedDisplay(String id,String pkg,Surface surface,int width,int height,int dpi){operation("surface",id,surface!=null&&surface.isValid()?width:-1);}
    @Override protected void injectEmbeddedTouch(String id,MotionEvent event){operation("touch",id,event==null?-1:event.getActionMasked());}
    @Override protected void moveEmbeddedDisplay(Bundle data){operation("move",data.getString("widgetId"),0);}
    @Override protected void swapEmbeddedDisplays(Bundle data){operation("swap",data.getString("widgetId"),0);}
}
