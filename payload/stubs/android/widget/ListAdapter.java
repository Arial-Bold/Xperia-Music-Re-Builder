package android.widget; public interface ListAdapter { int getCount(); Object getItem(int i); long getItemId(int i); android.view.View getView(int i, android.view.View c, android.view.ViewGroup p); }
