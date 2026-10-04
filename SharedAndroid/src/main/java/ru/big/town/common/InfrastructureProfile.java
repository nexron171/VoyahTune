package ru.big.town.common;

import android.content.Context;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Build-time infrastructure identity from the APK's signed assets, never a user setting. */
public enum InfrastructureProfile {
    PI, OD;

    private static volatile InfrastructureProfile cached;

    public boolean usesAccHooks() { return this == OD; }

    public static InfrastructureProfile fromValue(String value) {
        if ("pi".equals(value)) return PI;
        if ("od".equals(value)) return OD;
        throw new IllegalArgumentException("Unknown VoyahTune infrastructure: " + value);
    }

    public static InfrastructureProfile read(Context context) {
        InfrastructureProfile profile = cached;
        if (profile != null) return profile;
        // Local JVM callers may not have Android. Real builds always contain this asset.
        if (context == null) return OD;
        synchronized (InfrastructureProfile.class) {
            if (cached != null) return cached;
            try (InputStream input = context.getAssets().open("voyahtune-build.json")) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    if (output.size() > 64 * 1024) throw new IllegalStateException("Build identity too large");
                }
                JSONObject identity = new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
                cached = fromValue(identity.getString("infrastructure"));
                return cached;
            } catch (Exception error) {
                // A damaged release must not silently choose the other vehicle's restore mechanism.
                throw new IllegalStateException("Cannot read VoyahTune infrastructure identity", error);
            }
        }
    }
}
