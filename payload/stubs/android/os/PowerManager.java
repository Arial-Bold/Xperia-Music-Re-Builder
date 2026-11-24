package android.os; public final class PowerManager { public static final int PARTIAL_WAKE_LOCK = 1;
 public WakeLock newWakeLock(int f, String t) { return null; }
 public final class WakeLock { public void acquire(long t) {} public void release() {} public boolean isHeld() { return false; } public void setReferenceCounted(boolean v) {} } }
