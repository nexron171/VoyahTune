package ru.big.town.restoremode;

import android.view.Choreographer;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/** Emulator-only entry: real overlay and renderer, synthetic voice envelope, no mic or Native. */
public class VoiceAnimationPreviewActivity extends VoiceActivity implements Choreographer.FrameCallback {
    private boolean running;
    private long started;
    private boolean listening = true;

    @Override protected boolean isAnimationPreview() { return true; }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        String[] labels = {"Слушаю", "Распознано", "Успех · 3 с", "Бензобак · 10 с", "Ошибка · 6 с"};
        String[] states = {"listening", "recognized", "success", "fuel", "error"};
        int gap = (int) (8 * getResources().getDisplayMetrics().density);
        for (int i = 0; i < labels.length; i++) {
            String selection = states[i];
            Button button = new Button(this);
            button.setText(labels[i]); button.setAllCaps(false); button.setTextSize(18);
            button.setOnClickListener(v -> {
                listening = "listening".equals(selection);
                previewState(selection);
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
            params.setMargins(gap, 0, gap, 0);
            controls.addView(button, params);
        }
        FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        position.topMargin = gap;
        ((FrameLayout) findViewById(android.R.id.content)).addView(controls, position);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        listening = true;
    }

    @Override protected void onResume() {
        super.onResume();
        if (isFinishing()) return;
        started = 0;
        running = true;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        if (started == 0) started = frameTimeNanos;
        double seconds = (frameTimeNanos - started) / 1_000_000_000.0;
        // Slow phrases + faster syllables keep the orb expanding/contracting continuously.
        double phrase = .5 + .5 * Math.sin(seconds * 2.1);
        double syllable = .5 + .5 * Math.sin(seconds * 9.4 + Math.sin(seconds * 3.7));
        // A short silence in each cycle also exposes the smooth slowdown of the inner motion.
        previewLevel(!listening || seconds % 12 > 9 ? 0 : (float) (.08 + .92 * phrase * (.35 + .65 * syllable)));
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override protected void onPause() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
        super.onPause();
    }
}
