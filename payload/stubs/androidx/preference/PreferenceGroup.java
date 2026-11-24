package androidx.preference;
public abstract class PreferenceGroup extends Preference { public PreferenceGroup(android.content.Context c){super(c);} public boolean addPreference(Preference p){return true;} public int getPreferenceCount(){return 0;} public Preference getPreference(int i){return null;} }
