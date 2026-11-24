package com.arialbold.xmrb.patches;

import com.arialbold.xmrb.Smali;
import com.arialbold.xmrb.Smali.ClassFile;
import com.arialbold.xmrb.Smali.Method;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The code changes that connect the added features to Sony's app. Each patch finds its
 * place by what the code does (method signatures, calls, strings) and reports clearly if
 * a newer release no longer matches, so the other features can still be built.
 */
public final class Patches {

    /** Result of one patch. */
    public static final class Result {
        public final String feature;
        public final boolean applied;
        public final String detail;
        public Result(String feature, boolean applied, String detail) { this.feature = feature; this.applied = applied; this.detail = detail; }
        @Override public String toString() { return (applied ? "✓ " : "✗ ") + feature + (detail.isEmpty() ? "" : " — " + detail); }
    }

    public interface Patch {
        String feature();
        void apply(Smali s) throws Exception;   // throws Skip to report a non-match
    }

    /** Thrown when a patch can't find what it needs. */
    public static final class Skip extends Exception {
        public Skip(String why) { super(why); }
    }

    private static final String PKG = "Lcom/fpmod/dsd/";

    public static List<Patch> all() {
        List<Patch> l = new ArrayList<>();
        l.add(new Player());
        l.add(new Volume());
        l.add(new Settings());
        l.add(new LibraryTags());
        l.add(new CoverArt());
        l.add(new DownloadImage());
        l.add(new HdBadge());
        return l;
    }

    public static List<Result> run(Smali s, java.util.function.Consumer<String> log) {
        List<Result> out = new ArrayList<>();
        for (Patch p : all()) {
            Result r;
            try {
                p.apply(s);
                r = new Result(p.feature(), true, "");
            } catch (Skip e) {
                r = new Result(p.feature(), false, e.getMessage());
            } catch (Exception e) {
                r = new Result(p.feature(), false, "error: " + e);
            }
            out.add(r);
            if (log != null) log.accept(r.toString());
        }
        return out;
    }

    static void need(boolean ok, String why) throws Skip { if (!ok) throw new Skip(why); }

    // ------------------------------------------------------------------
    // DSD/DST playback and ReplayGain: AudioPlayer.setDataSourceAndMode

    static final String SDS_DESC = "(Landroid/media/MediaPlayer;Landroid/content/Context;Landroid/net/Uri;Ljava/util/Map;)V";

    static final class Player implements Patch {
        public String feature() { return "DSD/DST playback and ReplayGain"; }
        public void apply(Smali s) throws Exception {
            ClassFile c = s.find("\\.method [^\\n]*" + Pattern.quote(SDS_DESC), "->onDataSourceSet\\(");
            need(c != null, "player's data-source method not found");
            Method m = c.method(SDS_DESC);
            need(m != null && !m.isStatic(), "player's data-source method has changed shape");
            // DSD: before anything else, offer the track to the DSD decoder
            int first = m.firstInstruction();
            String r = m.newLocal();
            first = m.firstInstruction();
            m.insert(first,
                    "    invoke-static/range {p1 .. p3}, " + PKG + "DsdSupport;->trySetDataSource(Landroid/media/MediaPlayer;Landroid/content/Context;Landroid/net/Uri;)Z",
                    "    move-result " + r,
                    "    if-nez " + r + ", :fpmod_after_ds");
            // ReplayGain: after the data source is set, just before the listener is told
            int cb = m.find("->onDataSourceSet(");
            need(cb > 0, "listener call not found");
            Pattern loadListener = Pattern.compile("iget-object [vp]\\d+, p0, L[^;]+;->m\\w*Listener:");
            int at = -1;
            for (int i = cb; i > m.start; i--) if (loadListener.matcher(c.lines.get(i)).find()) { at = i; break; }
            need(at > 0, "listener field not found");
            m.insert(at,
                    ":fpmod_after_ds",
                    "    invoke-static/range {p1 .. p3}, " + PKG + "ReplayGain;->apply(Landroid/media/MediaPlayer;Landroid/content/Context;Landroid/net/Uri;)V");
            c.save();
        }
    }

    // ------------------------------------------------------------------
    // ReplayGain volume: MediaPlayer.setVolume -> ReplayGain.setVolume

    static final class Volume implements Patch {
        public String feature() { return "ReplayGain volume control"; }
        public void apply(Smali s) throws Exception {
            ClassFile c = s.find("\\.method [^\\n]*" + Pattern.quote(SDS_DESC));
            need(c != null, "player class not found");
            Pattern p = Pattern.compile("invoke-virtual \\{([vp]\\d+), ([vp]\\d+), ([vp]\\d+)\\}, Landroid/media/MediaPlayer;->setVolume\\(FF\\)V");
            int n = 0;
            for (int i = 0; i < c.lines.size(); i++) {
                Matcher mm = p.matcher(c.lines.get(i));
                if (mm.find()) {
                    c.lines.set(i, "    invoke-static {" + mm.group(1) + ", " + mm.group(2) + ", " + mm.group(3) + "}, "
                            + PKG + "ReplayGain;->setVolume(Landroid/media/MediaPlayer;FF)V");
                    n++;
                }
            }
            need(n > 0, "no volume calls found");
            c.save();
        }
    }

    // ------------------------------------------------------------------
    // Settings: ReplayGain section, DSD switch, alignment, tidy-up

    static final class Settings implements Patch {
        public String feature() { return "Settings additions"; }
        public void apply(Smali s) throws Exception {
            ClassFile c = s.find("^\\.super Landroidx/preference/PreferenceFragmentCompat;", "pref_key_music_settings_music_quality");
            need(c != null, "settings screen not found");
            int at = c.indexOf("PreferenceFragmentCompat;->addPreferencesFromResource(I)V", 0);
            need(at > 0, "settings loading call not found");
            c.insert(at + 1, Smali.invokeOne("p0", PKG + "FpSettings;->addTo(Landroidx/preference/PreferenceFragmentCompat;)V"));
            Method rv = c.method("onCreateRecyclerView(");
            if (rv != null) {
                List<Integer> rets = new ArrayList<>();
                for (int i = rv.start; i <= rv.end; i++) if (c.lines.get(i).trim().startsWith("return-object ")) rets.add(i);
                need(!rets.isEmpty(), "settings list method has no return");
                for (int k = rets.size() - 1; k >= 0; k--) {
                    int i = rets.get(k);
                    String reg = c.lines.get(i).trim().substring("return-object ".length()).trim();
                    rv.insert(i, Smali.invokeOne(reg, PKG + "FpSettings;->alignTitles(Landroid/view/View;)V"));
                }
            } else {
                // Sony's class doesn't override it in this version: add an override
                c.lines.add(String.join("\n",
                        "",
                        ".method public onCreateRecyclerView(Landroid/view/LayoutInflater;Landroid/view/ViewGroup;Landroid/os/Bundle;)Landroidx/recyclerview/widget/RecyclerView;",
                        "    .locals 0",
                        "    invoke-super {p0, p1, p2, p3}, Landroidx/preference/PreferenceFragmentCompat;->onCreateRecyclerView(Landroid/view/LayoutInflater;Landroid/view/ViewGroup;Landroid/os/Bundle;)Landroidx/recyclerview/widget/RecyclerView;",
                        "    move-result-object p1",
                        "    invoke-static {p1}, " + PKG + "FpSettings;->alignTitles(Landroid/view/View;)V",
                        "    return-object p1",
                        ".end method"));
            }
            c.save();
        }
    }

    // ------------------------------------------------------------------
    // DSD tags/duration in the library: before tag overrides are applied

    static final class LibraryTags implements Patch {
        public String feature() { return "DSD tags in the library"; }
        public void apply(Smali s) throws Exception {
            ClassFile c = s.find("SELECT aa\\.title, aa\\.artist", "audio_tag");
            need(c != null, "library sync not found");
            int q = c.indexOf("\"SELECT aa.title, aa.artist", 0);
            need(q > 0, "tag override query not found");
            // the database register is the one the query runs on
            Pattern raw = Pattern.compile("invoke-virtual(?:/range)? \\{([vp]\\d+)[ ,.]");
            String db = null;
            for (int i = q; i < Math.min(c.lines.size(), q + 12); i++) {
                String l = c.lines.get(i);
                if (l.contains("SQLiteDatabase;->rawQuery")) {
                    Matcher mm = raw.matcher(l);
                    if (mm.find()) db = mm.group(1);
                    break;
                }
            }
            need(db != null, "database not found next to the query");
            c.insert(q, Smali.invokeOne(db, PKG + "DsdTags;->syncAudioTags(Landroid/database/sqlite/SQLiteDatabase;)V"));
            c.save();
        }
    }

    // ------------------------------------------------------------------
    // Embedded cover art for DSD files

    static final class CoverArt implements Patch {
        public String feature() { return "DSD cover art"; }
        public void apply(Smali s) throws Exception {
            ClassFile c = s.find("getAndSaveEmbeddedAlbumArt", "Landroid/media/MediaMetadataRetriever;->getEmbeddedPicture\\(\\)\\[B");
            need(c != null, "cover art reader not found");
            Pattern set = Pattern.compile("invoke-virtual \\{([vp]\\d+), ([vp]\\d+)\\}, Landroid/media/MediaMetadataRetriever;->setDataSource\\(Ljava/lang/String;\\)V");
            Pattern pic = Pattern.compile("invoke-virtual \\{([vp]\\d+)\\}, Landroid/media/MediaMetadataRetriever;->getEmbeddedPicture\\(\\)\\[B");
            int n = 0;
            for (Method m : c.methods()) {
                for (int i = m.start; i <= m.end; i++) {
                    Matcher ms = set.matcher(c.lines.get(i));
                    if (!ms.find()) continue;
                    String mmr = ms.group(1), path = ms.group(2);
                    int j = -1;
                    for (int k = i + 1; k <= m.end; k++) {
                        Matcher mp = pic.matcher(c.lines.get(k));
                        if (mp.find() && mp.group(1).equals(mmr)) { j = k; break; }
                    }
                    if (j < 0 || !Smali.fitsInvoke(m, mmr, path)) continue;
                    c.lines.set(i, "    invoke-static {" + mmr + ", " + path + "}, " + PKG + "DsdTags;->mmrSetDataSource(Landroid/media/MediaMetadataRetriever;Ljava/lang/String;)V");
                    c.lines.set(j, "    invoke-static {" + mmr + ", " + path + "}, " + PKG + "DsdTags;->embeddedPicture(Landroid/media/MediaMetadataRetriever;Ljava/lang/String;)[B");
                    n++;
                }
            }
            need(n > 0, "no cover art reads found");
            c.save();
        }
    }

    // ------------------------------------------------------------------
    // "Download image" for album covers (MusicBrainz / Cover Art Archive)

    static final class DownloadImage implements Patch {
        public String feature() { return "Download cover image"; }
        public void apply(Smali s) throws Exception {
            ClassFile e = s.find("^\\.class [^\\n]*enum [^\\n]*ArtAction;", "->SET_IMAGE:", "->REMOVE_IMAGE:", "mStringId:I");
            need(e != null, "cover action list not found");
            String E = e.descriptor();
            need(e.text().contains("-><init>(Ljava/lang/String;II)V"), "cover action constructor has changed");
            // label: Sony's own "Download image" text, still in the app's resources
            ClassFile rs = s.find("^\\.class [^\\n]*R\\$string;", "edit_music_info_art_context_download:I = ");
            need(rs != null, "\"Download image\" text not found");
            Matcher idm = Pattern.compile("edit_music_info_art_context_download:I = (0x[0-9a-f]+)").matcher(rs.text());
            need(idm.find(), "\"Download image\" text id not found");
            String id = idm.group(1);

            // count constants for the new ordinal
            int ordinal = 0;
            for (String l : e.lines) if (l.startsWith(".field public static final enum ")) ordinal++;
            int f = e.indexOf(".field public static final enum SET_IMAGE:", 0);
            need(f >= 0, "cover action fields not found");
            e.insert(f + 1, ".field public static final enum DOWNLOAD_IMAGE:" + E);

            // add it to the values array
            Pattern arr = Pattern.compile("filled-new-array \\{([^}]*)\\}, \\[" + Pattern.quote(E));
            Method vm = null; int ai = -1; Matcher am = null;
            for (Method m : e.methods()) {
                int i = m.findRegex(arr, m.start);
                if (i >= 0) { vm = m; ai = i; am = arr.matcher(e.lines.get(i)); am.find(); break; }
            }
            need(vm != null, "values list not found");
            String[] regs = am.group(1).split(",\\s*");
            need(regs.length < 5, "values list too long for this patch");
            String nr = vm.newLocal();
            ai = vm.findRegex(arr, vm.start);
            vm.insert(ai, "    sget-object " + nr + ", " + E + "->DOWNLOAD_IMAGE:" + E);
            ai = vm.findRegex(arr, vm.start);
            e.lines.set(ai, "    filled-new-array {" + am.group(1) + ", " + nr + "}, [" + E);

            // create it in the static initialiser, after the last constant
            Method clinit = e.method("constructor <clinit>()V");
            need(clinit != null, "initialiser not found");
            clinit.ensureLocals(4);
            int last = -1;
            Pattern sput = Pattern.compile("sput-object [vp]\\d+, " + Pattern.quote(E) + "->(SET_IMAGE|REMOVE_IMAGE):");
            for (int i = clinit.start; i <= clinit.end; i++) if (sput.matcher(e.lines.get(i)).find()) last = i;
            need(last > 0, "constants not found in initialiser");
            clinit.insert(last + 1,
                    "    new-instance v0, " + E,
                    "    const/4 v1, 0x" + Integer.toHexString(ordinal),
                    "    const v2, " + id,
                    "    const-string v3, \"DOWNLOAD_IMAGE\"",
                    "    invoke-direct {v0, v3, v1, v2}, " + E + "-><init>(Ljava/lang/String;II)V",
                    "    sput-object v0, " + E + "->DOWNLOAD_IMAGE:" + E);

            // the edit screen: offer it in the menu and handle it
            ClassFile frag = s.find("getArtActions\\(", "->SET_IMAGE:", "onActionSelected\\(" + Pattern.quote(E));
            need(frag != null, "edit screen not found");
            Method ga = frag.method(" getArtActions(");   // not the compiler's "-$$Nest$mgetArtActions" bridge
            need(ga != null, "menu builder not found");
            Pattern setSget = Pattern.compile("sget-object ([vp]\\d+), " + Pattern.quote(E) + "->SET_IMAGE:");
            int si = ga.findRegex(setSget, ga.start);
            need(si > 0, "menu entry not found");
            Matcher sm = setSget.matcher(frag.lines.get(si)); sm.find();
            String reg = sm.group(1);
            int addi = -1;
            for (int i = si + 1; i <= ga.end; i++) if (frag.lines.get(i).contains("Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z")) { addi = i; break; }
            need(addi > 0, "menu add call not found");
            String addLine = frag.lines.get(addi);
            ga.insert(addi + 1, "    sget-object " + reg + ", " + E + "->DOWNLOAD_IMAGE:" + E, addLine);

            Method oa = frag.method("onActionSelected(" + E);
            need(oa != null, "menu handler not found");
            oa.ensureLocals(1);
            int fi = oa.firstInstruction();
            oa.insert(fi,
                    "    sget-object v0, " + E + "->DOWNLOAD_IMAGE:" + E,
                    "    if-ne p1, v0, :fpmod_not_download",
                    "    invoke-static {p0, p2}, " + PKG + "CoverArtSearch;->start(Ljava/lang/Object;Ljava/lang/Object;)V",
                    "    return-void",
                    ":fpmod_not_download");
            // save both only once everything matched, so a partial change is never left behind
            e.save();
            frag.save();
        }
    }

    // ------------------------------------------------------------------
    // HD badge for DSD

    static final class HdBadge implements Patch {
        public String feature() { return "HD badge for DSD"; }
        public void apply(Smali s) throws Exception {
            ClassFile c = s.find("\"is_high_res\"", "\"audio/dsd\"", "\\$TrackInfo;->mimeType");
            need(c != null, "HD list code not found");
            Method sync = c.method("static sync(Landroid/content/Context;");
            need(sync != null, "HD list sync not found");
            sync.insert(sync.firstInstruction(), Smali.invokeOne("p0", PKG + "DsdTags;->forceHdRescanOnce(Landroid/content/Context;)V"));

            Pattern set = Pattern.compile("invoke-virtual \\{([vp]\\d+), ([vp]\\d+), ([vp]\\d+)\\}, Landroid/media/MediaMetadataRetriever;->setDataSource\\(Landroid/content/Context;Landroid/net/Uri;\\)V");
            Pattern mime = Pattern.compile("iget-object ([vp]\\d+), ([vp]\\d+), L[^;]*\\$TrackInfo;->mimeType:");
            Pattern dsd = Pattern.compile("const-string ([vp]\\d+), \"audio/dsd\"");
            boolean done = false;
            for (Method m : c.methods()) {
                int si = m.findRegex(set, m.start);
                if (si < 0) continue;
                int di = m.findRegex(dsd, si);
                need(di > 0, "DSD check not found");
                Matcher dm = dsd.matcher(c.lines.get(di)); dm.find();
                String constReg = dm.group(1);
                int mi = m.findRegex(mime, di);
                need(mi > 0, "track type read not found");
                Matcher mmm = mime.matcher(c.lines.get(mi)); mmm.find();
                String track = mmm.group(2);
                int ei = -1;
                for (int i = mi; i <= m.end && i < mi + 8; i++) {
                    String l = c.lines.get(i);
                    if (l.contains("Ljava/lang/String;->equals(Ljava/lang/Object;)Z") && l.contains(constReg)) { ei = i; break; }
                }
                need(ei > 0, "DSD comparison not found");
                Matcher sm = set.matcher(c.lines.get(si)); sm.find();
                need(Smali.fitsInvoke(m, sm.group(1), sm.group(2), sm.group(3), track), "registers out of range");
                c.lines.set(si, "    invoke-static {" + sm.group(1) + ", " + sm.group(2) + ", " + sm.group(3) + ", " + track + "}, "
                        + PKG + "DsdTags;->mmrSetDataSourceTrack(Landroid/media/MediaMetadataRetriever;Landroid/content/Context;Landroid/net/Uri;Ljava/lang/Object;)V");
                c.lines.set(ei, Smali.invokeOne(track, PKG + "DsdTags;->isDsdTrack(Ljava/lang/Object;)Z"));
                c.lines.set(di, "    # fpmod: DSD check moved to DsdTags.isDsdTrack");
                done = true;
                break;
            }
            need(done, "HD check method not found");
            c.save();
        }
    }
}
