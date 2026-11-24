package com.fpmod.dsd;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;

/** Adds the ReplayGain rows to the app's Settings screen, built in code. */
public final class FpSettings {
    private static final String TAG = "FpSettings";
    private static final String CATEGORY = "pref_key_music_settings_music_quality";

    private static final String[] MODE_LABELS = {"Off", "Track gain", "Album gain"};
    private static final String[] MODE_VALUES = {"off", "track", "album"};
    private static final String[] DB_VALUES = {"+6", "+4", "+2", "0", "-2", "-4", "-6", "-8", "-10", "-12"};

    private FpSettings() { }

    private static final String[] MODE_DESC = {
            "Songs play at their original volume.",
            "Every song plays at about the same loudness. Best for shuffle and playlists.",
            "Keeps each album's loud and quiet songs the way they were mastered, while albums play at about the same loudness. Best for listening to whole albums."};

    public static void addTo(PreferenceFragmentCompat fragment) {
        try {
            PreferenceGroup screen = fragment.getPreferenceScreen();
            Preference quality = fragment.findPreference(CATEGORY);
            if (screen == null) return;
            final Context ctx = screen.getContext();
            SharedPreferences sp = ctx.getSharedPreferences(ctx.getPackageName() + "_preferences", Context.MODE_PRIVATE);

            // DSD software decoding switch, styled like Sony's own switch rows.
            if (quality instanceof PreferenceGroup) {
                final com.sonyericsson.music.settings.InterceptableSwitchPreference dsd =
                        new com.sonyericsson.music.settings.InterceptableSwitchPreference(ctx);
                final SharedPreferences fsp = sp;
                dsd.setKey(DsdSupport.KEY_ENABLED);
                dsd.setPersistent(false);
                dsd.setTitle("Software DSD decoding");
                dsd.setSummary("Converts DSD so any phone can play it. Turn off if your phone decodes DSD itself.");
                dsd.setChecked(sp.getBoolean(DsdSupport.KEY_ENABLED, true));
                dsd.setInterceptor(new com.sonyericsson.music.settings.InterceptableSwitchPreference.SwitchInterceptor() {
                    @Override public void onIntercept(com.sonyericsson.music.settings.InterceptableSwitchPreference p) {
                        boolean on = !p.isChecked();
                        p.setChecked(on);
                        fsp.edit().putBoolean(DsdSupport.KEY_ENABLED, on).apply();
                    }
                });
                ((PreferenceGroup) quality).addPreference(dsd);
            }

            // Space out the existing sections so ours can sit right after "Audio quality".
            for (int i = 0; i < screen.getPreferenceCount(); i++) screen.getPreference(i).setOrder(i * 10);
            PreferenceCategory cat = new PreferenceCategory(ctx, null);
            cat.setKey("fpmod_rg_category");
            cat.setTitle("ReplayGain");
            cat.setOrder(quality != null ? quality.getOrder() + 5 : 5);
            screen.addPreference(cat);

            String[] dbLabels = new String[DB_VALUES.length];
            for (int i = 0; i < DB_VALUES.length; i++) dbLabels[i] = DB_VALUES[i].replace("-", "\u2212") + " dB";

            final Preference preamp = choice(ctx, sp, ReplayGain.KEY_PREAMP, "Pre-amp", "0", dbLabels, DB_VALUES,
                    "Raises or lowers all ReplayGain volumes, never above a song's original level.", null);
            final Preference untagged = choice(ctx, sp, ReplayGain.KEY_UNTAGGED, "Songs without ReplayGain", "0", dbLabels, DB_VALUES,
                    "Volume for untagged songs. About \u22126 to \u22128 dB matches tagged songs.", null);
            final Preference[] dependents = {preamp, untagged};
            Preference mode = choice(ctx, sp, ReplayGain.KEY_MODE, "ReplayGain", "off", MODE_LABELS, MODE_VALUES,
                    "Evens out volume between songs. Track suits shuffle, Album keeps albums as mastered.", null);
            setDependentsEnabled(dependents, !"off".equals(sp.getString(ReplayGain.KEY_MODE, "off")));
            modeDependents = dependents;

            cat.addPreference(mode);
            cat.addPreference(preamp);
            cat.addPreference(untagged);

            final Preference scan = new Preference(ctx);
            scan.setKey("fpmod_rg_scan");
            scan.setPersistent(false);
            scan.setTitle("Add ReplayGain to files");
            scan.setSummary("Measures songs without ReplayGain and writes the tags into the files.");
            scan.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override public boolean onPreferenceClick(Preference pref) { RgScan.start(ctx); return true; }
            });
            cat.addPreference(scan);

            // "Service settings" only holds an empty row for a service app that isn't installed.
            Preference service = fragment.findPreference("pref_key_service_settings");
            if (service != null) service.setVisible(false);

            // Sony's rows reserve room for icons that never appear; line everything up flush left.
            noIconSpace(screen);
        } catch (Throwable e) {
            Log.w(TAG, "Couldn't add ReplayGain settings", e);
        }
    }

    private static Preference[] modeDependents;

    private static void setDependentsEnabled(Preference[] ps, boolean on) {
        for (Preference p : ps) p.setEnabled(on);
    }

    /** A row showing the current choice on the first summary line and an explanation under it. */
    private static Preference choice(final Context ctx, final SharedPreferences sp, final String key, final String title,
                                     final String def, final String[] labels, final String[] values,
                                     final String desc, final String[] perValueDesc) {
        final Preference p = new Preference(ctx);
        p.setKey(key);
        p.setPersistent(false);
        p.setTitle(title);
        p.setIconSpaceReserved(false);
        p.setSummary(describe(labels, values, index(values, sp.getString(key, def)), desc, perValueDesc));
        p.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override public boolean onPreferenceClick(Preference pref) {
                int cur = index(values, sp.getString(key, def));
                new AlertDialog.Builder(ctx)
                        .setTitle(title)
                        .setSingleChoiceItems(labels, cur, new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int which) {
                                sp.edit().putString(key, values[which]).apply();
                                p.setSummary(describe(labels, values, which, desc, perValueDesc));
                                if (key.equals(ReplayGain.KEY_MODE) && modeDependents != null)
                                    setDependentsEnabled(modeDependents, !"off".equals(values[which]));
                                d.dismiss();
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                return true;
            }
        });
        return p;
    }

    private static String describe(String[] labels, String[] values, int i, String desc, String[] perValueDesc) {
        if (i < 0 || i >= labels.length) i = 0;
        String d = perValueDesc != null ? perValueDesc[i] : desc;
        return d != null ? d : labels[i];   // the current choice shows in the pop-up, like Sony's own settings
    }

    private static Preference toggle(final Context ctx, final SharedPreferences sp, final String key, final String title,
                                     final boolean def, final String desc) {
        final Preference p = new Preference(ctx);
        p.setKey(key);
        p.setPersistent(false);
        p.setTitle(title);
        p.setIconSpaceReserved(false);
        p.setSummary((sp.getBoolean(key, def) ? "On" : "Off") + "\n" + desc);
        p.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override public boolean onPreferenceClick(Preference pref) {
                boolean v = !sp.getBoolean(key, def);
                sp.edit().putBoolean(key, v).apply();
                p.setSummary((v ? "On" : "Off") + "\n" + desc);
                return true;
            }
        });
        return p;
    }

    /**
     * Called with the settings list when it's created. Some of Sony's rows (section titles,
     * service rows added later) have a built-in left indent. Before each frame, any row whose
     * text starts further right than the others has that indent removed (padding, margins,
     * empty icon frames), so all text lines up.
     */
    public static void alignTitles(final android.view.View list) {
        if (!(list instanceof android.view.ViewGroup)) return;
        final android.view.ViewGroup rv = (android.view.ViewGroup) list;
        final int[] loc = new int[2];
        rv.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                try {
                    int n = rv.getChildCount();
                    if (n == 0) return true;
                    android.view.View[] text = new android.view.View[n];
                    int[] x = new int[n];
                    int minX = Integer.MAX_VALUE;
                    for (int i = 0; i < n; i++) {
                        android.view.View t = firstText(rv.getChildAt(i));
                        if (t == null) continue;
                        if (t.getTranslationX() != 0f) t.setTranslationX(0f);   // undo the old approach
                        t.getLocationInWindow(loc);
                        text[i] = t; x[i] = loc[0];
                        if (x[i] < minX) minX = x[i];
                    }
                    if (minX == Integer.MAX_VALUE) return true;
                    // Align to the most common text position (the normal rows), not just the leftmost.
                    int best = minX, bestCount = 0;
                    for (int i = 0; i < n; i++) {
                        if (text[i] == null) continue;
                        int c = 0;
                        for (int k = 0; k < n; k++) if (text[k] != null && Math.abs(x[k] - x[i]) <= 2) c++;
                        if (c > bestCount || (c == bestCount && x[i] < best)) { bestCount = c; best = x[i]; }
                    }
                    minX = best;
                    boolean changed = false;
                    for (int i = 0; i < n; i++) {
                        if (text[i] == null) continue;
                        int delta = x[i] - minX;
                        if (delta > 1 && unindent(text[i], rv.getChildAt(i), delta)) changed = true;
                    }
                    return !changed;   // skip this frame; the next one has the new layout
                } catch (Throwable e) {
                    return true;
                }
            }
        });
    }

    /** The row's title if it has text, otherwise its summary. */
    private static android.view.View firstText(android.view.View row) {
        android.view.View t = row.findViewById(android.R.id.title);
        if (t instanceof android.widget.TextView && t.getVisibility() == android.view.View.VISIBLE
                && t.getWidth() > 0 && ((android.widget.TextView) t).getText() != null
                && ((android.widget.TextView) t).getText().length() > 0) return t;
        android.view.View s = row.findViewById(android.R.id.summary);
        if (s != null && s.getVisibility() == android.view.View.VISIBLE && s.getWidth() > 0) return s;
        return null;
    }

    /** Removes up to delta pixels of left indent between the text view and its row. */
    private static boolean unindent(android.view.View v, android.view.View row, int delta) {
        boolean changed = false;
        while (v != row && delta > 0) {
            android.view.ViewParent pp = v.getParent();
            if (!(pp instanceof android.view.ViewGroup)) break;
            android.view.ViewGroup parent = (android.view.ViewGroup) pp;
            // the view's own start margin
            android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp instanceof android.view.ViewGroup.MarginLayoutParams) {
                android.view.ViewGroup.MarginLayoutParams m = (android.view.ViewGroup.MarginLayoutParams) lp;
                int ms = Math.max(m.getMarginStart(), m.leftMargin);
                int r = Math.min(ms, delta);
                if (r > 0) {
                    m.setMarginStart(ms - r); m.leftMargin = ms - r;
                    v.setLayoutParams(m);
                    delta -= r; changed = true;
                }
            }
            // empty icon frames sitting to the left inside a horizontal row
            if (delta > 0 && parent instanceof android.widget.LinearLayout
                    && ((android.widget.LinearLayout) parent).getOrientation() == android.widget.LinearLayout.HORIZONTAL) {
                for (int i = 0; i < parent.getChildCount() && delta > 0; i++) {
                    android.view.View sib = parent.getChildAt(i);
                    if (sib == v) break;
                    if (sib.getVisibility() == android.view.View.VISIBLE && !hasText(sib) && sib.getWidth() <= delta + 2) {
                        delta -= sib.getWidth();
                        sib.setVisibility(android.view.View.GONE);
                        changed = true;
                    }
                }
            }
            // the parent's start padding (stop at the row itself, which matches the other rows)
            if (delta > 0 && parent != row.getParent()) {
                int ps = Math.max(parent.getPaddingStart(), parent.getPaddingLeft());
                int r = Math.min(ps, delta);
                if (r > 0) {
                    parent.setPaddingRelative(ps - r, parent.getPaddingTop(), parent.getPaddingEnd(), parent.getPaddingBottom());
                    delta -= r; changed = true;
                }
            }
            v = parent;
        }
        return changed;
    }

    private static boolean hasText(android.view.View v) {
        if (v instanceof android.widget.TextView) {
            CharSequence t = ((android.widget.TextView) v).getText();
            return t != null && t.length() > 0;
        }
        if (v instanceof android.widget.CompoundButton) return true;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) if (g.getChildAt(i).getVisibility() == android.view.View.VISIBLE && hasText(g.getChildAt(i))) return true;
        }
        return false;
    }

    private static void noIconSpace(Preference p) {
        if (p == null) return;
        p.setIconSpaceReserved(false);
        if (p instanceof PreferenceGroup) {
            PreferenceGroup g = (PreferenceGroup) p;
            for (int i = 0; i < g.getPreferenceCount(); i++) noIconSpace(g.getPreference(i));
        }
    }

    private static int index(String[] values, String v) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(v)) return i;
        return 0;
    }

}
