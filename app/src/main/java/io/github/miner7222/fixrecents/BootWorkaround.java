package io.github.miner7222.fixrecents;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

final class BootWorkaround {
    private static final String TAG = "ZuiRecentsFix";
    private static final String ACTION_RESTART_SYSTEMUI =
            "io.github.miner7222.fixrecents.action.RESTART_SYSTEMUI";
    private static final String PREFS_NAME = "boot_workaround";
    private static final String KEY_LAST_SCHEDULED_BOOT = "last_scheduled_boot";
    private static final String KEY_LAST_RESTARTED_BOOT = "last_restarted_boot";
    private static final long RESTART_DELAY_MS = TimeUnit.SECONDS.toMillis(3);

    private BootWorkaround() {
    }

    static void scheduleSystemUiRestart(@NonNull Context context) {
        var appContext = context.getApplicationContext();
        var bootCount = getBootCount(appContext);
        var prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        if (bootCount >= 0 && prefs.getInt(KEY_LAST_SCHEDULED_BOOT, -1) == bootCount) {
            Log.i(TAG, "Boot workaround already scheduled for boot " + bootCount);
            return;
        }

        var alarmManager = appContext.getSystemService(AlarmManager.class);
        if (alarmManager == null) {
            Log.w(TAG, "Cannot schedule SystemUI restart workaround: no AlarmManager");
            return;
        }

        alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + RESTART_DELAY_MS,
                buildRestartPendingIntent(appContext)
        );

        if (bootCount >= 0) {
            prefs.edit().putInt(KEY_LAST_SCHEDULED_BOOT, bootCount).apply();
        }

        Log.i(TAG, "Scheduled SystemUI restart workaround for boot " + bootCount);
    }

    static void restartSystemUiOnce(@NonNull Context context) {
        var appContext = context.getApplicationContext();
        var bootCount = getBootCount(appContext);
        var prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        if (bootCount >= 0 && prefs.getInt(KEY_LAST_RESTARTED_BOOT, -1) == bootCount) {
            Log.i(TAG, "SystemUI restart workaround already ran for boot " + bootCount);
            return;
        }

        var command = "pidof com.android.systemui >/dev/null 2>&1 || exit 0; "
                + "killall com.android.systemui || pkill -TERM -f com.android.systemui";

        if (!runRootCommand(command)) {
            Log.w(TAG, "SystemUI restart workaround failed. Grant root to ZuiRecentsFix.");
            return;
        }

        if (bootCount >= 0) {
            prefs.edit().putInt(KEY_LAST_RESTARTED_BOOT, bootCount).apply();
        }

        Log.i(TAG, "Restarted SystemUI once for boot " + bootCount);
    }

    private static PendingIntent buildRestartPendingIntent(Context context) {
        var intent = new Intent(ACTION_RESTART_SYSTEMUI)
                .setComponent(new ComponentName(context, SystemUiRestartReceiver.class))
                .setPackage(context.getPackageName());

        return PendingIntent.getBroadcast(
                context,
                1001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static int getBootCount(Context context) {
        try {
            return Settings.Global.getInt(
                    context.getContentResolver(),
                    Settings.Global.BOOT_COUNT,
                    -1
            );
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static boolean runRootCommand(String command) {
        try {
            var process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    static String getRestartAction() {
        return ACTION_RESTART_SYSTEMUI;
    }
}
