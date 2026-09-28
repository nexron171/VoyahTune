package ru.big.town.restoremode;

/** Stateful anti-alias FIR, 48 kHz -> 16 kHz. Input frames are exactly 10 ms. */
final class VoiceDecimator {
    private static final int TAPS = 63;
    private static final double[] COEFFICIENTS = coefficients();
    private final float[] history = new float[TAPS];
    private int cursor, phase;

    void process(float[] input, float[] output) {
        if (input.length != 480 || output.length != 160) throw new IllegalArgumentException("10 ms frames required");
        int out = 0;
        for (float sample : input) {
            history[cursor] = sample;
            if (++phase == 3) {
                phase = 0;
                double sum = 0;
                int index = cursor;
                for (double coefficient : COEFFICIENTS) {
                    sum += history[index] * coefficient;
                    if (--index < 0) index = TAPS - 1;
                }
                output[out++] = (float) sum;
            }
            if (++cursor == TAPS) cursor = 0;
        }
    }
    private static double[] coefficients() {
        double[] result = new double[TAPS];
        double sum = 0, cutoff = 7000.0 / 48000;
        for (int i = 0; i < TAPS; i++) {
            int x = i - (TAPS - 1) / 2;
            double sinc = x == 0 ? 2 * cutoff : Math.sin(2 * Math.PI * cutoff * x) / (Math.PI * x);
            result[i] = sinc * (0.54 - 0.46 * Math.cos(2 * Math.PI * i / (TAPS - 1)));
            sum += result[i];
        }
        for (int i = 0; i < TAPS; i++) result[i] /= sum;
        return result;
    }
}
