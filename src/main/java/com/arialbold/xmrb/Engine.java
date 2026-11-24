package com.arialbold.xmrb;

import com.arialbold.xmrb.patches.Patches;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The whole rebuild: check the APK, unpack its code, apply the patches, rebuild the changed
 * code, assemble the new APK on top of Sony's untouched resources, and sign it.
 */
public final class Engine {

    public static final String PACKAGE = "com.sonyericsson.music";

    public interface Listener {
        void log(String line);
        void progress(int percent, String step);
    }

    public static final class Info {
        public String packageName, versionName, versionCode;
        public boolean alreadyRebuilt;
    }

    public static final class Outcome {
        public Path apk;
        public List<Patches.Result> results = new ArrayList<>();
        public boolean allApplied() { for (Patches.Result r : results) if (!r.applied) return false; return true; }
    }

    private Engine() { }

    /** Reads the APK's identity. Throws if it isn't Sony's Music app. */
    public static Info inspect(Path apk) throws IOException {
        try (ZipFile z = new ZipFile(apk.toFile())) {
            ZipEntry me = z.getEntry("AndroidManifest.xml");
            if (me == null) throw new IOException("This file isn't an Android app (no manifest).");
            Manifest m;
            try (InputStream in = z.getInputStream(me)) { m = new Manifest(in.readAllBytes()); }
            Info i = new Info();
            i.packageName = m.attr("manifest", "package");
            i.versionName = m.attr("manifest", "versionName");
            i.versionCode = m.attr("manifest", "versionCode");
            if (!PACKAGE.equals(i.packageName)) {
                throw new IOException("This is “" + i.packageName + "”, not Sony's Music app (" + PACKAGE + ").");
            }
            java.util.Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (n.startsWith("classes") && n.endsWith(".dex")) {
                    try (InputStream in = z.getInputStream(z.getEntry(n))) {
                        if (new String(in.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1).contains("Lcom/fpmod/dsd/DsdSupport;")) {
                            i.alreadyRebuilt = true;
                        }
                    }
                }
            }
            if (z.getEntry("base.apk") != null) throw new IOException("This looks like an app bundle. Use the base.apk inside it.");
            return i;
        }
    }

    public static Outcome build(Path input, Path output, Signing.Key key, Path work, Listener l) throws Exception {
        Outcome out = new Outcome();
        Info info = inspect(input);
        if (info.alreadyRebuilt) throw new IOException("This APK has already been rebuilt. Use Sony's original APK.");
        l.log("Sony Music " + info.versionName + " (" + info.versionCode + ")");

        deleteTree(work);
        Files.createDirectories(work);
        Path code = work.resolve("code");

        l.progress(5, "Unpacking the app's code…");
        apktool(l, "d", "-r", "-f", "-o", code.toString(), input.toString());

        l.progress(35, "Adding features…");
        Smali s = new Smali(code);
        out.results = Patches.run(s, l::log);
        if (s.changedDirs().isEmpty()) throw new IOException("None of the features could be added to this version of the app.");

        // only rebuild the code folders that changed; keep the others byte-for-byte
        l.progress(45, "Rebuilding the changed code…");
        apktool(l, "b", "-f", "-o", work.resolve("rebuilt.apk").toString(), code.toString());

        Map<String, byte[]> replace = new LinkedHashMap<>();
        try (ZipFile rebuilt = new ZipFile(work.resolve("rebuilt.apk").toFile())) {
            for (Path dir : s.changedDirs()) {
                String folder = dir.getFileName().toString();          // smali or smali_classesN
                String dex = folder.equals("smali") ? "classes.dex" : folder.substring("smali_".length()) + ".dex";
                ZipEntry e = rebuilt.getEntry(dex);
                if (e == null) throw new IOException("rebuilt " + dex + " is missing");
                try (InputStream in = rebuilt.getInputStream(e)) { replace.put(dex, in.readAllBytes()); }
                l.log("Rebuilt " + dex);
            }
        }

        l.progress(70, "Assembling the new app…");
        byte[] manifestBytes;
        int dexCount = 0;
        try (ZipFile z = new ZipFile(input.toFile())) {
            try (InputStream in = z.getInputStream(z.getEntry("AndroidManifest.xml"))) { manifestBytes = in.readAllBytes(); }
            java.util.Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (n.matches("classes\\d*\\.dex")) dexCount++;
            }
        }
        Manifest mf = new Manifest(manifestBytes);
        if (mf.makeLibraryOptional("com.sony.device")) {
            replace.put("AndroidManifest.xml", mf.bytes());
            out.results.add(0, resultOk("Installs on phones that aren't Xperias"));
        } else {
            out.results.add(0, resultSkip("Installs on phones that aren't Xperias", "no Xperia-only requirement found (already optional?)"));
        }
        l.log(out.results.get(0).toString());

        Map<String, byte[]> add = new LinkedHashMap<>();
        byte[] payload;
        try (InputStream in = Engine.class.getResourceAsStream("payload.dex")) {
            if (in == null) throw new IOException("the builder's feature code (payload.dex) is missing");
            payload = in.readAllBytes();
        }
        add.put("classes" + (dexCount + 1) + ".dex", payload);

        Path unsigned = work.resolve("unsigned.apk");
        ApkAssembler.write(input, unsigned, replace, add);
        ApkAssembler.verifyAlignment(unsigned);

        l.progress(85, "Signing…");
        Signing.sign(unsigned, output, key);
        Files.deleteIfExists(Path.of(output + ".idsig"));
        l.progress(100, "Done");
        out.apk = output;
        deleteTree(work);
        return out;
    }

    private static Patches.Result resultOk(String f) { return new Patches.Result(f, true, ""); }
    private static Patches.Result resultSkip(String f, String why) { return new Patches.Result(f, false, why); }

    // ---------------------------------------------------------------- apktool

    private static void apktool(Listener l, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(Signing.Paths.javaHomeTool("java"));
        cmd.add("-Xmx2g");
        cmd.add("-Djava.awt.headless=true");
        cmd.add("-cp");
        cmd.add(jarOf("brut.apktool.Main"));
        cmd.add("brut.apktool.Main");
        for (String a : args) cmd.add(a);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder all = new StringBuilder();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                all.append(line).append('\n');
                if (line.startsWith("I: ")) l.log(line.substring(3));
            }
        }
        if (p.waitFor() != 0) throw new IOException("apktool " + args[0] + " failed:\n" + all.toString().trim());
    }

    private static String jarOf(String className) throws IOException {
        try {
            Class<?> c = Class.forName(className, false, Engine.class.getClassLoader());
            return new File(c.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();
        } catch (Exception e) {
            throw new IOException("apktool isn't bundled with the builder", e);
        }
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).forEach(x -> { try { Files.delete(x); } catch (IOException ignored) { } });
        }
    }
}
