package com.arialbold.xmrb;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and patches the app's binary AndroidManifest.xml directly, so nothing else in the
 * manifest or resources is re-encoded (re-encoding is what made earlier builds fail to install).
 */
public final class Manifest {
    private final byte[] d;
    private final List<String> strings = new ArrayList<>();
    private int poolEnd;

    public Manifest(byte[] data) {
        d = data.clone();
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getShort(0) != 0x0003) throw new IllegalArgumentException("not a binary manifest");
        int p = 8;
        int type = b.getShort(p) & 0xffff, hs = b.getShort(p + 2) & 0xffff, sz = b.getInt(p + 4);
        if (type != 0x0001) throw new IllegalArgumentException("manifest has no string pool");
        int count = b.getInt(p + 8), flags = b.getInt(p + 16), sStart = b.getInt(p + 20);
        boolean utf8 = (flags & 0x100) != 0;
        for (int i = 0; i < count; i++) {
            int o = p + sStart + b.getInt(p + hs + i * 4);
            if (utf8) {
                int n = d[o] & 0xff; o++;
                if ((n & 0x80) != 0) o++;
                n = d[o] & 0xff; o++;
                if ((n & 0x80) != 0) { n = ((n & 0x7f) << 8) | (d[o] & 0xff); o++; }
                strings.add(new String(d, o, n, StandardCharsets.UTF_8));
            } else {
                int n = b.getShort(o) & 0xffff; o += 2;
                if ((n & 0x8000) != 0) { n = ((n & 0x7fff) << 16) | (b.getShort(o) & 0xffff); o += 2; }
                strings.add(new String(d, o, n * 2, StandardCharsets.UTF_16LE));
            }
        }
        poolEnd = p + sz;
    }

    public byte[] bytes() { return d.clone(); }

    /** Attribute value (string or raw string) of the first element with this name, or null. */
    public String attr(String element, String attribute) {
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        int q = poolEnd;
        while (q + 8 <= d.length) {
            int type = b.getShort(q) & 0xffff, sz = b.getInt(q + 4);
            if (sz <= 0) break;
            if (type == 0x0102 && strings.get(b.getInt(q + 20)).equals(element)) {
                int astart = b.getShort(q + 24) & 0xffff, asize = b.getShort(q + 26) & 0xffff, acount = b.getShort(q + 28) & 0xffff;
                for (int i = 0; i < acount; i++) {
                    int a = q + 16 + astart + i * asize;
                    if (strings.get(b.getInt(a + 4)).equals(attribute)) {
                        int raw = b.getInt(a + 8);
                        if (raw >= 0) return strings.get(raw);
                        int dt = d[a + 15] & 0xff, val = b.getInt(a + 16);
                        if (dt == 0x03) return strings.get(val);
                        if (dt == 0x10) return String.valueOf(val);
                        return null;
                    }
                }
                return null;
            }
            q += sz;
        }
        return null;
    }

    /** Sets android:required="false" on a <uses-library> by name. Returns true if changed. */
    public boolean makeLibraryOptional(String library) {
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        int target = strings.indexOf(library);
        if (target < 0) return false;
        int q = poolEnd;
        boolean changed = false;
        while (q + 8 <= d.length) {
            int type = b.getShort(q) & 0xffff, sz = b.getInt(q + 4);
            if (sz <= 0) break;
            if (type == 0x0102 && strings.get(b.getInt(q + 20)).equals("uses-library")) {
                int astart = b.getShort(q + 24) & 0xffff, asize = b.getShort(q + 26) & 0xffff, acount = b.getShort(q + 28) & 0xffff;
                int nameRaw = -1, requiredAt = -1;
                for (int i = 0; i < acount; i++) {
                    int a = q + 16 + astart + i * asize;
                    String an = strings.get(b.getInt(a + 4));
                    if (an.equals("name")) nameRaw = b.getInt(a + 8);
                    if (an.equals("required")) requiredAt = a;
                }
                if (nameRaw == target && requiredAt > 0 && (d[requiredAt + 15] & 0xff) == 0x12 && b.getInt(requiredAt + 16) != 0) {
                    b.putInt(requiredAt + 16, 0);
                    changed = true;
                }
            }
            q += sz;
        }
        return changed;
    }

    /** True if the app currently requires this library. */
    public boolean requiresLibrary(String library) {
        Manifest copy = new Manifest(d);
        return copy.makeLibraryOptional(library);
    }

    /**
     * Returns a copy of this manifest with a new &lt;service&gt; added right after an existing one,
     * copied from it (same exported/foregroundServiceType settings) but with a new class name and
     * without android:process, so it runs in the app's main process. Returns null if the template
     * service isn't found, or if a service with the new name already exists.
     */
    public byte[] withServiceLike(String templateClass, String newClass) {
        if (strings.contains(newClass)) return null;
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        int tmpl = strings.indexOf(templateClass);
        if (tmpl < 0) return null;
        // find the template's start and matching end element
        int q = poolEnd, start = -1, startLen = 0, end = -1, depth = 0;
        while (q + 8 <= d.length) {
            int type = b.getShort(q) & 0xffff, sz = b.getInt(q + 4);
            if (sz <= 0) break;
            if (start < 0 && type == 0x0102 && strings.get(b.getInt(q + 20)).equals("service")) {
                int astart = b.getShort(q + 24) & 0xffff, asize = b.getShort(q + 26) & 0xffff, acount = b.getShort(q + 28) & 0xffff;
                for (int i = 0; i < acount; i++) {
                    int a = q + 16 + astart + i * asize;
                    if (strings.get(b.getInt(a + 4)).equals("name") && b.getInt(a + 8) == tmpl) { start = q; startLen = sz; depth = 0; }
                }
            } else if (start >= 0) {
                if (type == 0x0102) depth++;
                else if (type == 0x0103) { if (depth == 0) { end = q; break; } depth--; }
            }
            q += sz;
        }
        if (start < 0 || end < 0 || end != start + startLen) return null;   // only simple, childless services
        int endLen = b.getInt(end + 4);
        int newIndex = strings.size();

        // the new start element: template attributes, minus "process", with the new name
        int astart = b.getShort(start + 24) & 0xffff, asize = b.getShort(start + 26) & 0xffff, acount = b.getShort(start + 28) & 0xffff;
        int idIdx = b.getShort(start + 30) & 0xffff, classIdx = b.getShort(start + 32) & 0xffff, styleIdx = b.getShort(start + 34) & 0xffff;
        if (idIdx != 0 || classIdx != 0 || styleIdx != 0) return null;
        java.io.ByteArrayOutputStream attrs = new java.io.ByteArrayOutputStream();
        int kept = 0;
        for (int i = 0; i < acount; i++) {
            int a = start + 16 + astart + i * asize;
            String an = strings.get(b.getInt(a + 4));
            if (an.equals("process")) continue;
            byte[] at = java.util.Arrays.copyOfRange(d, a, a + asize);
            if (an.equals("name")) {
                ByteBuffer ab = ByteBuffer.wrap(at).order(ByteOrder.LITTLE_ENDIAN);
                ab.putInt(8, newIndex);
                ab.put(15, (byte) 0x03);
                ab.putInt(16, newIndex);
            }
            attrs.write(at, 0, at.length);
            kept++;
        }
        byte[] attrBytes = attrs.toByteArray();
        int headLen = 16 + astart;
        ByteBuffer el = ByteBuffer.allocate(headLen + attrBytes.length).order(ByteOrder.LITTLE_ENDIAN);
        el.put(d, start, headLen).put(attrBytes);
        el.putInt(4, el.capacity());
        el.putShort(28, (short) kept);
        byte[] newStart = el.array();
        byte[] newEnd = java.util.Arrays.copyOfRange(d, end, end + endLen);

        // string pool with the new class name appended
        byte[] pool = poolWith(newClass);
        int insertAt = end + endLen;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(d, 0, 8);
        out.write(pool, 0, pool.length);
        out.write(d, poolEnd, insertAt - poolEnd);
        out.write(newStart, 0, newStart.length);
        out.write(newEnd, 0, newEnd.length);
        out.write(d, insertAt, d.length - insertAt);
        byte[] r = out.toByteArray();
        ByteBuffer.wrap(r).order(ByteOrder.LITTLE_ENDIAN).putInt(4, r.length);
        return r;
    }

    private byte[] poolWith(String extra) {
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        int p = 8;
        int hs = b.getShort(p + 2) & 0xffff, sz = b.getInt(p + 4);
        int count = b.getInt(p + 8), styles = b.getInt(p + 12), flags = b.getInt(p + 16);
        int sStart = b.getInt(p + 20), stStart = b.getInt(p + 24);
        boolean utf8 = (flags & 0x100) != 0;
        int dataEnd = styles > 0 ? stStart : sz;                 // string data region [sStart, dataEnd)
        byte[] data = java.util.Arrays.copyOfRange(d, p + sStart, p + dataEnd);
        byte[] styleData = styles > 0 ? java.util.Arrays.copyOfRange(d, p + stStart, p + sz) : new byte[0];
        // encode the new string
        java.io.ByteArrayOutputStream enc = new java.io.ByteArrayOutputStream();
        if (utf8) {
            byte[] u = extra.getBytes(StandardCharsets.UTF_8);
            int chars = extra.length();
            if (chars > 0x7f) { enc.write(0x80 | (chars >> 8)); }
            enc.write(chars & 0xff);
            if (u.length > 0x7f) { enc.write(0x80 | (u.length >> 8)); }
            enc.write(u.length & 0xff);
            enc.write(u, 0, u.length);
            enc.write(0);
        } else {
            byte[] u = extra.getBytes(StandardCharsets.UTF_16LE);
            int chars = u.length / 2;
            enc.write(chars & 0xff); enc.write((chars >> 8) & 0xff);
            enc.write(u, 0, u.length);
            enc.write(0); enc.write(0);
        }
        byte[] e = enc.toByteArray();
        int newOffset = data.length;
        int newDataLen = data.length + e.length;
        int padded = (newDataLen + 3) & ~3;
        int offsetsLen = (count + 1) * 4 + styles * 4;
        int newSStart = hs + offsetsLen;
        int newStStart = styles > 0 ? newSStart + padded : 0;
        int newSize = newSStart + padded + styleData.length;
        ByteBuffer o = ByteBuffer.allocate(newSize).order(ByteOrder.LITTLE_ENDIAN);
        o.put(d, p, hs);
        o.putInt(4, newSize);
        o.putInt(8, count + 1);
        o.putInt(20, newSStart);
        o.putInt(24, newStStart);
        o.position(hs);
        for (int i = 0; i < count; i++) o.putInt(b.getInt(p + hs + i * 4));
        o.putInt(newOffset);
        for (int i = 0; i < styles; i++) o.putInt(b.getInt(p + hs + count * 4 + i * 4));
        o.put(data).put(e);
        o.position(newSStart + padded);
        o.put(styleData);
        return o.array();
    }
}
