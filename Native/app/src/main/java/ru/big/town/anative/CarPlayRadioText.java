package ru.big.town.anative;

import android.content.Context;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;

import java.io.FileInputStream;
import java.security.MessageDigest;

/** Read-only RDS getter from VoyahPlay, gated by the exact reviewed OEM APK. */
final class CarPlayRadioText {
    private static final String SDK_SHA256 =
            "36b4d73a889aa78325f6a6ff0c8113c6b13605620a5a4b045209fbff36449cca";
    private final Context context;
    private Boolean compatible;

    CarPlayRadioText(Context context) {
        this.context = context;
    }

    Bundle read() {
        try {
            if (compatible == null) compatible = verify();
            if (!compatible) return null;
            IBinder binder =
                    (IBinder)
                            Class.forName("android.os.ServiceManager")
                                    .getMethod("getService", String.class)
                                    .invoke(null, "adayo.dab");
            if (binder == null) return null;
            Parcel request = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                request.writeInterfaceToken("com.adayo.proxy.dab.aidl.IDABServiceInterface");
                return binder.transact(8, request, reply, 0) ? decode(reply) : null;
            } finally {
                reply.recycle();
                request.recycle();
            }
        } catch (Exception unavailable) {
            return null;
        }
    }

    private boolean verify() throws Exception {
        String path =
                context.getPackageManager().getApplicationInfo("com.pateo.rdsapp", 0).sourceDir;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(path)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
        }
        StringBuilder hash = new StringBuilder();
        for (byte value : digest.digest()) hash.append(String.format("%02x", value));
        return SDK_SHA256.equals(hash.toString());
    }

    static Bundle decode(Parcel reply) {
        require(reply.dataAvail() >= 8 && reply.dataAvail() <= 4096);
        reply.readException();
        int present = integer(reply);
        require(present == 0 || present == 1);
        if (present == 0) {
            require(reply.dataAvail() == 0);
            return null;
        }
        integer(reply);
        int rds = flag(reply);
        string(reply);
        int frequency = integer(reply);
        require(frequency > 0);
        int fm = flag(reply);
        for (int index = 0; index < 3; index++) flag(reply);
        integer(reply);
        for (int index = 0; index < 4; index++) flag(reply);
        integer(reply);
        string(reply);
        String text = string(reply);
        integer(reply);
        require(reply.dataAvail() == 0);
        Bundle result = new Bundle();
        result.putString("band", fm == 1 ? "FM" : "AM");
        result.putLong("frequency", frequency);
        result.putString("text", rds == 1 ? text : null);
        return result;
    }

    private static int integer(Parcel reply) {
        require(reply.dataAvail() >= 4);
        return reply.readInt();
    }

    private static int flag(Parcel reply) {
        require(reply.dataAvail() >= 4);
        int value = reply.readByte();
        require(value == 0 || value == 1);
        return value;
    }

    private static String string(Parcel reply) {
        require(reply.dataAvail() >= 4);
        String value = reply.readString();
        require(value == null || value.length() <= 256);
        return value;
    }

    private static void require(boolean condition) {
        if (!condition) throw new IllegalArgumentException("RDS layout");
    }
}
