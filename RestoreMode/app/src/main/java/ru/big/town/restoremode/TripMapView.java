package ru.big.town.restoremode;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.LruCache;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native OSM viewport: only visible tiles, seven-day disk cache, no GPS input or upload. */
final class TripMapView extends View {
    private static final ExecutorService TILES = Executors.newFixedThreadPool(2);
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getByteCount(); }
    };
    private final List<double[]> points;
    private final double[][] xy;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tilePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path roundedViewport = new Path();
    private final ScaleGestureDetector scaleDetector;
    private final Set<String> requested = new HashSet<>();
    private final File cacheDir;
    private double zoom = 12;
    private double centerX, centerY;
    private float lastX, lastY, downX, downY;
    private float scaleFocusX, scaleFocusY;
    private int activePointerId = -1;
    private boolean gestureMoved;
    private String selection = "";
    private volatile boolean tileFailed;
    TripMapView(Context c, List<double[]> points) {
        super(c); this.points = points; xy = new double[points.size()][2];
        // Dark basemap palette; keep original tiles in the shared caches and route colors intact.
        tilePaint.setColorFilter(new ColorMatrixColorFilter(new float[]{
                -.15945f, -.5364f, -.05415f, 0, 210,
                -.15945f, -.5364f, -.05415f, 0, 216,
                -.15945f, -.5364f, -.05415f, 0, 226,
                0, 0, 0, 1, 0
        }));
        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                gestureMoved = true;
                scaleFocusX = detector.getFocusX(); scaleFocusY = detector.getFocusY();
                return true;
            }
            @Override public boolean onScale(ScaleGestureDetector detector) {
                double factor = detector.getScaleFactor();
                if (!Double.isFinite(factor) || factor <= 0) return false;
                float density = getResources().getDisplayMetrics().density;
                double previousWorld = world(density);
                double focusX = centerX + (scaleFocusX - getWidth() / 2d) / previousWorld;
                double focusY = centerY + (scaleFocusY - getHeight() / 2d) / previousWorld;
                zoom = Math.max(2, Math.min(18, zoom + Math.log(factor) / Math.log(2)));
                scaleFocusX = detector.getFocusX(); scaleFocusY = detector.getFocusY();
                double currentWorld = world(density);
                centerX = focusX - (scaleFocusX - getWidth() / 2d) / currentWorld;
                centerY = Math.max(0, Math.min(1, focusY - (scaleFocusY - getHeight() / 2d) / currentWorld));
                invalidate();
                return true;
            }
        });
        scaleDetector.setQuickScaleEnabled(false);
        cacheDir = new File(c.getCacheDir(), "trip-map-tiles");
        for (int i = 0; i < points.size(); i++) {
            double[] p = points.get(i); double lat = Math.max(-85.0511, Math.min(85.0511, p[2]));
            double x = (p[3] + 180) / 360;
            if (i > 0) { while (x - xy[i - 1][0] > .5) x -= 1; while (x - xy[i - 1][0] < -.5) x += 1; }
            xy[i][0] = x; xy[i][1] = (1 - Math.log(Math.tan(Math.PI / 4 + Math.toRadians(lat) / 2)) / Math.PI) / 2;
        }
        setContentDescription("Карта маршрута. Цвет участка показывает среднюю скорость по одометру. Перемещение одним пальцем и масштабирование двумя пальцами.");
    }
    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int side = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
                ? Math.round(400 * getResources().getDisplayMetrics().density) : MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(side, side);
    }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        double minX = xy[0][0], maxX = minX, minY = xy[0][1], maxY = minY;
        for (double[] p : xy) { minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]); minY = Math.min(minY, p[1]); maxY = Math.max(maxY, p[1]); }
        centerX = (minX + maxX) / 2; centerY = (minY + maxY) / 2;
        float density = getResources().getDisplayMetrics().density;
        roundedViewport.reset();
        roundedViewport.addRoundRect(new RectF(0, 0, w, h), 20 * density, 20 * density, Path.Direction.CW);
        zoom = 16;
        while (zoom > 2 && ((maxX - minX) * world(density) > w - 64 * density || (maxY - minY) * world(density) > h - 64 * density)) zoom--;
    }
    private double world(float density) { return Math.pow(2, zoom) * 256d * density; }
    private float screenX(int i, double world) { return (float)((xy[i][0] - centerX) * world + getWidth() / 2d); }
    private float screenY(int i, double world) { return (float)((xy[i][1] - centerY) * world + getHeight() / 2d); }
    @Override protected void onDraw(Canvas c) {
        int saved = c.save();
        c.clipPath(roundedViewport);
        float d = getResources().getDisplayMetrics().density;
        double world = world(d); int tileZoom = (int)Math.floor(zoom), n = 1 << tileZoom;
        float tile = (float)(world / n);
        c.drawColor(0xff242e3c);
        int left = (int)Math.floor(centerX * n - getWidth() / (2 * tile)), top = (int)Math.floor(centerY * n - getHeight() / (2 * tile));
        int right = (int)Math.floor(centerX * n + getWidth() / (2 * tile)), bottom = (int)Math.floor(centerY * n + getHeight() / (2 * tile));
        int ready = 0;
        for (int x = left; x <= right; x++) for (int y = top; y <= bottom; y++) {
            if (y < 0 || y >= n) continue;
            int wrapped = ((x % n) + n) % n;
            String key = tileZoom + "/" + wrapped + "/" + y;
            float px = (float)((x / (double)n - centerX) * world + getWidth() / 2d), py = (float)((y / (double)n - centerY) * world + getHeight() / 2d);
            Bitmap bitmap = CACHE.get(key);
            if (bitmap != null) { c.drawBitmap(bitmap, null, new RectF(px, py, px + tile, py + tile), tilePaint); ready++; }
            else requestTile(key);
        }
        paint.setStrokeCap(Paint.Cap.ROUND);
        // Draw every outline first: short segments otherwise cover the previous segment's color.
        for (int pass = 0; pass < 2; pass++) {
            paint.setStrokeWidth((pass == 0 ? 8 : 5) * d);
            for (int i = 1; i < xy.length; i++) if (TripHistoryPresentation.drawableSegment(points.get(i - 1), points.get(i))) {
                paint.setColor(pass == 0 ? 0xff22252b : TripHistoryPresentation.speedColor(points.get(i)[6]));
                c.drawLine(screenX(i - 1, world), screenY(i - 1, world), screenX(i, world), screenY(i, world), paint);
            }
        }
        marker(c, 0, world, 0xff64d8ff, d); marker(c, xy.length - 1, world, 0xfff04444, d);
        paint.setColor(0xcc18202c); c.drawRect(0, getHeight() - 28 * d, getWidth(), getHeight(), paint);
        text(c, "© OpenStreetMap contributors", 10 * d, getHeight() - 9 * d, 12 * d);
        text(c, selection.isEmpty() ? (ready == 0 ? (tileFailed ? "Подложка недоступна · трек доступен без сети" : "Подложка загружается · трек доступен без сети") : "Нажмите на участок: скорость и время") : selection,
                12 * d, 25 * d, 13 * d);
        c.restoreToCount(saved);
    }
    private void marker(Canvas c, int i, double world, int color, float d) {
        paint.setColor(0xff22252b); c.drawCircle(screenX(i, world), screenY(i, world), 7 * d, paint);
        paint.setColor(color); c.drawCircle(screenX(i, world), screenY(i, world), 5 * d, paint);
    }
    private void text(Canvas c, String text, float x, float y, float size) { paint.setColor(0xffeeeeee); paint.setTextSize(size); c.drawText(text, x, y, paint); }
    private void requestTile(String key) {
        if (!isAttachedToWindow() || !requested.add(key)) return;
        TILES.execute(() -> {
            Bitmap bitmap = null; File file = new File(cacheDir, key.replace('/', '_') + ".png");
            try {
                cacheDir.mkdirs();
                if (file.exists() && System.currentTimeMillis() - file.lastModified() < 7 * 24 * 60 * 60 * 1000L) bitmap = BitmapFactory.decodeFile(file.getPath());
                if (bitmap == null) {
                    HttpURLConnection connection = (HttpURLConnection)new URL("https://tile.openstreetmap.org/" + key + ".png").openConnection();
                    connection.setRequestProperty("User-Agent", "VoyahTune/" + BuildConfig.VERSION_NAME + " Android TripHistory");
                    connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
                    if (file.exists()) connection.setIfModifiedSince(file.lastModified());
                    try {
                        int status = connection.getResponseCode();
                        if (status == 304) { file.setLastModified(System.currentTimeMillis()); bitmap = BitmapFactory.decodeFile(file.getPath()); }
                        else if (status == 200) {
                            try (InputStream in = connection.getInputStream()) { bitmap = BitmapFactory.decodeStream(in); }
                            if (bitmap != null) try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
                        }
                    } finally { connection.disconnect(); }
                }
            } catch (Exception unavailable) { bitmap = BitmapFactory.decodeFile(file.getPath()); }
            if (bitmap != null) CACHE.put(key, bitmap); else tileFailed = true;
            postInvalidate();
        });
    }
    @Override public boolean onTouchEvent(MotionEvent e) {
        float d = getResources().getDisplayMetrics().density;
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            activePointerId = e.getPointerId(0); gestureMoved = false;
            lastX = downX = e.getX(); lastY = downY = e.getY();
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        }
        scaleDetector.onTouchEvent(e);
        if (action == MotionEvent.ACTION_POINTER_DOWN) gestureMoved = true;
        if (action == MotionEvent.ACTION_POINTER_UP) {
            int lifted = e.getActionIndex();
            int remaining = e.getPointerId(lifted) == activePointerId ? (lifted == 0 ? 1 : 0) : e.findPointerIndex(activePointerId);
            if (remaining >= 0) {
                activePointerId = e.getPointerId(remaining);
                lastX = e.getX(remaining); lastY = e.getY(remaining);
            }
        }
        if (action == MotionEvent.ACTION_MOVE) {
            int pointer = e.findPointerIndex(activePointerId);
            if (pointer < 0) return true;
            float x = e.getX(pointer), y = e.getY(pointer);
            if (e.getPointerCount() == 1 && !scaleDetector.isInProgress()) {
                centerX -= (x - lastX) / world(d); centerY -= (y - lastY) / world(d);
                centerY = Math.max(0, Math.min(1, centerY));
                gestureMoved |= Math.hypot(x - downX, y - downY) >= 8 * d;
                invalidate();
            }
            lastX = x; lastY = y;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            activePointerId = -1;
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
            if (action == MotionEvent.ACTION_UP && !gestureMoved && Math.hypot(e.getX() - downX, e.getY() - downY) < 8 * d) {
                if (e.getY() >= getHeight() - 28 * d)
                    getContext().startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.openstreetmap.org/copyright")));
                else select(e.getX(), e.getY(), d);
                performClick(); invalidate();
            }
            return true;
        }
        return true;
    }
    private void select(float x, float y, float d) {
        int selected = -1; double best = 30 * d, world = world(d);
        for (int i = 1; i < xy.length; i++) if (TripHistoryPresentation.drawableSegment(points.get(i - 1), points.get(i))) {
            float ax = screenX(i - 1, world), ay = screenY(i - 1, world), bx = screenX(i, world), by = screenY(i, world);
            double dx = bx - ax, dy = by - ay, length = dx * dx + dy * dy;
            double t = length > 0 ? Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy) / length)) : 0;
            double dist = Math.hypot(x - ax - t * dx, y - ay - t * dy);
            if (dist < best) { best = dist; selected = i; }
        }
        if (selected >= 0) { double[] p = points.get(selected); selection = String.format(Locale.getDefault(), "%.0f м · %.1f с · %.1f км/ч", p[4], p[5] / 1000, p[6]); }
    }
    @Override public boolean performClick() { super.performClick(); return true; }
}
