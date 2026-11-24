package com.arialbold.xmrb;

import java.nio.file.Path;

/** Starts the window, or with --cli builds from the command line. */
public final class Main {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--cli")) {
            if (args.length < 5) {
                System.err.println("Usage: --cli <sony-music.apk> <output.apk> <key file (.jks/.p12)> <key password>");
                System.exit(2);
            }
            Path in = Path.of(args[1]), out = Path.of(args[2]);
            Signing.Key key = new Signing.Key(Path.of(args[3]), args[4].toCharArray(), null);
            String problem = Signing.check(key);
            if (problem != null) { System.err.println(problem); System.exit(1); }
            Engine.Outcome o = Engine.build(in, out, key, Path.of(System.getProperty("java.io.tmpdir"), "xmrb-work"),
                    new Engine.Listener() {
                        public void log(String line) { System.out.println("  " + line); }
                        public void progress(int pct, String step) { System.out.println("[" + pct + "%] " + step); }
                    });
            System.out.println(o.allApplied() ? "All features added." : "Built, but some features couldn't be added (see above).");
            System.out.println("Saved " + o.apk);
            return;
        }
        BuilderWindow.open();
    }
}
