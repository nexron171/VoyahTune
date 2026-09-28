package ru.big.town.restoremode;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Messenger;
import android.os.ResultReceiver;

/** Test-only remote endpoint: echoes protocol fields and never sends vehicle commands. */
public class VoiceReplyTestService extends Service {
    @Override public IBinder onBind(Intent intent) {
        return new Messenger(new Handler(Looper.getMainLooper(), message -> {
            Bundle data = message.getData();
            data.setClassLoader(ResultReceiver.class.getClassLoader());
            ResultReceiver reply = data.getParcelable("reply");
            Bundle result = new Bundle();
            result.putString("session", data.getString("session"));
            result.putString("action", data.getString("action"));
            result.putString("op", data.getString("op"));
            result.putInt("displayMs", data.getInt("resultDisplayMs"));
            result.putInt("pid", android.os.Process.myPid());
            reply.send(message.what, result);
            return true;
        })).getBinder();
    }
}
