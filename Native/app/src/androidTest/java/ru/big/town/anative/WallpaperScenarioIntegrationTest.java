package ru.big.town.anative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ContentResolver;
import android.content.Context;
import android.os.Handler;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import ru.big.town.common.WallpaperProtocol;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;

@RunWith(AndroidJUnit4.class)
public class WallpaperScenarioIntegrationTest {
    @Test
    public void driveModeTransitionAppliesWallpaperThroughTheSignedProvider() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Context target = instrumentation.getTargetContext();
        SetModesService[] service = new SetModesService[1];
        instrumentation.runOnMainSync(() -> service[0] = new SetModesService() {
            @Override
            public ContentResolver getContentResolver() {
                return target.getContentResolver();
            }
        });
        ScenarioActionExecutor executor = new ScenarioActionExecutor(service[0]);
        TestEnvironment environment = new TestEnvironment(executor);
        ScenarioEngine engine = new ScenarioEngine(environment);
        String original = InstrumentationRegistry.getArguments().getString("restoreWallpaper", "black");
        try {
            engine.reload("[{\"id\":\"wallpaper-test\",\"name\":\"Обои\",\"enabled\":true,"
                    + "\"triggers\":[{\"type\":\"driveMode\",\"value\":\"SPORT\"}],"
                    + "\"conditions\":[],\"steps\":[{\"type\":\"action\",\"value\":\"wallpaper:lilac\"}]}]");
            assertFalse(environment.completed.await(200, TimeUnit.MILLISECONDS));
            environment.mode("COMFORT");
            environment.mode("SPORT");
            assertTrue(environment.completed.await(5, TimeUnit.SECONDS));
            assertTrue(environment.accepted.get());
            assertEquals("wallpaper:lilac", environment.action);
        } finally {
            engine.close();
            CountDownLatch restored = new CountDownLatch(1);
            AtomicBoolean restoreAccepted = new AtomicBoolean();
            executor.execute(WallpaperProtocol.ACTION_PREFIX + original,
                    (accepted, error) -> {
                        restoreAccepted.set(accepted);
                        restored.countDown();
                    });
            assertTrue(restored.await(5, TimeUnit.SECONDS));
            assertTrue(restoreAccepted.get());
        }
    }

    private static final class TestEnvironment implements ScenarioEnvironment {
        private final ScenarioActionExecutor executor;
        private final CountDownLatch completed = new CountDownLatch(1);
        private final AtomicBoolean accepted = new AtomicBoolean();
        private Handler modeHandler;
        private Consumer<String> modeListener;
        private String action;

        TestEnvironment(ScenarioActionExecutor executor) {
            this.executor = executor;
        }

        @Override
        public Subscription gear(Handler handler, IntConsumer listener) {
            return () -> {};
        }

        @Override
        public Subscription doors(Handler handler, IntConsumer listener) {
            return () -> {};
        }

        @Override
        public Subscription driveMode(Handler handler, Consumer<String> listener) {
            modeHandler = handler;
            modeListener = listener;
            mode("SPORT");
            return () -> {};
        }

        void mode(String value) {
            modeHandler.post(() -> modeListener.accept(value));
        }

        @Override
        public Subscription cabinTemperature(Handler handler, DoubleConsumer listener) {
            return () -> {};
        }

        @Override
        public Subscription speed(Handler handler, IntConsumer listener) {
            return () -> {};
        }

        @Override
        public Subscription light(IntConsumer listener) {
            return () -> {};
        }

        @Override
        public ScenarioPolicy.Snapshot snapshot(ScenarioDefinition scenario) {
            return new ScenarioPolicy.Snapshot(-1, ScenarioPolicy.Snapshot.UNKNOWN,
                    -1, null, 0, 1, -1);
        }

        @Override
        public void execute(String action, ScenarioActionExecutor.Completion completion) {
            this.action = action;
            executor.execute(action, (applied, error) -> {
                accepted.set(applied);
                completion.onFinished(applied, error);
                completed.countDown();
            });
        }

        @Override
        public void notifyUser(String text) {}
    }
}
