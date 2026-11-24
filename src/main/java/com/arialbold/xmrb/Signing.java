package com.arialbold.xmrb;

import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Signing with the user's own key. Updates only install over a previous build when they're
 * signed with the same key, so the key is kept (in the builder's folder, or the user's own file).
 */
public final class Signing {

    public static final class Key {
        public final Path file;
        public final char[] password;
        public final String alias;   // null = first key in the file
        public Key(Path file, char[] password, String alias) { this.file = file; this.password = password; this.alias = alias; }
    }

    private Signing() { }

    /** Creates a new personal key with the JDK's keytool. */
    public static Key create(Path file) throws IOException, InterruptedException {
        Files.createDirectories(file.getParent());
        char[] pw = randomPassword();
        String keytool = Paths.javaHomeTool("keytool");
        Process p = new ProcessBuilder(keytool, "-genkeypair", "-keystore", file.toString(), "-storetype", "PKCS12",
                "-alias", "music-rebuilder", "-keyalg", "RSA", "-keysize", "3072", "-validity", "36500",
                "-storepass", new String(pw), "-keypass", new String(pw),
                "-dname", "CN=Xperia Music Re-Builder personal key")
                .redirectErrorStream(true).start();
        String outText = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0 || !Files.exists(file)) throw new IOException("couldn't create a signing key: " + outText.trim());
        return new Key(file, pw, "music-rebuilder");
    }

    private static char[] randomPassword() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        char[] c = new char[24];
        for (int i = 0; i < c.length; i++) c[i] = chars.charAt(r.nextInt(chars.length()));
        return c;
    }

    static KeyStore load(Key k) throws Exception {
        Exception last = null;
        for (String type : new String[]{"PKCS12", "JKS"}) {
            try (InputStream in = new FileInputStream(k.file.toFile())) {
                KeyStore ks = KeyStore.getInstance(type);
                ks.load(in, k.password);
                return ks;
            } catch (Exception e) { last = e; }
        }
        throw new IOException("couldn't open the key file (wrong password?)", last);
    }

    /** Checks the key file opens with this password and holds a signing key. */
    public static String check(Key k) {
        try {
            KeyStore ks = load(k);
            String alias = pickAlias(ks, k.alias);
            if (alias == null) return "the file has no signing key";
            return null;
        } catch (Exception e) {
            return "couldn't open the key file — check the password";
        }
    }

    private static String pickAlias(KeyStore ks, String wanted) throws Exception {
        if (wanted != null && ks.isKeyEntry(wanted)) return wanted;
        for (String a : Collections.list(ks.aliases())) if (ks.isKeyEntry(a)) return a;
        return null;
    }

    public static void sign(Path in, Path out, Key k) throws Exception {
        KeyStore ks = load(k);
        String alias = pickAlias(ks, k.alias);
        if (alias == null) throw new IOException("the key file has no signing key");
        PrivateKey key = (PrivateKey) ks.getKey(alias, k.password);
        List<X509Certificate> certs = new ArrayList<>();
        for (Certificate c : ks.getCertificateChain(alias)) certs.add((X509Certificate) c);
        ApkSigner.SignerConfig cfg = new ApkSigner.SignerConfig.Builder("SIGNER", key, certs).build();
        new ApkSigner.Builder(Collections.singletonList(cfg))
                .setInputApk(in.toFile())
                .setOutputApk(out.toFile())
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setCreatedBy("Xperia Music Re-Builder")
                .build()
                .sign();
        ApkVerifier.Result r = new ApkVerifier.Builder(out.toFile()).build().verify();
        if (!r.isVerified()) throw new IOException("signature check failed: " + r.getErrors());
    }

    static final class Paths {
        static String javaHomeTool(String name) {
            String exe = System.getProperty("os.name").toLowerCase().contains("win") ? name + ".exe" : name;
            File f = new File(new File(System.getProperty("java.home"), "bin"), exe);
            return f.exists() ? f.getPath() : name;
        }
    }
}
