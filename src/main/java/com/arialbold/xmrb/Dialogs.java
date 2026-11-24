package com.arialbold.xmrb;

import java.awt.*;
import java.io.File;
import java.nio.file.Path;
import java.util.Locale;

/** Native open/save dialogs and "show in Finder/Explorer". */
final class Dialogs {
    private Dialogs() { }

    static Path open(Frame owner, String title, String... extensions) {
        FileDialog d = new FileDialog(owner, title, FileDialog.LOAD);
        d.setFilenameFilter((dir, name) -> {
            String n = name.toLowerCase(Locale.ROOT);
            for (String e : extensions) if (n.endsWith("." + e)) return true;
            return false;
        });
        if (Theme.isWindows()) d.setFile("*." + String.join(";*.", extensions));
        d.setVisible(true);
        if (d.getFile() == null) return null;
        return new File(d.getDirectory(), d.getFile()).toPath();
    }

    static Path save(Frame owner, String title, String suggested) {
        FileDialog d = new FileDialog(owner, title, FileDialog.SAVE);
        d.setFile(suggested);
        d.setVisible(true);
        if (d.getFile() == null) return null;
        String name = d.getFile();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".apk")) name += ".apk";
        return new File(d.getDirectory(), name).toPath();
    }

    static void reveal(Path file) {
        if (file == null) return;
        try {
            if (Theme.isMac()) {
                new ProcessBuilder("open", "-R", file.toString()).start();
            } else if (Theme.isWindows()) {
                new ProcessBuilder("explorer.exe", "/select,", file.toString()).start();
            } else if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(file.getParent().toFile());
            }
        } catch (Exception ignored) { }
    }
}
