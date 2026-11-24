package com.fpmod.dsd;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;

/**
 * "Classic player layout": Music 9.4.16.A.1.x shrank the full player's album art (a margin
 * around it) and added a yellow format line (with the HR icon) above the title. With the
 * setting on, the player looks like earlier versions: art edge to edge, no format line.
 * Applied to the views when the player is shown and whenever the setting changes.
 */
public final class PlayerStyle {
    public static final String KEY = "fpmod_classic_player";
    private static final int TAG_MARGINS = 0x7f5e0001;   // our view tag keys (outside the app's id range use)
    private static final int TAG_PADDING = 0x7f5e0002;

    private PlayerStyle() { }

    /** True if this version of the app has the newer player layout (so the setting matters). */
    public static boolean supported(Context ctx) {
        return id(ctx, "music_format_info_layout") != 0;
    }

    public static boolean classic(Context ctx) {
        return prefs(ctx).getBoolean(KEY, true);
    }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(ctx.getPackageName() + "_preferences", Context.MODE_PRIVATE);
    }

    /** Called from the player's MetadataView.onFinishInflate. */
    public static void attach(final View metadata) {
        if (metadata == null) return;
        try {
            final SharedPreferences sp = prefs(metadata.getContext());
            final SharedPreferences.OnSharedPreferenceChangeListener l = new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override public void onSharedPreferenceChanged(SharedPreferences p, String key) {
                    if (KEY.equals(key)) metadata.post(new Runnable() { @Override public void run() { apply(metadata); } });
                }
            };
            metadata.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View v) {
                    apply(metadata);
                    sp.registerOnSharedPreferenceChangeListener(l);
                }
                @Override public void onViewDetachedFromWindow(View v) {
                    sp.unregisterOnSharedPreferenceChangeListener(l);
                }
            });
        } catch (Throwable ignored) { }
    }

    static void apply(View metadata) {
        try {
            Context ctx = metadata.getContext();
            boolean classic = classic(ctx);
            View root = metadata.getRootView();
            int infoId = id(ctx, "music_format_info_layout"), artId = id(ctx, "art_pager");
            if (infoId != 0) {
                View info = metadata.findViewById(infoId);
                if (info == null) info = root.findViewById(infoId);
                if (info != null) info.setVisibility(classic ? View.GONE : View.VISIBLE);
            }
            if (artId != 0) {
                View art = root.findViewById(artId);
                if (art != null && art.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) art.getLayoutParams();
                    Object saved = art.getTag(TAG_MARGINS);
                    if (!(saved instanceof int[])) {
                        saved = new int[]{lp.leftMargin, lp.topMargin, lp.rightMargin, lp.bottomMargin};
                        art.setTag(TAG_MARGINS, saved);
                    }
                    int[] m = (int[]) saved;
                    if (classic) lp.setMargins(0, 0, 0, 0); else lp.setMargins(m[0], m[1], m[2], m[3]);
                    art.setLayoutParams(lp);
                }
                if (art != null) invalidateTree(art);       // redraw the art so the HR icon appears or goes
            }
            // The newer player pulls the title, seek bar and controls up by the art margin, to make up for
            // the margin around the art. Without that margin, add room back as top padding in the song info:
            // the player spreads its spare height in thirds, so 1.5x the margin puts the title where it was.
            int padId = ctx.getResources().getIdentifier("art_pager_view_padding", "dimen", ctx.getPackageName());
            float pad = padId != 0 ? ctx.getResources().getDimension(padId) : 0f;
            Object savedTop = metadata.getTag(TAG_PADDING);
            if (!(savedTop instanceof Integer)) { savedTop = metadata.getPaddingTop(); metadata.setTag(TAG_PADDING, savedTop); }
            int top = (Integer) savedTop + (classic ? Math.round(pad * 1.5f) : 0);
            metadata.setPadding(metadata.getPaddingLeft(), top, metadata.getPaddingRight(), metadata.getPaddingBottom());
            metadata.requestLayout();
        } catch (Throwable ignored) { }
    }

    private static void invalidateTree(View v) {
        v.invalidate();
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) invalidateTree(g.getChildAt(i));
        }
    }

    // ------------------------------------------------------------ HR icon

    private static java.lang.reflect.Field hdField;
    private static android.graphics.drawable.Drawable hrIcon;
    private static int hrMargin = -1;

    /**
     * Called at the end of the album art view's onDraw. In the classic layout the format line
     * (which carries the HR icon) is hidden, so draw the HR icon in the art's bottom-left corner,
     * where older versions of the app put it.
     */
    public static void drawHr(View art, android.graphics.Canvas canvas) {
        try {
            if (art == null || canvas == null) return;
            Context ctx = art.getContext();
            if (!supported(ctx) || !classic(ctx)) return;
            if (hdField == null) {
                java.lang.reflect.Field f = art.getClass().getDeclaredField("mHdAudio");
                f.setAccessible(true);
                hdField = f;
            }
            if (!hdField.getBoolean(art)) return;
            if (hrIcon == null) {
                android.content.res.Resources r = ctx.getResources();
                int d = r.getIdentifier("hr_indicator_player", "drawable", ctx.getPackageName());
                if (d == 0) return;
                hrIcon = r.getDrawable(d, null);
                int mid = r.getIdentifier("full_player_hd_audio_indicator_margin", "dimen", ctx.getPackageName());
                hrMargin = mid != 0 ? r.getDimensionPixelSize(mid) : Math.round(4 * r.getDisplayMetrics().density);
            }
            int w = hrIcon.getIntrinsicWidth(), h = hrIcon.getIntrinsicHeight();
            int bottom = art.getHeight() - hrMargin;
            hrIcon.setBounds(hrMargin, bottom - h, hrMargin + w, bottom);
            hrIcon.draw(canvas);
        } catch (Throwable ignored) { }
    }

    private static int id(Context ctx, String name) {
        try { return ctx.getResources().getIdentifier(name, "id", ctx.getPackageName()); } catch (Throwable e) { return 0; }
    }
}
