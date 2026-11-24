package com.arialbold.xmrb;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Writes the rebuilt APK: every entry of Sony's original APK as it was (resources untouched),
 * with replaced files swapped in, extra files added, old signatures and stale profiles dropped,
 * and uncompressed entries aligned to 4 bytes as Android requires.
 */
public final class ApkAssembler {

    private ApkAssembler() { }

    public static void write(Path original, Path out, Map<String, byte[]> replace, Map<String, byte[]> add) throws IOException {
        try (ZipFile src = new ZipFile(original.toFile());
             CountingStream cs = new CountingStream(new BufferedOutputStream(new FileOutputStream(out.toFile())));
             ZipOutputStream zo = new ZipOutputStream(cs)) {
            Enumeration<? extends ZipEntry> en = src.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if (isOldSignature(name) || name.startsWith("assets/dexopt/") || e.isDirectory()) continue;
                byte[] data = replace.containsKey(name) ? replace.get(name) : readAll(src, e);
                put(zo, cs, name, data, e.getMethod() == ZipEntry.STORED, e.getTime());
            }
            for (Map.Entry<String, byte[]> a : add.entrySet()) {
                put(zo, cs, a.getKey(), a.getValue(), true, 1230768000000L);
            }
        }
    }

    static boolean isOldSignature(String n) {
        if (!n.startsWith("META-INF/") || n.indexOf('/', 9) >= 0) return false;
        String u = n.toUpperCase();
        return u.endsWith(".SF") || u.endsWith(".RSA") || u.endsWith(".DSA") || u.endsWith(".EC") || u.equals("META-INF/MANIFEST.MF");
    }

    private static void put(ZipOutputStream zo, CountingStream cs, String name, byte[] data, boolean stored, long time) throws IOException {
        ZipEntry z = new ZipEntry(name);
        z.setTime(time);
        if (stored) {
            z.setMethod(ZipEntry.STORED);
            z.setSize(data.length);
            z.setCompressedSize(data.length);
            CRC32 crc = new CRC32();
            crc.update(data);
            z.setCrc(crc.getValue());
            // local header: 30 bytes + name + extra; pad the extra field so data starts on a 4-byte boundary
            int nameLen = name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            long dataStart = cs.count + 30 + nameLen;
            int pad = (int) ((4 - (dataStart % 4)) % 4);
            if (pad > 0) {
                // ZipOutputStream may add its own extended-timestamp extra; use a fixed DOS time to avoid that
                z.setExtra(new byte[pad]);
            }
        } else {
            z.setMethod(ZipEntry.DEFLATED);
        }
        zo.putNextEntry(z);
        zo.write(data);
        zo.closeEntry();
    }

    private static byte[] readAll(ZipFile zf, ZipEntry e) throws IOException {
        try (InputStream in = zf.getInputStream(e)) { return in.readAllBytes(); }
    }

    /** Checks every stored entry is 4-byte aligned (reads local headers). */
    public static void verifyAlignment(Path apk) throws IOException {
        byte[] all = Files.readAllBytes(apk);
        try (ZipFile zf = new ZipFile(apk.toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(all).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            int pos = 0;
            while (pos + 30 <= all.length && b.getInt(pos) == 0x04034b50) {
                int method = b.getShort(pos + 8) & 0xffff;
                int csize = b.getInt(pos + 18);
                int nl = b.getShort(pos + 26) & 0xffff, el = b.getShort(pos + 28) & 0xffff;
                int flags = b.getShort(pos + 6) & 0xffff;
                int data = pos + 30 + nl + el;
                String name = new String(all, pos + 30, nl, java.nio.charset.StandardCharsets.UTF_8);
                if (method == 0 && data % 4 != 0) throw new IOException("not aligned: " + name);
                if ((flags & 8) != 0) {
                    // size is in the data descriptor; get it from the central directory
                    ZipEntry ze = zf.getEntry(name);
                    csize = (int) ze.getCompressedSize();
                    pos = data + csize + 16;   // descriptor with signature
                    if (b.getInt(data + csize) != 0x08074b50) pos -= 4;
                } else {
                    pos = data + csize;
                }
            }
        }
    }

    static final class CountingStream extends OutputStream {
        final OutputStream o;
        long count;
        CountingStream(OutputStream o) { this.o = o; }
        @Override public void write(int b) throws IOException { o.write(b); count++; }
        @Override public void write(byte[] b, int off, int len) throws IOException { o.write(b, off, len); count += len; }
        @Override public void flush() throws IOException { o.flush(); }
        @Override public void close() throws IOException { o.close(); }
    }
}
