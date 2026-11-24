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
}
