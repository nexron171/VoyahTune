package ru.big.town.restoremode;

import android.content.*;
import android.os.*;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import ru.big.town.common.*;

@RunWith(AndroidJUnit4.class)
public class RestoreIntegrationTest {
    private Context app,test;
    private volatile Messenger mock;
    private ServiceConnection connection;
    private ActivityScenario<IntegrationMainActivity> screen;
    private final List<Message> received=new ArrayList<>();
    private final Messenger replies=new Messenger(new Handler(Looper.getMainLooper(),m->{synchronized(received){received.add(Message.obtain(m));received.notifyAll();}return true;}));
    @Before public void setUp()throws Exception{
        assertTrue("Emulator only",Build.FINGERPRINT.contains("generic")||Build.MODEL.contains("sdk")||Build.HARDWARE.contains("ranchu"));
        app=InstrumentationRegistry.getInstrumentation().getTargetContext();test=InstrumentationRegistry.getInstrumentation().getContext();
        app.getSharedPreferences("DrivePreferences",0).edit().clear().putBoolean("showSuspensionWidget",true).putBoolean("show_energyWidget",true).putBoolean("voiceEnabled",false).commit();
        connection=new ServiceConnection(){public void onServiceConnected(ComponentName n,IBinder b){mock=new Messenger(b);}public void onServiceDisconnected(ComponentName n){mock=null;}};
        assertTrue(test.bindService(new Intent().setClassName(test.getPackageName(),MockNativeService.class.getName()),connection,Context.BIND_AUTO_CREATE));
        waitFor(()->mock!=null);control(new Bundle());
    }
    @After public void tearDown(){if(screen!=null)screen.close();if(connection!=null)test.unbindService(connection);}
    private void launch(){screen=ActivityScenario.launch(new Intent(app,IntegrationMainActivity.class));}
    private static void waitFor(BooleanSupplier ready)throws Exception{long end=SystemClock.elapsedRealtime()+8000;while(!ready.getAsBoolean()){if(SystemClock.elapsedRealtime()>=end)throw new AssertionError("condition timed out");Thread.sleep(25);}}
    private Message await(Predicate<Message> match)throws Exception{
        long end=SystemClock.elapsedRealtime()+8000;
        synchronized(received){while(true){for(int i=0;i<received.size();i++)if(match.test(received.get(i)))return received.remove(i);long left=end-SystemClock.elapsedRealtime();if(left<=0)throw new AssertionError("Missing IPC event: "+received);received.wait(left);}}
    }
    private Bundle request(int what)throws Exception{return await(m->m.what==9001&&m.getData().getInt("what")==what).getData();}
    private Bundle control(Bundle b)throws Exception{Message m=Message.obtain(null,9000);m.replyTo=replies;m.setData(b);mock.send(m);return await(r->r.what==9002).getData();}
    private Bundle state(String field){Bundle[] result={null};screen.onActivity(a->{try{Field f=MainActivity.class.getDeclaredField(field);f.setAccessible(true);result[0]=new Bundle((Bundle)f.get(a));}catch(Exception e){throw new AssertionError(e);}});return result[0];}
    @Test public void realDashboardConfiguresSubscribesAndConsumesCallbacks()throws Exception{
        launch();Bundle config=request(EnergyWidgetProtocol.CONFIGURE);assertEquals(EnergyWidgetProtocol.VERSION,config.getInt(EnergyWidgetProtocol.SCHEMA));assertEquals(android.os.Process.myUid(),config.getInt("callerUid"));
        request(100);request(90);waitFor(()->state("energyWidgetState").getInt("schema")==8);assertEquals(66,state("energyWidgetState").getFloatArray("levels")[0],0);assertEquals(7,state("suspensionState").getInt("height"));
        screen.onActivity(a->{try{Field f=MainActivity.class.getDeclaredField("energyWidgetViews");f.setAccessible(true);List<?> views=(List<?>)f.get(a);assertFalse(views.isEmpty());Field s=EnergyWidgetView.class.getDeclaredField("state");s.setAccessible(true);assertEquals(66,((Bundle)s.get(views.get(0))).getFloatArray("levels")[0],0);}catch(Exception e){throw new AssertionError(e);}});
        Bundle invalid=new Bundle();invalid.putBoolean("emit",true);invalid.putInt("schema",999);invalid.putInt("height",1);control(invalid);waitFor(()->state("suspensionState").getInt("height")==1);assertEquals(8,state("energyWidgetState").getInt("schema"));
    }
    @Test public void dashboardActionsSendExpectedCommandsAndPersistPreferences()throws Exception{
        launch();request(100);
        screen.onActivity(a->a.onCardAutoLight(null));request(10);
        screen.onActivity(a->a.onCardAutoLight(null));request(11);
        screen.onActivity(a->a.onCardPedestrian(null));assertEquals(1,request(21).getInt("arg1"));
        screen.onActivity(a->a.onCardForcedEv(null));assertEquals(1,request(35).getInt("arg1"));
        screen.onActivity(a->a.onCardSuspensionMaintenance(null));assertEquals(1,request(37).getInt("arg1"));
        SharedPreferences prefs=app.getSharedPreferences("DrivePreferences",0);assertTrue(prefs.getBoolean("forcedEv",false));assertTrue(prefs.getBoolean("disablePedestrianSound",false));assertTrue(prefs.getBoolean("suspensionMaintenance",false));
    }
    @Test public void settingsApplyStaysDisabledUntilNativeCallback()throws Exception{
        launch();request(100);
        android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        android.app.Instrumentation.ActivityMonitor monitor=instrumentation.addMonitor(AdvanceActivity.class.getName(),null,false);
        screen.onActivity(a->a.startActivity(new Intent(a,AdvanceActivity.class)));
        AdvanceActivity settings=(AdvanceActivity)monitor.waitForActivityWithTimeout(5000);
        assertNotNull(settings);
        try {
            instrumentation.runOnMainSync(()->{settings.onButtonClickApply(null);assertFalse(settings.findViewById(R.id.buttonApplyAdvance).isEnabled());});request(1);
            Bundle b=new Bundle();b.putBoolean("finishApply",true);control(b);
            waitFor(()->{boolean[] enabled={false};instrumentation.runOnMainSync(()->enabled[0]=settings.findViewById(R.id.buttonApplyAdvance).isEnabled());return enabled[0];});
        } finally {instrumentation.runOnMainSync(settings::finish);instrumentation.removeMonitor(monitor);}
    }
    @Test public void pauseUnsubscribesResumeAndRecreationResubscribe()throws Exception{
        launch();request(100);request(90);
        screen.moveToState(Lifecycle.State.CREATED);request(101);request(91);
        screen.moveToState(Lifecycle.State.RESUMED);request(100);request(90);
        screen.recreate();request(101);request(91);request(100);request(90);
        waitFor(()->state("energyWidgetState").containsKey("levels"));
    }
    @Test public void nativeProcessDeathReconnectsAndRestoresSubscriptions()throws Exception{
        launch();request(100);request(90);Messenger old=mock;
        Bundle b=new Bundle();b.putBoolean("die",true);control(b);
        waitFor(()->mock!=null&&!mock.getBinder().equals(old.getBinder()));
        control(new Bundle());request(100);request(90);waitFor(()->state("energyWidgetState").getInt("schema")==8);
        screen.onActivity(a->assertTrue(a.sendMessageToService(10)));request(10);
    }
    @Test public void completeChainUsesNativeControllersAndMockVehicle()throws Exception{
        screen=ActivityScenario.launch(new Intent(app,IntegrationMainActivity.class).putExtra("realNativeWithMockVehicle",true));
        waitFor(()->state("energyWidgetState").containsKey("levels")&&state("energyWidgetState").getFloatArray("levels")[0]==78);
        assertEquals(5,state("suspensionState").getInt("height"));
        screen.onActivity(a->{try{java.lang.reflect.Method m=MainActivity.class.getDeclaredMethod("sendSuspensionMessage",int.class,int.class);m.setAccessible(true);m.invoke(a,92,1);}catch(Exception e){throw new AssertionError(e);}});
        waitFor(()->state("suspensionState").getInt("height")==7&&"Высота подтверждена".equals(state("suspensionState").getString("message")));
    }
    @Test public void mockNativeReadsAndUpdatesRealRestoreProvider()throws Exception{
        app.getSharedPreferences("DrivePreferences",0).edit().putBoolean("forcedEv",true).putBoolean("energyRememberLast",false).commit();
        Bundle c=new Bundle();c.putBoolean("provider",true);c.putString("mode","SPORT");Bundle result=control(c);
        assertNotEquals(android.os.Process.myUid(),result.getInt("serviceUid"));assertEquals(1,result.getInt("changed"));
        assertEquals("SPORT",result.getString("configuredDriveMode"));assertEquals("SPORT",result.getString("suspensionDriveOverride"));
        assertEquals("1",result.getString("forcedEv"));assertEquals("0",result.getString("energyRememberLast"));
        assertTrue("A same-signed mock must not impersonate the Native UID for OTA health",result.getBoolean("healthDenied"));
        c.putString("mode","invalid");result=control(c);assertEquals(0,result.getInt("changed"));assertEquals("SPORT",result.getString("configuredDriveMode"));
    }
    @Test public void nativeResizeBroadcastUpdatesOnlyMatchingResizablePreset()throws Exception{
        SharedPreferences prefs=app.getSharedPreferences("DrivePreferences",0);
        SplitStore.Preset first=new SplitStore.Preset();first.id="first";first.resizable=true;
        SplitStore.Preset second=new SplitStore.Preset();second.id="second";
        SplitStore.save(prefs,Arrays.asList(first,second));
        Bundle c=new Bundle();c.putString("presetId","first");c.putFloat("split",.63f);control(c);
        waitFor(()->Math.abs(SplitStore.load(prefs).get(0).split-.63f)<.001f);
        c.putString("presetId","second");c.putFloat("split",.7f);control(c);Thread.sleep(150);
        assertEquals(0,SplitStore.load(prefs).get(1).split,0);
        c.putString("presetId","first");c.putFloat("split",.99f);control(c);Thread.sleep(150);assertEquals(.63f,SplitStore.load(prefs).get(0).split,0);
    }

}
