package ru.big.town.anative;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.car.hardware.power.CarPowerManager.CarPowerStateListener;
import android.content.Context;
import android.provider.Settings;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, manifest = Config.NONE, application = Application.class)
public class ScenarioTriggerPersistenceTest {
    private final Context context = RuntimeEnvironment.getApplication();
    private final ScenarioDefinition.Trigger doorOpening =
            new ScenarioDefinition.Trigger("door", "driver", "open", true);

    private boolean acceptOpening() {
        return ScenarioTriggerPersistence.accept(context, "driver-door", doorOpening);
    }

    @Test
    public void cancelledShutdownDoesNotRearmTheDoorOnSubsequentOpenings() {
        assertTrue(acceptOpening());
        assertFalse(acceptOpening());
        for (int wake :
                new int[] {CarPowerStateListener.SHUTDOWN_CANCELLED, CarPowerStateListener.ON}) {
            ScenarioTriggerPersistence.sleep(context, CarPowerStateListener.SHUTDOWN_PREPARE);
            assertFalse(acceptOpening());
            ScenarioTriggerPersistence.wake(context, wake);
            assertFalse(acceptOpening());
        }
    }

    @Test
    public void cancellationWhileWaitingForSleepOrShutdownKeepsTheConsumedOpening() {
        assertTrue(acceptOpening());
        for (int entry :
                new int[] {
                    CarPowerStateListener.SUSPEND_ENTER, CarPowerStateListener.SHUTDOWN_ENTER
                }) {
            ScenarioTriggerPersistence.sleep(context, CarPowerStateListener.SHUTDOWN_PREPARE);
            ScenarioTriggerPersistence.sleep(context, entry);
            ScenarioTriggerPersistence.wake(context, CarPowerStateListener.SHUTDOWN_CANCELLED);
            ScenarioTriggerPersistence.wake(context, CarPowerStateListener.ON);
            assertFalse(acceptOpening());
        }
    }

    @Test
    public void onAfterSleepEntryRearmsOnceWhenTheExitCallbackWasMissed() {
        assertTrue(acceptOpening());
        ScenarioTriggerPersistence.sleep(context, CarPowerStateListener.SUSPEND_ENTER);
        ScenarioTriggerPersistence.wake(context, CarPowerStateListener.WAIT_FOR_VHAL);
        ScenarioTriggerPersistence.wake(context, CarPowerStateListener.ON);
        assertTrue(acceptOpening());
        ScenarioTriggerPersistence.wake(context, CarPowerStateListener.ON);
        assertFalse(acceptOpening());
    }

    @Test
    public void confirmedSleepRearmsOnlyOnceEvenWhenWakeIsRepeated() {
        assertTrue(acceptOpening());
        ScenarioTriggerPersistence.sleep(context, CarPowerStateListener.SHUTDOWN_PREPARE);
        ScenarioTriggerPersistence.sleep(context, CarPowerStateListener.SUSPEND_ENTER);
        assertFalse(acceptOpening());
        ScenarioTriggerPersistence.wake(context, CarPowerStateListener.SUSPEND_EXIT);
        assertTrue(acceptOpening());
        ScenarioTriggerPersistence.wake(context, CarPowerStateListener.SUSPEND_EXIT);
        ScenarioTriggerPersistence.wake(context, CarPowerStateListener.ON);
        assertFalse(acceptOpening());
    }

    @Test
    public void suspendExitAfterPreparationConfirmsSleepWhenEntryWasMissed() {
        assertTrue(acceptOpening());
        ScenarioTriggerPersistence.sleep(context, CarPowerStateListener.SHUTDOWN_PREPARE);
        ScenarioTriggerPersistence.wake(context, CarPowerStateListener.SUSPEND_EXIT);
        assertTrue(acceptOpening());
        assertFalse(acceptOpening());
    }

    @Test
    public void androidRebootRearmsTheDoorWithoutAPowerCallback() {
        Settings.Global.putInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 7);
        assertTrue(acceptOpening());
        assertFalse(acceptOpening());
        Settings.Global.putInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 8);
        assertTrue(acceptOpening());
        assertFalse(acceptOpening());
    }
}
