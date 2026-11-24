package com.fpmod.dsd;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

/**
 * Keeps the app running while the ReplayGain scan works. Android (and Samsung's battery
 * management especially) freezes apps that aren't on screen within seconds unless they run a
 * foreground service, which shows an ongoing notification: here, the scan's own progress
 * notification. Also holds a partial wake lock so the processor keeps working with the screen off.
 * The scan itself runs on its own threads; this service only keeps the process alive.
 * Declared in the manifest by the Re-Builder (foregroundServiceType="dataSync").
 */
public final class RgScanService extends Service {
    private static final String TAG = "RgScanService";
    private static final int TYPE_DATA_SYNC = 1;        // ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    private static volatile RgScanService instance;
    private static PowerManager.WakeLock fallbackLock;  // used if the service couldn't be started
    private PowerManager.WakeLock lock;

    /** Called when a scan starts. */
    static void begin(Context ctx) {
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        try {
            app.startForegroundService(new Intent(app, RgScanService.class));
        } catch (Throwable e) {
            // not declared (older build) or not allowed right now: at least keep the processor awake
            Log.w(TAG, "Couldn't start the foreground service; the scan may pause in the background", e);
            synchronized (RgScanService.class) {
                if (fallbackLock == null) fallbackLock = newLock(app);
                if (fallbackLock != null && !fallbackLock.isHeld()) fallbackLock.acquire(6L * 3600 * 1000);
            }
        }
    }

    /** Called when the scan has finished or stopped. Leaves the notification in place. */
    static void end(Context ctx) {
        RgScanService s = instance;
        if (s != null) {
            try { s.stopForeground(STOP_FOREGROUND_DETACH); } catch (Throwable ignored) { }
            try { s.stopSelf(); } catch (Throwable ignored) { }
        }
        synchronized (RgScanService.class) {
            if (fallbackLock != null && fallbackLock.isHeld()) try { fallbackLock.release(); } catch (Throwable ignored) { }
        }
    }

    private static PowerManager.WakeLock newLock(Context c) {
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return null;
            PowerManager.WakeLock l = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fpmod:replaygain-scan");
            l.setReferenceCounted(false);
            return l;
        } catch (Throwable e) {
            return null;
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        instance = this;
        try {
            startForeground(RgScan.NOTICE_ID, RgScan.buildNotice(this, "Starting…", 0, 0, true), TYPE_DATA_SYNC);
        } catch (Throwable e) {
            Log.w(TAG, "startForeground failed", e);
        }
        if (lock == null) lock = newLock(this);
        if (lock != null && !lock.isHeld()) lock.acquire(6L * 3600 * 1000);
        if (!RgScan.isRunning()) end(this);                 // started late, after the scan already ended
        return START_NOT_STICKY;
    }

    /** Android 15 limits data-sync services to 6 hours a day; stop cleanly when told to. */
    @Override public void onTimeout(int startId, int fgsType) {
        end(this);
    }

    @Override public void onDestroy() {
        if (lock != null && lock.isHeld()) try { lock.release(); } catch (Throwable ignored) { }
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
