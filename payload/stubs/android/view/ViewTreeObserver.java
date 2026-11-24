package android.view; public final class ViewTreeObserver { public interface OnPreDrawListener { boolean onPreDraw(); } public void addOnPreDrawListener(OnPreDrawListener l){} }
