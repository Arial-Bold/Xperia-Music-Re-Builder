package androidx.preference;
public class Preference {
  public interface OnPreferenceClickListener { boolean onPreferenceClick(Preference p); }
  public Preference(android.content.Context c) {}
  public android.content.Context getContext() { return null; }
  public void setKey(String k) {} public void setPersistent(boolean b) {} public void setTitle(CharSequence t) {}
  public void setSummary(CharSequence s) {} public void setIconSpaceReserved(boolean b) {}
  public void setOnPreferenceClickListener(OnPreferenceClickListener l) {}
  public void setOrder(int o) {} public int getOrder() { return 0; } public void setEnabled(boolean e) {} public final void setVisible(boolean v) {}
}
