package ru.big.town.anative;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.util.ArrayDeque;

/**
 * Захват ВСЕГО вывода процесса Native в файл + кольцевой буфер для живого просмотра.
 *
 * Механика: запускаем `logcat --pid=<наш pid> -v time` и построчно пишем в файл на
 * sdcard (по умолчанию /sdcard/tmp/voyahtune_native_log.txt) и в кольцевой буфер (последние
 * {@link #RING_MAX} строк) — RestoreMode опрашивает снимок буфера для «живой» ленты.
 * `--pid` ловит весь наш процесс (все сервисы Native в одном процессе), поэтому править
 * каждый Log.* не нужно. Требует READ_LOGS (есть у priv-app по whitelist).
 *
 * Файл шарится через FileProvider (см. shareLogFile в SetModesService).
 */
final class NativeLog {

    private static final String TAG = "$$$ NativeLog $$$";
    private static final int RING_MAX = 600;          // строк в живой ленте
    private static final int FLUSH_EVERY = 25;        // сброс на диск каждые N строк
    static final String FILE_NAME = "voyahtune_native_log.txt";

    private static final NativeLog INSTANCE = new NativeLog();
    static NativeLog get() { return INSTANCE; }
    private NativeLog() {}

    private final ArrayDeque<String> ring = new ArrayDeque<>();
    private Process proc;
    private Thread thread;
    private FileWriter writer;
    private File file;
    private volatile boolean running = false;

    synchronized boolean isRunning() { return running; }

    synchronized File logFile(Context ctx) {
        if (file == null) file = resolveFile(ctx);
        return file;
    }

    /** Снимок последних строк (для живой ленты). */
    synchronized String snapshot() {
        StringBuilder sb = new StringBuilder();
        for (String s : ring) sb.append(s).append('\n');
        return sb.toString();
    }

    synchronized void start(Context ctx) {
        if (running) return;
        file = resolveFile(ctx);
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            writer = new FileWriter(file, false); // truncate — свежая сессия
            String header = "==== Native log start (pid=" + android.os.Process.myPid()
                    + ", file=" + file.getAbsolutePath() + ") ====";
            writer.write(header + "\n");
            writer.flush();
            addRing(header);
        } catch (Exception e) {
            Log.e(TAG, "start: не удалось открыть файл " + e.getMessage());
            writer = null;
        }

        final int pid = android.os.Process.myPid();
        thread = new Thread(() -> pump(pid), "native-log-pump");
        running = true;
        thread.start();
        Log.i(TAG, "logging STARTED → " + file.getAbsolutePath());
    }

    synchronized void stop() {
        if (running) {
            running = false;
            try { if (proc != null) proc.destroy(); } catch (Exception ignored) {}
            proc = null;
            if (thread != null) thread.interrupt();
            thread = null;
            try { if (writer != null) { writer.flush(); writer.close(); } } catch (Exception ignored) {}
            writer = null;
        }
        Log.i(TAG, "logging STOPPED");
    }

    /** Останавливает захват, удаляет файл и очищает живую ленту (при выключении тумблера). */
    synchronized void stopAndDelete(Context ctx) {
        stop();
        File f = (file != null) ? file : resolveFile(ctx);
        try {
            if (f != null && f.exists() && f.delete()) {
                Log.i(TAG, "log file deleted: " + f.getAbsolutePath());
            }
        } catch (Exception e) {
            Log.w(TAG, "stopAndDelete: " + e.getMessage());
        }
        ring.clear();
    }

    private void pump(int pid) {
        BufferedReader r = null;
        try {
            Process p = new ProcessBuilder(
                    "logcat", "--pid=" + pid, "-v", "time")
                    .redirectErrorStream(true).start();
            synchronized (this) { proc = p; }
            r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            int since = 0;
            while (running && (line = r.readLine()) != null) {
                synchronized (this) {
                    addRing(line);
                    if (writer != null) {
                        try {
                            writer.write(line);
                            writer.write('\n');
                            if (++since >= FLUSH_EVERY) { writer.flush(); since = 0; }
                        } catch (Exception e) {
                            Log.e(TAG, "pump write: " + e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "pump: " + e.getMessage());
        } finally {
            try { if (r != null) r.close(); } catch (Exception ignored) {}
            synchronized (this) {
                try { if (writer != null) writer.flush(); } catch (Exception ignored) {}
            }
        }
    }

    private void addRing(String line) {
        ring.addLast(line);
        while (ring.size() > RING_MAX) ring.pollFirst();
    }

    /** /sdcard/tmp/voyahtune_native_log.txt, при недоступности — app-specific external. */
    private File resolveFile(Context ctx) {
        File tmp = new File(Environment.getExternalStorageDirectory(), "tmp");
        if (tmp.exists() || tmp.mkdirs()) {
            return new File(tmp, FILE_NAME);
        }
        File ext = ctx.getExternalFilesDir(null);
        return new File(ext, FILE_NAME);
    }
}
