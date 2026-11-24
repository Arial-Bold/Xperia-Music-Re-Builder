package com.arialbold.xmrb;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The app's code, disassembled into smali text (one file per class), with helpers that
 * find classes and methods by what they contain rather than by line numbers, so the
 * patches keep working when Sony's code shifts around in newer releases.
 */
public final class Smali {
    private final Path root;
    private final List<Path> files;
    private final Set<Path> changedDirs = new HashSet<>();

    public Smali(Path root) throws IOException {
        this.root = root;
        try (Stream<Path> s = Files.walk(root)) {
            files = s.filter(p -> p.toString().endsWith(".smali")).collect(Collectors.toList());
        }
    }

    /** The smali_classesN folders that were modified. */
    public Set<Path> changedDirs() { return changedDirs; }

    /** First class file whose text matches every given regex. */
    public ClassFile find(String... regexes) {
        List<ClassFile> all = findAll(1, regexes);
        return all.isEmpty() ? null : all.get(0);
    }

    public List<ClassFile> findAll(int max, String... regexes) {
        Pattern[] ps = new Pattern[regexes.length];
        for (int i = 0; i < regexes.length; i++) ps[i] = Pattern.compile(regexes[i], Pattern.MULTILINE);
        List<ClassFile> out = new ArrayList<>();
        for (Path p : files) {
            String text = read(p);
            boolean ok = true;
            for (Pattern pt : ps) if (!pt.matcher(text).find()) { ok = false; break; }
            if (ok) { out.add(new ClassFile(p, text)); if (out.size() >= max) break; }
        }
        return out;
    }

    /** Class by its descriptor, e.g. "Lcom/foo/Bar;". */
    public ClassFile byDescriptor(String desc) {
        String rel = desc.substring(1, desc.length() - 1) + ".smali";
        for (Path p : files) {
            if (p.toString().replace('\\', '/').endsWith("/" + rel)) return new ClassFile(p, read(p));
        }
        return null;
    }

    private static String read(Path p) {
        try { return new String(Files.readAllBytes(p), StandardCharsets.UTF_8); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    // --------------------------------------------------------------- class

    public final class ClassFile {
        final Path path;
        public final List<String> lines;

        ClassFile(Path path, String text) {
            this.path = path;
            this.lines = new ArrayList<>(List.of(text.split("\n", -1)));
        }

        public String descriptor() {
            for (String l : lines) {
                if (l.startsWith(".class ")) return l.substring(l.lastIndexOf(' ') + 1).trim();
            }
            return "?";
        }

        public String text() { return String.join("\n", lines); }

        /** Method whose ".method" line contains all the given fragments. */
        public Method method(String... fragments) {
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i);
                if (!l.startsWith(".method ")) continue;
                boolean ok = true;
                for (String f : fragments) if (!l.contains(f)) { ok = false; break; }
                if (!ok) continue;
                for (int j = i + 1; j < lines.size(); j++) {
                    if (lines.get(j).startsWith(".end method")) return new Method(this, i, j);
                }
            }
            return null;
        }

        /** All methods. */
        public List<Method> methods() {
            List<Method> out = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                if (!lines.get(i).startsWith(".method ")) continue;
                for (int j = i + 1; j < lines.size(); j++) {
                    if (lines.get(j).startsWith(".end method")) { out.add(new Method(this, i, j)); i = j; break; }
                }
            }
            return out;
        }

        public int indexOf(String fragment, int from) {
            for (int i = Math.max(0, from); i < lines.size(); i++) if (lines.get(i).contains(fragment)) return i;
            return -1;
        }

        public void insert(int index, String... newLines) {
            List<String> add = new ArrayList<>();
            for (String s : newLines) { add.add(""); add.add(s); }
            lines.addAll(index, add);
        }

        public void save() throws IOException {
            Files.write(path, text().getBytes(StandardCharsets.UTF_8));
            Path rel = root.relativize(path);
            changedDirs.add(root.resolve(rel.getName(0)));
        }
    }

    // -------------------------------------------------------------- method

    public static final class Method {
        final ClassFile cls;
        public int start, end;   // line indexes of ".method" and ".end method"

        Method(ClassFile cls, int start, int end) { this.cls = cls; this.start = start; this.end = end; }

        public String header() { return cls.lines.get(start); }
        public boolean isStatic() { return header().contains(" static "); }

        /** Lines of this method (live view by index). */
        public List<String> body() { return cls.lines.subList(start, end + 1); }

        public int find(String fragment) {
            for (int i = start; i <= end; i++) if (cls.lines.get(i).contains(fragment)) return i;
            return -1;
        }

        public int findRegex(Pattern p, int from) {
            for (int i = Math.max(start, from); i <= end; i++) if (p.matcher(cls.lines.get(i)).find()) return i;
            return -1;
        }

        public int localsLine() {
            for (int i = start; i <= end; i++) {
                String t = cls.lines.get(i).trim();
                if (t.startsWith(".locals ") || t.startsWith(".registers ")) return i;
            }
            return -1;
        }

        public int locals() {
            String t = cls.lines.get(localsLine()).trim();
            int n = Integer.parseInt(t.substring(t.indexOf(' ') + 1).trim());
            if (t.startsWith(".registers ")) n -= paramRegisterCount();
            return n;
        }

        /** Raises .locals to at least n. Parameter registers are named pN, so this is safe. */
        public void ensureLocals(int n) {
            int li = localsLine();
            String t = cls.lines.get(li).trim();
            if (t.startsWith(".registers ")) {
                int regs = Integer.parseInt(t.substring(10).trim());
                int need = n + paramRegisterCount();
                if (regs < need) cls.lines.set(li, "    .registers " + need);
            } else if (locals() < n) {
                cls.lines.set(li, "    .locals " + n);
            }
        }

        /** Adds one local register and returns its name. */
        public String newLocal() {
            int n = locals();
            ensureLocals(n + 1);
            return "v" + n;
        }

        int paramRegisterCount() {
            String h = header();
            String params = h.substring(h.indexOf('(') + 1, h.indexOf(')'));
            int count = isStatic() ? 0 : 1;
            for (int i = 0; i < params.length(); i++) {
                char c = params.charAt(i);
                if (c == 'J' || c == 'D') count += 2;
                else if (c == 'L') { count++; i = params.indexOf(';', i); }
                else if (c == '[') { while (params.charAt(i) == '[') i++; if (params.charAt(i) == 'L') i = params.indexOf(';', i); count++; }
                else count++;
            }
            return count;
        }

        /** Index of the first real instruction (after .locals, annotations, params, debug info). */
        public int firstInstruction() {
            boolean inAnnotation = false;
            for (int i = start + 1; i < end; i++) {
                String t = cls.lines.get(i).trim();
                if (inAnnotation) { if (t.startsWith(".end annotation") || t.startsWith(".end param")) inAnnotation = false; continue; }
                if (t.isEmpty() || t.startsWith("#") || t.startsWith(".locals") || t.startsWith(".registers")
                        || t.startsWith(".line") || t.startsWith(".prologue") || t.startsWith(".local ")) continue;
                if (t.startsWith(".annotation")) { inAnnotation = true; continue; }
                if (t.startsWith(".param")) { if (!t.endsWith("\"") && !t.contains(",")) { /* single-line .param */ }
                    // a .param may open a block ending with .end param
                    if (i + 1 < end && cls.lines.get(i + 1).trim().startsWith(".annotation")) inAnnotation = true;
                    continue; }
                if (t.startsWith(".end param")) continue;
                return i;
            }
            return end;
        }

        public void insert(int index, String... lines) {
            cls.insert(index, lines);
            end += lines.length * 2;
        }

        public void replace(int index, String line) { cls.lines.set(index, line); }

        public void remove(int index) { cls.lines.remove(index); end--; }
    }

    // ------------------------------------------------------------- helpers

    private static final Pattern REG = Pattern.compile("^[vp](\\d+)$");

    /** Register number as written (v3 -> 3, p1 -> resolved by caller); -1 if unknown. */
    public static int regNumber(Method m, String reg) {
        Matcher mm = REG.matcher(reg);
        if (!mm.matches()) return -1;
        int n = Integer.parseInt(mm.group(1));
        if (reg.charAt(0) == 'p') n += m.locals();
        return n;
    }

    /** Whether a plain (non-range) invoke can name these registers. */
    public static boolean fitsInvoke(Method m, String... regs) {
        for (String r : regs) { int n = regNumber(m, r); if (n < 0 || n > 15) return false; }
        return true;
    }

    /** "invoke-static" for one register, valid whatever its number. */
    public static String invokeOne(String reg, String target) {
        return "    invoke-static/range {" + reg + " .. " + reg + "}, " + target;
    }
}
