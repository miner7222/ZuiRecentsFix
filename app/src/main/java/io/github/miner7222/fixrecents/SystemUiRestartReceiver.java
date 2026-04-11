package io.github.miner7222.fixrecents;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

public class SystemUiRestartReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(@NonNull Context context, Intent intent) {
        if (intent == null) return;
        if (!BootWorkaround.getRestartAction().equals(intent.getAction())) return;

        var pendingResult = goAsync();
        new Thread(() -> {
            try {
                BootWorkaround.restartSystemUiOnce(context);
            } finally {
                pendingResult.finish();
            }
        }, "fixrecents-systemui-restart").start();
    }
}
