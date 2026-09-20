package ru.big.town.anative;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.lang.ref.WeakReference;

/** A removable host above the OEM media activity; finishing reveals the original media card. */
public final class ClusterMediaHostActivity extends Activity implements TextureView.SurfaceTextureListener {
    private static final String TAG = "VoyahClusterMedia";
    private static final String MEDIA_DISPLAY_NAME = "Cluster-Media-Display";
    private static final String EXTRA_PACKAGE = "clusterPackage";
    private static final String EXTRA_GENERATION = "clusterGeneration";
    // Live OEM PanelView geometry, verified on the 1920x720 cluster's simple theme.
    private static final int LEFT = 66, TOP = 200, WIDTH = 574, HEIGHT = 464, DPI = 160;
    private static final ClusterMediaSession SESSION = new ClusterMediaSession();
    private static WeakReference<ClusterMediaHostActivity> active = new WeakReference<>(null);
    private String packageName;
    private long generation;
    private VirtualDisplay virtualDisplay;
    private Surface surface;
    private boolean closing;

    static void launch(Context context, String pkg) {
        if (!BuildConfig.IS_FULL || pkg == null || pkg.isEmpty() || "none".equals(pkg)) return;
        Context app = context.getApplicationContext();
        int mediaId = findMediaDisplayId(app);
        if (mediaId < 0 || app.getPackageManager().getLaunchIntentForPackage(pkg) == null) {
            showError(app, "Медиакарточка или приложение недоступны");
            return;
        }
        ClusterMediaHostActivity previous = active.get();
        if (previous != null && previous.isCurrent() && pkg.equals(previous.packageName)) return;
        long token = SESSION.begin(pkg);
        if (previous != null) previous.close();
        AppDisplayLauncher.cancel(pkg);
        SplitHostActivity.closeActiveHost();
        try {
            Intent intent = new Intent(app, ClusterMediaHostActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                    .putExtra(EXTRA_PACKAGE, pkg).putExtra(EXTRA_GENERATION, token);
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchDisplayId(mediaId);
            app.startActivity(intent, options.toBundle());
        } catch (Exception e) {
            SESSION.end(token);
            Log.e(TAG, "Cannot open media host", e);
            showError(app, "Не удалось открыть медиакарточку");
        }
    }

    static void closeForPackage(String pkg) {
        // Cancel pending creation as well as an already visible host before a physical launch.
        if (!SESSION.cancelPackage(pkg)) return;
        AppDisplayLauncher.cancel(pkg);
        ClusterMediaHostActivity host = active.get();
        if (host != null && pkg.equals(host.packageName)) host.close();
    }

    private static int findMediaDisplayId(Context context) {
        DisplayManager manager = context.getSystemService(DisplayManager.class);
        if (manager != null) {
            for (Display display : manager.getDisplays()) {
                if (MEDIA_DISPLAY_NAME.equals(display.getName())) return display.getDisplayId();
            }
        }
        // Private displays are invisible to DisplayManager until our UID has a task on them.
        // Discover the OEM media task using REAL_GET_TASKS; never assume a fixed display ID.
        try {
            ActivityManager am = context.getSystemService(ActivityManager.class);
            for (ActivityManager.RunningTaskInfo task : am.getRunningTasks(100)) {
                if (task.baseActivity != null
                        && "com.qinggan.instrumentcard".equals(task.baseActivity.getPackageName())
                        && "com.qinggan.instrumentcard.ScreenActivity".equals(task.baseActivity.getClassName())) {
                    int id = task.getClass().getField("displayId").getInt(task);
                    if (id > 1) return id;
                }
            }
        } catch (Exception e) { Log.w(TAG, "Cannot locate OEM media display", e); }
        return -1;
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        packageName = getIntent().getStringExtra(EXTRA_PACKAGE);
        generation = getIntent().getLongExtra(EXTRA_GENERATION, -1);
        Display media = getDisplay();
        Point size = new Point();
        if (media != null) media.getRealSize(size);
        if (!BuildConfig.IS_FULL || !SESSION.owns(generation, packageName)
                || media == null || !MEDIA_DISPLAY_NAME.equals(media.getName())
                || size.x != 1920 || size.y != 720) {
            finishAndRemoveTask();
            return;
        }
        active = new WeakReference<>(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        TextureView texture = new TextureView(this);
        texture.setSurfaceTextureListener(this);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(WIDTH, HEIGHT, Gravity.TOP | Gravity.LEFT);
        params.leftMargin = LEFT;
        params.topMargin = TOP;
        root.addView(texture, params);
        setContentView(root);
    }

    private boolean isCurrent() {
        return !closing && !isFinishing() && !isDestroyed() && SESSION.owns(generation, packageName);
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        if (!isCurrent()) return;
        try {
            texture.setDefaultBufferSize(WIDTH, HEIGHT);
            surface = new Surface(texture);
            DisplayManager manager = getSystemService(DisplayManager.class);
            virtualDisplay = manager.createVirtualDisplay("voyah-cluster-media", WIDTH, HEIGHT, DPI,
                    surface, 1 | 8 | 256 | 1024, new VirtualDisplay.Callback() {
                        @Override public void onStopped() { if (!closing) close(); }
                    }, new Handler(Looper.getMainLooper()));
            if (virtualDisplay == null) throw new IllegalStateException("VirtualDisplay unavailable");
            virtualDisplay.setSurface(surface);
            int displayId = virtualDisplay.getDisplay().getDisplayId();
            AppDisplayLauncher.launch(this, packageName, displayId, true, this::isCurrent, this::launchFailed);
            Log.i(TAG, "host pkg=" + packageName + " display=" + displayId + " bounds=66,200-640,664");
        } catch (Exception e) {
            Log.e(TAG, "Virtual display failed", e);
            launchFailed();
        }
    }

    private void launchFailed() {
        Context app = getApplicationContext();
        close();
        showError(app, "Не удалось открыть приложение в медиакарточке");
    }

    private static void showError(Context context, String message) {
        // Toast is queued asynchronously. The media Activity's private display loses our UID
        // when close() finishes; using that Activity context crashes later in Toast$TN.handleShow.
        // Pin the notification to the stable physical screen, independent of the retired host.
        try {
            Context app = context.getApplicationContext();
            DisplayManager manager = app.getSystemService(DisplayManager.class);
            Display physical = manager == null ? null : manager.getDisplay(Display.DEFAULT_DISPLAY);
            if (physical == null || !physical.isValid()) { Log.w(TAG, message); return; }
            Toast.makeText(app.createDisplayContext(physical), message, Toast.LENGTH_LONG).show();
        } catch (Exception e) { Log.w(TAG, "Cannot show failure notification: " + message, e); }
    }

    private void releaseDisplay() {
        if (virtualDisplay != null) {
            VirtualDisplay old = virtualDisplay;
            virtualDisplay = null;
            try { old.release(); }
            catch (Exception e) { Log.w(TAG, "Display was already removed", e); }
        }
        if (surface != null) {
            try { surface.release(); }
            finally { surface = null; }
        }
    }

    private void close() {
        if (closing) return;
        closing = true;
        SESSION.end(generation);
        AppDisplayLauncher.cancel(packageName);
        releaseDisplay(); // Synchronous: no delayed VD teardown can destroy the returning task.
        finishAndRemoveTask();
    }

    @Override protected void onDestroy() {
        closing = true;
        SESSION.end(generation);
        if (active.get() == this) active = new WeakReference<>(null);
        releaseDisplay();
        super.onDestroy();
    }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) { close(); return true; }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {}
    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) {}
}
