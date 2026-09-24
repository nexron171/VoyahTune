package ru.big.town.restoremode;

import android.os.Bundle;
import android.os.Message;
import android.os.Parcel;
import android.os.ResultReceiver;

/** Only framework Parcelable classes may cross into the separate Native APK. */
final class VoiceCommandMessage {
    static Message create(String session, String op, String action, ResultReceiver reply) {
        Bundle data = new Bundle();
        data.putString("session", session);
        data.putString("op", op);
        data.putString("action", action);
        data.putParcelable("reply", remoteReceiver(reply));
        // Native acknowledges navigation, then waits for the success screen to close.
        data.putInt("resultDisplayMs", 3000);
        Message message = Message.obtain(null, VoiceCommands.MESSAGE);
        message.setData(data);
        return message;
    }

    private static ResultReceiver remoteReceiver(ResultReceiver receiver) {
        if (receiver == null) return null;
        Parcel parcel = Parcel.obtain();
        try {
            // Bundle otherwise writes the anonymous callback's app-local class name.
            // Recreate the framework proxy, retaining the Binder back to that callback.
            receiver.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return ResultReceiver.CREATOR.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
    }
}
