package com.arialbold.xmrb;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * Look and feel. Uses FlatLaf (bundled in the release builds) with the macOS theme on a Mac and
 * the Windows 11-style theme elsewhere, following the system's light or dark setting.
 * Falls back to the platform look if FlatLaf isn't on the class path.
 */
final class Theme {
    private static boolean dark;
    private static boolean flat;

    private Theme() { }

    static boolean isMac() { return System.getProperty("os.name").toLowerCase().contains("mac"); }
    static boolean isWindows() { return System.getProperty("os.name").toLowerCase().contains("win"); }

    static void install() {
        if (isMac()) {
            System.setProperty("apple.awt.application.name", "Xperia Music Re-Builder");
            System.setProperty("apple.awt.application.appearance", "system");
            System.setProperty("apple.laf.useScreenMenuBar", "true");
        }
        String force = System.getProperty("xmrb.theme");
        dark = force != null ? force.equals("dark") : systemIsDark();
        String cls = isMac()
                ? (dark ? "com.formdev.flatlaf.themes.FlatMacDarkLaf" : "com.formdev.flatlaf.themes.FlatMacLightLaf")
                : (dark ? "com.formdev.flatlaf.FlatDarkLaf" : "com.formdev.flatlaf.FlatLightLaf");
        if (System.getProperty("xmrb.laf") != null) cls = System.getProperty("xmrb.laf");
        try {
            if (isWindows()) {
                JFrame.setDefaultLookAndFeelDecorated(true);
                JDialog.setDefaultLookAndFeelDecorated(true);
            }
            UIManager.put("Button.arc", 10);
            UIManager.put("Component.arc", 8);
            UIManager.put("TextComponent.arc", 8);
            UIManager.put("ProgressBar.arc", 6);
            UIManager.put("ScrollBar.thumbArc", 999);
            UIManager.put("ScrollBar.width", 10);
            UIManager.setLookAndFeel(cls);
            flat = true;
        } catch (Exception e) {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) { }
        }
    }

    /** Asks the OS whether dark mode is on. */
    private static boolean systemIsDark() {
        try {
            if (isMac()) {
                return run("defaults", "read", "-g", "AppleInterfaceStyle").trim().equalsIgnoreCase("dark");
            }
            if (isWindows()) {
                String out = run("reg", "query",
                        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                        "/v", "AppsUseLightTheme");
                return out.contains("0x0");
            }
        } catch (Exception ignored) { }
        return false;
    }

    private static String run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        p.waitFor();
        return sb.toString();
    }

    /** Transparent title bar with the content running underneath, as in current macOS apps. */
    static void macWindow(JFrame f) {
        if (!isMac()) return;
        JRootPane rp = f.getRootPane();
        rp.putClientProperty("apple.awt.fullWindowContent", true);
        rp.putClientProperty("apple.awt.transparentTitleBar", true);
        rp.putClientProperty("apple.awt.windowTitleVisible", false);
    }

    static Font font(int style, float size) {
        Font base = UIManager.getFont("Label.font");
        if (base == null) base = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
        return base.deriveFont(style, size);
    }

    static Color accent() {
        Color c = flat ? UIManager.getColor("Component.accentColor") : null;
        if (c != null) return c;
        if (isMac()) return dark ? new Color(0x0A84FF) : new Color(0x007AFF);
        return dark ? new Color(0x4CC2FF) : new Color(0x005FB8);
    }

    static Color secondary() { return dark ? new Color(0x9A9AA0) : new Color(0x6E6E73); }
    static Color warning() { return dark ? new Color(0xFFB340) : new Color(0xB25E00); }
    static Color card() { return dark ? new Color(255, 255, 255, 14) : new Color(0, 0, 0, 9); }
    static Color cardBorder() { return dark ? new Color(255, 255, 255, 40) : new Color(0, 0, 0, 38); }
    static Color disabledFill() { return dark ? new Color(255, 255, 255, 30) : new Color(0, 0, 0, 22); }

    static Image icon() {
        try (InputStream in = Theme.class.getResourceAsStream("icon.png")) {
            return in == null ? null : ImageIO.read(in);
        } catch (Exception e) { return null; }
    }
}
