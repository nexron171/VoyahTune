package ru.big.town.anative;

import android.content.*;
import android.os.*;
import android.view.MotionEvent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import ru.big.town.common.*;

@RunWith(AndroidJUnit4.class)
public class NativeIntegrationTest {
    private Context testContext;
    private Messenger remote;
    private ServiceConnection connection;
    private final Inbox inbox=new Inbox();
    static final class Inbox {
        final List<Message> received=new ArrayList<>();
        final Messenger messenger=new Messenger(new Handler(Looper.getMainLooper(),m->{synchronized(received){received.add(Message.obtain(m));received.notifyAll();}return true;}));
        Message await(Predicate<Message> match) throws Exception {
            long deadline=SystemClock.elapsedRealtime()+6000;
            synchronized(received){while(true){for(int i=0;i<received.size();i++)if(match.test(received.get(i)))return received.remove(i);
                long left=deadline-SystemClock.elapsedRealtime();if(left<=0)throw new AssertionError("No matching reply: "+received);received.wait(left);}}
        }
        Message what(int what)throws Exception{return await(m->m.what==what);}
        Bundle op(String op)throws Exception{return await(m->m.what==NativeContractService.TRACE&&op.equals(m.getData().getString("op"))).getData();}
        void clear(){synchronized(received){received.clear();}}
        boolean absent(int what,long millis)throws Exception{Thread.sleep(millis);synchronized(received){return received.stream().noneMatch(m->m.what==what);}}
    }
    @Before public void connect() throws Exception {
        assertTrue("Emulator only",Build.FINGERPRINT.contains("generic")||Build.MODEL.contains("sdk")||Build.HARDWARE.contains("ranchu"));
        testContext=InstrumentationRegistry.getInstrumentation().getContext();
        CountDownLatch ready=new CountDownLatch(1);
        connection=new ServiceConnection(){public void onServiceConnected(ComponentName n,IBinder b){remote=new Messenger(b);ready.countDown();}public void onServiceDisconnected(ComponentName n){remote=null;}};
        assertTrue(testContext.bindService(new Intent().setClassName(testContext.getPackageName(),MockRestoreService.class.getName()),connection,Context.BIND_AUTO_CREATE));
        assertTrue(ready.await(5,TimeUnit.SECONDS));Bundle b=new Bundle();b.putBoolean("reset",true);control(b);inbox.clear();
    }
    @After public void disconnect(){if(connection!=null)testContext.unbindService(connection);}
    private void send(int what,int arg,Bundle data,Inbox target)throws Exception{Message m=Message.obtain(null,what,arg,0);m.replyTo=target.messenger;if(data!=null)m.setData(data);remote.send(m);}
    private void send(int what,int arg)throws Exception{send(what,arg,null,inbox);}
    private void control(Bundle b)throws Exception{send(NativeContractService.CONTROL,0,b,inbox);inbox.what(NativeContractService.READY);}
    private Bundle setting(String key,boolean value){Bundle b=new Bundle();b.putBoolean(key,value);return b;}
    @Test public void decodesCommandsAcrossApkBoundaryAndRepliesAfterApply()throws Exception{
        send(1,0);Bundle request=inbox.op("request");assertNotEquals(android.os.Process.myUid(),request.getInt("callerUid"));
        assertEquals(testContext.getPackageManager().getApplicationInfo(testContext.getPackageName(),0).uid,request.getInt("callerUid"));
        inbox.op("apply");inbox.what(4);
        int[] codes={2,10,11,21,35,37,24,25,22,28,32,33,23};
        String[] ops={"star","autoLight","autoLight","pedestrian","forcedEv","maintenance","floatingBack","floatingSide","reboot","theme","logging","share","wash"};
        for(int i=0;i<codes.length;i++){send(codes[i],1);Bundle b=inbox.op(ops[i]);if(codes[i]==11)assertEquals(0,b.getInt("value"));}
        Bundle pkg=new Bundle();pkg.putString("pkg","example.app");send(26,12345,pkg,inbox);Bundle grant=inbox.op("grant");assertEquals("example.app",grant.getString("text"));assertEquals(12345,grant.getInt("value"));
        send(27,77);Message result=inbox.what(SystemWidgetProtocol.CLEAR_RESULT);assertEquals(77,result.arg1);assertEquals(1,result.getData().getInt(SystemWidgetProtocol.SCHEMA));
        send(999,0);send(1,0);inbox.what(4);
    }
    @Test public void splitAndParcelableTouchKeepAllArguments()throws Exception{
        Bundle b=new Bundle();b.putString("left","app.left");b.putString("right","app.right");b.putInt("leftDpi",180);b.putInt("rightDpi",220);b.putBoolean("resizable",true);b.putFloat("split",.6f);b.putInt("presetIdx",2);b.putString("presetId","stable-id");
        send(34,3,b,inbox);Bundle split=inbox.op("split");assertEquals("app.left",split.getString("left"));assertEquals("app.right",split.getString("right"));assertEquals(180,split.getInt("leftDpi"));assertEquals(220,split.getInt("rightDpi"));assertEquals(.6f,split.getFloat("split"),0);assertTrue(split.getBoolean("resizable"));assertEquals(2,split.getInt("presetIdx"));assertEquals("stable-id",split.getString("presetId"));
        b.putBoolean("singleVd",true);send(34,0,b,inbox);assertEquals(180,inbox.op("single").getInt("value"));
        b.putBoolean("singleVd",false);b.remove("right");send(34,0,b,inbox);inbox.op("freeform");
        Bundle touch=new Bundle();touch.putBoolean("embeddedTouch",true);touch.putString("widgetId","widget-a");
        MotionEvent e=MotionEvent.obtain(0,10,MotionEvent.ACTION_DOWN,12,34,0);touch.putParcelable("event",e);send(34,0,touch,inbox);assertEquals(MotionEvent.ACTION_DOWN,inbox.op("touch").getInt("value"));e.recycle();
        Bundle release=new Bundle();release.putBoolean("embeddedRelease",true);release.putString("widgetId","widget-a");send(34,0,release,inbox);assertEquals("widget-a",inbox.op("release").getString("text"));
        for(String op:new String[]{"Move","Swap"}){Bundle transfer=new Bundle();transfer.putBoolean("embedded"+op,true);send(38,0,transfer,inbox);inbox.op(op.toLowerCase());}
    }
    @Test public void suspensionReadsMockCarWaitsForFeedbackAndConfirms()throws Exception{
        send(SuspensionWidgetProtocol.WATCH,0);inbox.await(m->m.what==93&&m.getData().getInt("height")==5);
        send(SuspensionWidgetProtocol.SELECT,1);
        Bundle write=inbox.op("write");assertEquals(545,write.getInt("id"));assertEquals(3,write.getInt("value"));
        inbox.await(m->m.what==93&&m.getData().getInt(SuspensionWidgetProtocol.PENDING)==1);
        Message confirmed=inbox.await(m->m.what==93&&"Высота подтверждена".equals(m.getData().getString(SuspensionWidgetProtocol.MESSAGE)));
        assertEquals(7,confirmed.getData().getInt(SuspensionWidgetProtocol.HEIGHT));assertEquals(-1,confirmed.getData().getInt(SuspensionWidgetProtocol.PENDING));
        Bundle persisted=testContext.getContentResolver().call(android.net.Uri.parse("content://ru.big.town.anative.test.restore/"),"inspect",null,null);
        assertEquals("SPORT",persisted.getString("selected"));assertEquals(1,persisted.getInt("writes"));assertTrue(persisted.getInt("reads")>=3);
        assertEquals(android.os.Process.myUid(),persisted.getInt("caller"));assertNotEquals(android.os.Process.myUid(),persisted.getInt("providerUid"));
    }
    @Test public void suspensionRejectsUnsafeRequestsAndTransportErrors()throws Exception{
        Bundle b=new Bundle();b.putInt("speed",50);control(b);send(92,3);
        inbox.await(m->m.what==93&&m.getData().getString("message","").contains("40 км"));
        b=new Bundle();b.putInt("speed",0);b.putBoolean("accepted",false);control(b);send(92,1);
        inbox.await(m->m.what==93&&"Команда не отправлена".equals(m.getData().getString("message")));
        b=new Bundle();b.putInt("stateId",711);b.putInt("stateValue",2);control(b);send(92,1);
        inbox.await(m->m.what==93&&m.getData().getString("message","").contains("сервисный режим"));
    }
    @Test public void missingConfirmationTimesOutAndReconnectRefreshes()throws Exception{
        control(setting("confirm",false));send(92,1);
        inbox.await(m->m.what==93&&m.getData().getString("message","").contains("не подтверждено"));
        control(setting("connected",false));inbox.await(m->m.what==93&&m.getData().getInt("height")==-1);
        control(setting("connected",true));inbox.await(m->m.what==93&&m.getData().getInt("height")==5);
    }
    @Test public void unwatchCannotRemoveNewOwnerAndStopsOldCallbacks()throws Exception{
        send(90,0);inbox.what(93);Inbox replacement=new Inbox();send(90,0,null,replacement);replacement.what(93);
        send(91,0);Bundle b=new Bundle();b.putInt("stateId",750);b.putInt("stateValue",7);control(b);
        replacement.await(m->m.what==93&&m.getData().getInt("height")==7);
        send(91,0,null,replacement);Thread.sleep(100);replacement.clear();b.putInt("stateValue",1);control(b);assertTrue(replacement.absent(93,180));
    }
    @Test public void telemetryConfigurationDisconnectAndSubscriptionLifecycle()throws Exception{
        Bundle config=new Bundle();config.putInt(EnergyWidgetProtocol.SCHEMA,EnergyWidgetProtocol.VERSION);config.putFloat(EnergyWidgetProtocol.BATTERY_KWH,50);config.putFloat(EnergyWidgetProtocol.TANK_LITERS,60);send(103,0,config,inbox);send(100,0);
        Message state=inbox.await(m->m.what==102&&m.getData().getFloatArray(EnergyWidgetProtocol.LEVELS)[0]==78);
        assertEquals(50,state.getData().getFloat(EnergyWidgetProtocol.BATTERY_KWH),0);assertEquals(2.4f,state.getData().getFloatArray(EnergyWidgetProtocol.TIRES)[0],0);
        config.putInt(EnergyWidgetProtocol.SCHEMA,999);config.putFloat(EnergyWidgetProtocol.BATTERY_KWH,99);send(103,0,config,inbox);send(100,0);assertEquals(50,inbox.what(102).getData().getFloat(EnergyWidgetProtocol.BATTERY_KWH),0);
        control(setting("connected",false));Message lost=inbox.await(m->m.what==102&&!m.getData().getBoolean(EnergyWidgetProtocol.CONNECTED));assertTrue(Float.isNaN(lost.getData().getFloatArray(EnergyWidgetProtocol.LEVELS)[0]));
        control(setting("connected",true));inbox.await(m->m.what==102&&m.getData().getBoolean(EnergyWidgetProtocol.CONNECTED));send(101,0);Thread.sleep(100);inbox.clear();assertTrue(inbox.absent(102,1150));
    }
    @Test public void powerHoldUsesVehicleGearSocAndReportsAcceptanceOnly()throws Exception{
        Bundle b=new Bundle();b.putInt("gear",1);control(b);send(20,0);assertEquals("NOT_IN_PARK",inbox.op("powerHoldResult").getString("text"));
        b.putInt("gear",0);b.putInt("soc",10);control(b);send(20,0);assertEquals("LOW_BATTERY",inbox.op("powerHoldResult").getString("text"));
        b.putInt("soc",60);control(b);send(20,0);assertEquals("ACCEPTED",inbox.op("powerHoldResult").getString("text"));
        Bundle write=inbox.op("write");assertEquals(1162,write.getInt("id"));assertEquals(15,write.getInt("value"));
        control(setting("accepted",false));send(20,0);assertEquals("TRANSPORT_FAILURE",inbox.op("powerHoldResult").getString("text"));
    }
    @Test public void voiceUsesRealSessionGateOemTransportAndFrameworkCallback()throws Exception{
        BlockingQueue<Bundle> replies=new LinkedBlockingQueue<>();
        ResultReceiver local=new ResultReceiver(new Handler(Looper.getMainLooper())){@Override protected void onReceiveResult(int code,Bundle data){data.putInt("code",code);replies.add(data);}};
        Parcel p=Parcel.obtain();local.writeToParcel(p,0);p.setDataPosition(0);ResultReceiver callback=ResultReceiver.CREATOR.createFromParcel(p);p.recycle();
        Bundle b=new Bundle();b.putString("session","ipc-test");b.putString("op","begin");send(36,0,b,inbox);
        b.putString("op","execute");b.putString("action","windows:driver:open");b.putParcelable("reply",callback);send(36,0,b,inbox);
        Bundle reply=replies.poll(5,TimeUnit.SECONDS);assertNotNull(reply);assertEquals(reply.toString(),1,reply.getInt("code"));inbox.op("oemWrite");
        send(36,0,b,inbox);assertNull(replies.poll(250,TimeUnit.MILLISECONDS));
        b.putString("op","cancel");send(36,0,b,inbox);b.putString("op","execute");b.putInt("index",1);b.putInt("count",2);send(36,0,b,inbox);assertNull(replies.poll(250,TimeUnit.MILLISECONDS));
    }
    private void configureScenarios(String json)throws Exception{
        Bundle b=new Bundle();b.putString("json",json);send(9003,0,b,inbox);
        assertEquals(json,inbox.op("scenarioConfig").getString("text"));
        assertEquals(json,InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getSharedPreferences("NativePrefs",0).getString(ScenarioProtocol.KEY_SCENARIOS,""));
    }
    private static String scenario(String id,boolean enabled,String triggers,String conditions,String steps){
        return "{\"id\":\""+id+"\",\"name\":\""+id+"\",\"enabled\":"+enabled+
                ",\"triggers\":"+triggers+",\"conditions\":"+conditions+",\"steps\":"+steps+"}";
    }
    private static String action(String value){return "{\"type\":\"action\",\"value\":\""+value+"\"}";}
    private void runScenario(String id,boolean test)throws Exception{
        Bundle b=new Bundle();b.putString(ScenarioProtocol.EXTRA_ID,id);b.putBoolean(ScenarioProtocol.EXTRA_TEST,test);
        send(ScenarioProtocol.MSG_RUN,0,b,inbox);
    }
    private void assertNoScenarioAction(long duration)throws Exception{
        Thread.sleep(duration);
        synchronized(inbox.received){assertFalse(inbox.received.stream().anyMatch(m->
                m.what==NativeContractService.TRACE&&"scenarioAction".equals(m.getData().getString("op"))));}
    }
    @Test public void taskManagerDecodesListPinsClosesAndDistinguishesWidgetTasks()throws Exception{
        send(TaskManagerProtocol.REQUEST,0);
        Bundle list=inbox.what(TaskManagerProtocol.LIST).getData();
        assertEquals(Arrays.asList("mock.navigation","mock.music","mock.podcasts"),list.getStringArrayList(TaskManagerProtocol.PACKAGES));
        assertArrayEquals(new boolean[]{true,false,false},list.getBooleanArray(TaskManagerProtocol.PINNED));
        assertArrayEquals(new boolean[]{true,false,false},list.getBooleanArray(TaskManagerProtocol.WIDGETS));
        Bundle b=new Bundle();b.putString(TaskManagerProtocol.PACKAGE,"mock.music");b.putBoolean(TaskManagerProtocol.PINNED,true);
        send(TaskManagerProtocol.PIN,0,b,inbox);assertTrue(inbox.what(TaskManagerProtocol.LIST).getData().getBooleanArray(TaskManagerProtocol.PINNED)[1]);
        send(TaskManagerProtocol.SWITCH,0,b,inbox);assertEquals("mock.music",inbox.op("taskFront").getString("text"));
        b.putString(TaskManagerProtocol.PACKAGE,"mock.navigation");send(TaskManagerProtocol.SWITCH,0,b,inbox);inbox.op("taskLaunch");
        send(TaskManagerProtocol.CLOSE,0,b,inbox);
        assertEquals("mock.navigation",inbox.op("taskRelease").getString("text"));assertEquals("mock.navigation",inbox.op("taskStop").getString("text"));
        assertEquals(Arrays.asList("mock.music","mock.podcasts"),inbox.what(TaskManagerProtocol.LIST).getData().getStringArrayList(TaskManagerProtocol.PACKAGES));
        send(TaskManagerProtocol.CLOSE_ALL,12);assertEquals(1,inbox.what(SystemWidgetProtocol.CLEAR_RESULT).getData().getInt(SystemWidgetProtocol.SUCCEEDED));
        send(TaskManagerProtocol.REQUEST,0);assertEquals(Collections.singletonList("mock.music"),inbox.what(TaskManagerProtocol.LIST).getData().getStringArrayList(TaskManagerProtocol.PACKAGES));
        b.putString(TaskManagerProtocol.PACKAGE,"mock.music");b.putBoolean(TaskManagerProtocol.PINNED,false);send(TaskManagerProtocol.PIN,0,b,inbox);inbox.what(TaskManagerProtocol.LIST);
        send(TaskManagerProtocol.CLOSE_ALL,13);inbox.what(SystemWidgetProtocol.CLEAR_RESULT);send(TaskManagerProtocol.REQUEST,0);
        assertTrue(inbox.what(TaskManagerProtocol.LIST).getData().getStringArrayList(TaskManagerProtocol.PACKAGES).isEmpty());
    }
    @Test public void scenarioConfigBroadcastReloadsRunningEngineAndRemovalStopsNewRuns()throws Exception{
        String steps="["+action("windows:driver:open")+"]";
        configureScenarios("["+scenario("manual",true,"[]","[]",steps)+"]");
        runScenario("manual",false);assertEquals("windows:driver:open",inbox.op("scenarioAction").getString("text"));
        inbox.op("oemWrite");assertEquals(1,inbox.op("scenarioActionResult").getInt("value"));inbox.op("scenarioNotice");
        configureScenarios("[]");inbox.clear();runScenario("manual",true);assertNoScenarioAction(200);
        configureScenarios("["+scenario("disabled",false,"[]","[]",steps)+"]");
        inbox.clear();runScenario("disabled",false);assertNoScenarioAction(150);
        runScenario("disabled",true);inbox.op("scenarioAction");inbox.op("scenarioActionResult");
    }
    @Test public void scenarioVehicleConditionsReadSocAndRejectUnknownOrLowValues()throws Exception{
        String conditions="[{\"type\":\"soc\",\"op\":\"ge\",\"value\":50}]";
        configureScenarios("["+scenario("charge",true,"[]",conditions,"["+action("windows:driver:close")+"]")+"]");
        Bundle car=new Bundle();car.putInt("soc",20);control(car);runScenario("charge",true);
        assertEquals(615,inbox.op("read").getInt("id"));assertEquals(20,inbox.op("scenarioSnapshot").getInt("value"));assertTrue(inbox.op("scenarioNotice").getString("text").contains("условия"));assertNoScenarioAction(100);
        car=new Bundle();car.putBoolean("connected",false);control(car);runScenario("charge",true);
        assertEquals(-1,inbox.op("scenarioSnapshot").getInt("value"));inbox.op("scenarioNotice");assertNoScenarioAction(100);
        car.putBoolean("connected",true);car.putInt("soc",80);control(car);runScenario("charge",true);
        inbox.op("scenarioAction");inbox.op("oemWrite");assertEquals(1,inbox.op("scenarioActionResult").getInt("value"));
    }
    @Test public void scenarioGearDoorAndLightEventsUseSubscriptionsAndSuppressDuplicates()throws Exception{
        String steps="["+action("windows:driver:close")+"]";
        configureScenarios("["+scenario("gear",true,"[{\"type\":\"gear\",\"value\":\"D\"}]","[]",steps)+","+
                scenario("door",true,"[{\"type\":\"door\",\"value\":\"driver\",\"edge\":\"open\"}]","[]",steps)+","+
                scenario("light",true,"[{\"type\":\"light\",\"value\":\"dark\"}]","[]",steps)+"]");
        assertEquals(1,inbox.op("scenarioLightSubscription").getInt("value"));
        for(String key:new String[]{"gear","doors","light"}){
            Bundle car=new Bundle();car.putInt(key,key.equals("gear")?3:key.equals("doors")?1:2);control(car);
            inbox.op("scenarioAction");inbox.op("scenarioActionResult");inbox.op("scenarioNotice");
            inbox.clear();control(car);assertNoScenarioAction(160);
        }
        configureScenarios("[]");assertEquals(0,inbox.op("scenarioLightSubscription").getInt("value"));
    }
    @Test public void scenarioPausePreservesOrderAndTransportFailureIsReported()throws Exception{
        configureScenarios("["+scenario("ordered",true,"[]","[]","["+action("windows:driver:open")+
                ",{\"type\":\"pause\",\"seconds\":1},"+action("windows:driver:close")+"]")+"]");
        runScenario("ordered",true);
        assertEquals("windows:driver:open",inbox.op("scenarioAction").getString("text"));
        long first=inbox.op("scenarioActionResult").getLong("elapsed");
        Bundle second=inbox.op("scenarioAction");assertEquals("windows:driver:close",second.getString("text"));
        assertTrue("Pause must wait a full second",second.getLong("elapsed")-first>=950);
        inbox.op("scenarioActionResult");inbox.op("scenarioNotice");
        control(setting("accepted",false));runScenario("ordered",true);inbox.op("scenarioAction");
        assertEquals(0,inbox.op("scenarioActionResult").getInt("value"));
        Bundle reset=new Bundle();reset.putBoolean("reset",true);control(reset);inbox.clear();assertNoScenarioAction(1200);
    }
    @Test public void lightObservationStartsForScenariosAndPreservesAutoLight()throws Exception{
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        int[] calls={0,0};
        Context runtime=new ContextWrapper(app){
            @Override public ComponentName startForegroundService(Intent intent){calls[0]++;return new ComponentName(app,LightSensorService.class);}
            @Override public boolean stopService(Intent intent){calls[1]++;return true;}
        };
        SharedPreferences preferences=app.getSharedPreferences("NativePrefs",0);
        preferences.edit().putBoolean("autoLight",false).commit();
        configureScenarios("["+scenario("sensor",true,"[{\"type\":\"light\",\"value\":\"dark\"}]","[]","["+action("windows:driver:close")+"]")+"]");
        AutoLightSettings.refreshObservation(runtime);
        assertEquals(1,calls[0]);assertEquals(0,calls[1]);assertTrue(preferences.getBoolean("lightObserveOnly",false));
        preferences.edit().putBoolean("autoLight",true).commit();
        AutoLightSettings.refreshObservation(runtime);assertEquals(2,calls[0]);assertFalse(preferences.getBoolean("lightObserveOnly",true));
        configureScenarios("[]");AutoLightSettings.refreshObservation(runtime);assertEquals(3,calls[0]);
        preferences.edit().putBoolean("autoLight",false).commit();AutoLightSettings.refreshObservation(runtime);assertEquals(1,calls[1]);
    }
}
