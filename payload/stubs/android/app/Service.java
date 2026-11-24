package android.app; public abstract class Service extends android.content.ContextWrapper {
 public static final int START_NOT_STICKY = 2, STOP_FOREGROUND_REMOVE = 1, STOP_FOREGROUND_DETACH = 2;
 public Service() { super(null); }
 public void onCreate() {} public int onStartCommand(android.content.Intent i, int f, int id) { return 0; } public void onDestroy() {}
 public abstract android.os.IBinder onBind(android.content.Intent i);
 public final void startForeground(int id, Notification n, int type) {} public final void stopForeground(int flags) {} public final void stopSelf() {}
 public void onTimeout(int startId, int fgsType) {} }
