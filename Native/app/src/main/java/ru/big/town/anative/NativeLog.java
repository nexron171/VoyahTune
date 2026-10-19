package ru.big.town.anative;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import ru.big.town.common.InfrastructureProfile;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.util.ArrayDeque;

/** Process-local recording for the PI logging screen. OD records in its root service. */
final class NativeLog {

    private static final String TAG = "$$$ NativeLog $$$";
    private static final int RING_MAX = 600;
    private static final int FLUSH_EVERY = 25;
    static final String FILE_NAME = "voyahtune_native_log.txt";

    private static final NativeLog INSTANCE = new NativeLog();

    static NativeLog get() {
        return INSTANCE;
    }

    private NativeLog() {}

    private final ArrayDeque<String> ring = new ArrayDeque<>();
    private Process process;
    private Thread thread;
    private FileWriter writer;
    private File file;
    private volatile boolean running = false;

    synchronized boolean isRunning() {
        return running;
    }

    synchronized File logFile(Context context) {
        if (file == null) file = resolveFile(context);
        return file;
    }

    /** Snapshot for the live view. */
    synchronized String snapshot() {
        StringBuilder snapshot = new StringBuilder();
        for (String line : ring) snapshot.append(line).append('\n');
        return snapshot.toString();
    }

    synchronized void start(Context context) {
        if (running || InfrastructureProfile.read(context) == InfrastructureProfile.OD) {
            return;
        }
        file = resolveFile(context);
        try {
            File directory = file.getParentFile();
            if (directory != null && !directory.exists()) directory.mkdirs();
            writer = new FileWriter(file, false);
            String header =
                    "==== Native log start (pid="
                            + android.os.Process.myPid()
                            + ", file="
                            + file.getAbsolutePath()
                            + ") ====";
            writer.write(header + "\n");
            writer.flush();
            addRing(header);
        } catch (Exception error) {
            Log.e(TAG, "start: не удалось открыть файл " + error.getMessage());
            writer = null;
        }

        final int processId = android.os.Process.myPid();
        thread = new Thread(() -> pump(processId), "native-log-pump");
        running = true;
        thread.start();
        Log.i(TAG, "logging STARTED → " + file.getAbsolutePath());
    }

    synchronized void stop() {
        if (running) {
            running = false;
            try {
                if (process != null) process.destroy();
            } catch (Exception ignored) {
            }
            process = null;
            if (thread != null) thread.interrupt();
            thread = null;
            try {
                if (writer != null) {
                    writer.flush();
                    writer.close();
                }
            } catch (Exception ignored) {
            }
            writer = null;
        }
        Log.i(TAG, "logging STOPPED");
    }

    /** Turning off the PI switch clears the file and the live view. */
    synchronized void stopAndDelete(Context context) {
        stop();
        File logFile = (file != null) ? file : resolveFile(context);
        try {
            if (logFile != null && logFile.exists() && logFile.delete()) {
                Log.i(TAG, "log file deleted: " + logFile.getAbsolutePath());
            }
        } catch (Exception error) {
            Log.w(TAG, "stopAndDelete: " + error.getMessage());
        }
        ring.clear();
    }

    private void pump(int processId) {
        BufferedReader reader = null;
        try {
            Process captureProcess =
                    new ProcessBuilder("logcat", "--pid=" + processId, "-v", "time")
                            .redirectErrorStream(true)
                            .start();
            synchronized (this) {
                process = captureProcess;
            }
            reader = new BufferedReader(new InputStreamReader(captureProcess.getInputStream()));
            String line;
            int linesSinceFlush = 0;
            while (running && (line = reader.readLine()) != null) {
                synchronized (this) {
                    addRing(line);
                    if (writer != null) {
                        try {
                            writer.write(line);
                            writer.write('\n');
                            if (++linesSinceFlush >= FLUSH_EVERY) {
                                writer.flush();
                                linesSinceFlush = 0;
                            }
                        } catch (Exception error) {
                            Log.e(TAG, "pump write: " + error.getMessage());
                        }
                    }
                }
            }
        } catch (Exception error) {
            Log.e(TAG, "pump: " + error.getMessage());
        } finally {
            try {
                if (reader != null) reader.close();
            } catch (Exception ignored) {
            }
            synchronized (this) {
                try {
                    if (writer != null) writer.flush();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void addRing(String line) {
        ring.addLast(line);
        while (ring.size() > RING_MAX) ring.pollFirst();
    }

    /** Fall back to app-specific storage when the shared directory is unavailable. */
    private File resolveFile(Context context) {
        File directory = new File(Environment.getExternalStorageDirectory(), "tmp");
        if (directory.exists() || directory.mkdirs()) {
            return new File(directory, FILE_NAME);
        }
        File externalDirectory = context.getExternalFilesDir(null);
        return new File(externalDirectory, FILE_NAME);
    }
}
