package android.content;
public interface SharedPreferences { String getString(String k, String d); boolean getBoolean(String k, boolean d); Editor edit(); void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l); void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l);
  interface OnSharedPreferenceChangeListener { void onSharedPreferenceChanged(SharedPreferences p, String key); }
  interface Editor { Editor putString(String k, String v); Editor putBoolean(String k, boolean v); void apply(); } }
