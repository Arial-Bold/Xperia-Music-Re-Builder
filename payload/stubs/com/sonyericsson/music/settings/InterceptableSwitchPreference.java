package com.sonyericsson.music.settings;
public class InterceptableSwitchPreference extends androidx.preference.Preference {
  public interface SwitchInterceptor { void onIntercept(InterceptableSwitchPreference p); }
  public InterceptableSwitchPreference(android.content.Context c){super(c);}
  public boolean isChecked(){return false;} public boolean setChecked(boolean b){return false;} public void setInterceptor(SwitchInterceptor i){} }
