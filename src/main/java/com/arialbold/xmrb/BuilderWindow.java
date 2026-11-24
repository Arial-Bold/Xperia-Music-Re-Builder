package com.arialbold.xmrb;

import com.arialbold.xmrb.patches.Patches;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/** The builder's window: pick the APK, pick the key, build. */
final class BuilderWindow {
    private static final String TITLE = "Xperia Music Re-Builder";

    private final JFrame frame = new JFrame(TITLE);
    private final Properties settings = new Properties();
    private final Path home = appFolder();

    private Path apk;
    private Engine.Info info;

    private final JLabel apkTitle = new JLabel("Drop Sony’s Music APK here");
    private final JTextArea apkDetail = text("or choose the file — Sony’s Music app, version 9.4 or newer", 320);
    private final JButton chooseApk = new JButton("Choose File…");
    private final JRadioButton keyCreate = new JRadioButton("Create a key for me and keep it");
    private final JRadioButton keyOwn = new JRadioButton("Use my own key file");
    private final JLabel keyFileLabel = new JLabel(" ");
    private final JButton chooseKey = new JButton("Choose…");
    private final JPasswordField keyPassword = new JPasswordField(18);
    private final JCheckBox rememberPassword = new JCheckBox("Remember password");
    private final AccentButton build = new AccentButton("Build App");
    private final JProgressBar progress = new JProgressBar(0, 100);
    private final JLabel status = new JLabel(" ");
    private final JTextArea log = new JTextArea(8, 40);
    private final JPanel results = new JPanel();
    private final JButton reveal = new JButton();
    private Path lastOutput;
    private Path ownKey;

    static void open() {
        Theme.install();
        SwingUtilities.invokeLater(() -> new BuilderWindow().show());
    }

    private BuilderWindow() {
        loadSettings();
    }

    // ------------------------------------------------------------------ UI

    private void show() {
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        Image icon = Theme.icon();
        if (icon != null) frame.setIconImage(icon);
        JPanel root = new JPanel(new BorderLayout());
        root.setBorder(new EmptyBorder(Theme.isMac() ? 34 : 20, 24, 20, 24));
        Theme.macWindow(frame);

        Box col = Box.createVerticalBox();
        JLabel title = new JLabel(TITLE);
        title.setFont(Theme.font(Font.BOLD, 22f));
        JTextArea sub = text("Rebuilds Sony’s Xperia Music app so it runs on any Android phone, "
                + "with DSD and DST playback, ReplayGain, album art downloads and more.", 560);
        sub.setForeground(Theme.secondary());
        col.add(left(title));
        col.add(Box.createVerticalStrut(4));
        col.add(left(sub));
        col.add(Box.createVerticalStrut(18));
        col.add(left(sectionLabel("1", "Sony Music app")));
        col.add(Box.createVerticalStrut(8));
        col.add(left(dropCard()));
        col.add(Box.createVerticalStrut(18));
        col.add(left(sectionLabel("2", "Signing key")));
        col.add(Box.createVerticalStrut(8));
        col.add(left(keyCard()));
        col.add(Box.createVerticalStrut(18));

        JPanel actions = new JPanel(new BorderLayout(12, 0));
        actions.setOpaque(false);
        progress.setVisible(false);
        status.setForeground(Theme.secondary());
        JPanel st = new JPanel(new BorderLayout(0, 6));
        st.setOpaque(false);
        st.add(status, BorderLayout.NORTH);
        st.add(progress, BorderLayout.SOUTH);
        actions.add(st, BorderLayout.CENTER);
        actions.add(build, BorderLayout.EAST);
        col.add(left(actions));
        col.add(Box.createVerticalStrut(12));

        results.setLayout(new BoxLayout(results, BoxLayout.Y_AXIS));
        results.setOpaque(false);
        results.setVisible(false);
        col.add(left(results));

        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        JScrollPane logScroll = new JScrollPane(log);
        logScroll.setVisible(false);
        JButton details = linkButton("Show details");
        details.addActionListener(e -> {
            boolean v = !logScroll.isVisible();
            logScroll.setVisible(v);
            details.setText(v ? "Hide details" : "Show details");
            relayout();
        });
        col.add(Box.createVerticalStrut(8));
        col.add(left(details));
        col.add(left(logScroll));

        root.add(col, BorderLayout.NORTH);
        frame.setContentPane(root);
        build.addActionListener(e -> startBuild());
        build.setEnabled(false);
        relayout();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        refresh();
    }

    private static final int WIDTH = 620;

    /** Fits the height to the content and keeps the width fixed. */
    private void relayout() {
        frame.getContentPane().invalidate();
        Dimension d = frame.getPreferredSize();
        frame.setMinimumSize(new Dimension(WIDTH, 200));
        frame.setSize(WIDTH, d.height);
        frame.validate();
    }

    /** Read-only text that wraps at a fixed width (Swing HTML widths scale differently per screen). */
    private static JTextArea text(String value, int width) {
        JTextArea t = new JTextArea(value) {
            @Override public Dimension getPreferredSize() {
                setSize(width, Short.MAX_VALUE);
                return new Dimension(width, super.getPreferredSize().height);
            }
            @Override public Dimension getMaximumSize() { return getPreferredSize(); }
        };
        t.setLineWrap(true);
        t.setWrapStyleWord(true);
        t.setEditable(false);
        t.setFocusable(false);
        t.setOpaque(false);
        t.setBorder(null);
        t.setHighlighter(null);
        t.setTransferHandler(null);   // let drops fall through to the drop card
        t.setDropTarget(null);
        t.setFont(UIManager.getFont("Label.font"));
        t.setForeground(Theme.secondary());
        return t;
    }

    private static JComponent left(JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        return c;
    }

    private JComponent sectionLabel(String n, String text) {
        JLabel l = new JLabel(n + "   " + text);
        l.setFont(Theme.font(Font.BOLD, 14f));
        return l;
    }

    private JComponent dropCard() {
        Card card = new Card(true);
        card.setLayout(new BorderLayout(14, 0));
        card.setBorder(new EmptyBorder(18, 18, 18, 18));
        JLabel glyph = new JLabel("♫");
        glyph.setFont(Theme.font(Font.PLAIN, 30f));
        glyph.setForeground(Theme.accent());
        apkTitle.setFont(Theme.font(Font.BOLD, 14f));
        apkDetail.setForeground(Theme.secondary());
        JPanel text = new JPanel();
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.setOpaque(false);
        text.add(left(apkTitle));
        text.add(Box.createVerticalStrut(3));
        text.add(left(apkDetail));
        JPanel middle = new JPanel(new GridBagLayout());   // centres the text block vertically
        middle.setOpaque(false);
        GridBagConstraints gc = new GridBagConstraints();
        gc.anchor = GridBagConstraints.WEST; gc.weightx = 1;
        middle.add(text, gc);
        card.add(glyph, BorderLayout.WEST);
        card.add(middle, BorderLayout.CENTER);
        JPanel btn = new JPanel(new GridBagLayout());
        btn.setOpaque(false);
        btn.add(chooseApk);
        card.add(btn, BorderLayout.EAST);
        chooseApk.addActionListener(e -> pickApk());
        card.setTransferHandler(new TransferHandler() {
            @Override public boolean canImport(TransferSupport s) { return s.isDataFlavorSupported(DataFlavor.javaFileListFlavor); }
            @Override @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport s) {
                try {
                    List<File> files = (List<File>) s.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    if (!files.isEmpty()) setApk(files.get(0).toPath());
                    return true;
                } catch (Exception e) { return false; }
            }
        });
        frame.setTransferHandler(card.getTransferHandler());
        return card;
    }

    private JComponent keyCard() {
        Card card = new Card(false);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(new EmptyBorder(14, 18, 14, 18));
        ButtonGroup g = new ButtonGroup();
        g.add(keyCreate); g.add(keyOwn);
        keyCreate.setOpaque(false); keyOwn.setOpaque(false); rememberPassword.setOpaque(false);
        JTextArea createNote = text("Saved in " + home.resolve("signing-key.p12"), 500);
        createNote.setForeground(Theme.secondary());
        createNote.setFont(Theme.font(Font.PLAIN, 11.5f));
        createNote.setBorder(new EmptyBorder(0, 24, 6, 0));
        JPanel own = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        own.setOpaque(false);
        own.setBorder(new EmptyBorder(2, 18, 0, 0));
        own.add(chooseKey);
        own.add(keyFileLabel);
        JPanel pw = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        pw.setOpaque(false);
        pw.setBorder(new EmptyBorder(6, 18, 0, 0));
        pw.add(new JLabel("Password"));
        pw.add(keyPassword);
        pw.add(rememberPassword);
        JTextArea tip = text("Use the same key every time, so new builds install over the old one "
                + "and keep your playlists and settings.", 530);
        tip.setForeground(Theme.secondary());
        tip.setFont(Theme.font(Font.PLAIN, 11.5f));
        tip.setBorder(new EmptyBorder(10, 0, 0, 0));
        for (JComponent c : new JComponent[]{keyCreate, createNote, keyOwn, own, pw, tip}) card.add(left(c));
        keyCreate.addActionListener(e -> refresh());
        keyOwn.addActionListener(e -> refresh());
        chooseKey.addActionListener(e -> pickKey());
        keyPassword.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { refresh(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { refresh(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { refresh(); }
        });
        if ("own".equals(settings.getProperty("key.mode"))) keyOwn.setSelected(true); else keyCreate.setSelected(true);
        String kp = settings.getProperty("key.file");
        if (kp != null && Files.exists(Path.of(kp))) ownKey = Path.of(kp);
        String saved = settings.getProperty("key.password");
        if (saved != null) { keyPassword.setText(saved); rememberPassword.setSelected(true); }
        return card;
    }

    private JButton linkButton(String text) {
        JButton b = new JButton(text);
        b.setBorderPainted(false);
        b.setContentAreaFilled(false);
        b.setFocusPainted(false);
        b.setForeground(Theme.accent());
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setBorder(new EmptyBorder(0, 0, 0, 0));
        return b;
    }

    private void refresh() {
        boolean own = keyOwn.isSelected();
        chooseKey.setEnabled(own);
        keyPassword.setEnabled(own);
        rememberPassword.setEnabled(own);
        keyFileLabel.setText(ownKey != null ? ownKey.getFileName().toString() : "No file chosen");
        keyFileLabel.setForeground(ownKey != null && own ? UIManager.getColor("Label.foreground") : Theme.secondary());
        boolean keyOk = !own || (ownKey != null && keyPassword.getPassword().length > 0);
        build.setEnabled(info != null && keyOk);
    }

    // ------------------------------------------------------------- actions

    private void pickApk() {
        Path p = Dialogs.open(frame, "Choose Sony’s Music APK", "apk");
        if (p != null) setApk(p);
    }

    private void setApk(Path p) {
        results.setVisible(false);
        try {
            Engine.Info i = Engine.inspect(p);
            if (i.alreadyRebuilt) throw new IOException("This APK has already been rebuilt. Use Sony’s original APK.");
            apk = p; info = i;
            apkTitle.setText("Music " + i.versionName);
            apkDetail.setText(p.getFileName().toString());
            chooseApk.setText("Change…");
        } catch (IOException e) {
            apk = null; info = null;
            apkTitle.setText("That file can’t be used");
            apkDetail.setText(e.getMessage());
        }
        refresh();
        relayout();
    }

    private void pickKey() {
        Path p = Dialogs.open(frame, "Choose your key file", "jks", "p12", "keystore", "pfx");
        if (p != null) { ownKey = p; refresh(); }
    }

    private void startBuild() {
        String ver = info.versionName == null ? "" : info.versionName;
        Path out = Dialogs.save(frame, "Save the rebuilt app", "Xperia Music " + ver + " rebuilt.apk");
        if (out == null) return;
        build.setEnabled(false);
        chooseApk.setEnabled(false);
        results.removeAll();
        results.setVisible(false);
        log.setText("");
        progress.setVisible(true);
        progress.setValue(0);
        status.setText("Starting…");
        relayout();

        final boolean own = keyOwn.isSelected();
        final char[] pw = keyPassword.getPassword();
        new SwingWorker<Engine.Outcome, String>() {
            @Override protected Engine.Outcome doInBackground() throws Exception {
                Signing.Key key;
                if (own) {
                    key = new Signing.Key(ownKey, pw, null);
                    String problem = Signing.check(key);
                    if (problem != null) throw new IOException("Your key file: " + problem + ".");
                } else {
                    key = createdKey();
                }
                saveSettings(own, pw);
                return Engine.build(apk, out, key, home.resolve("work"), new Engine.Listener() {
                    public void log(String line) { publish(line); }
                    public void progress(int pct, String step) {
                        SwingUtilities.invokeLater(() -> { progress.setValue(pct); status.setText(step); });
                    }
                });
            }
            @Override protected void process(List<String> lines) {
                for (String l : lines) log.append(l + "\n");
            }
            @Override protected void done() {
                progress.setVisible(false);
                chooseApk.setEnabled(true);
                refresh();
                try {
                    Engine.Outcome o = get();
                    lastOutput = o.apk;
                    status.setText(o.allApplied() ? "Done. Every feature was added." : "Done, but some features couldn’t be added to this version.");
                    showResults(o);
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    status.setText("The build didn’t finish.");
                    log.append("\n" + c + "\n");
                    JOptionPane.showMessageDialog(frame, c.getMessage(), "Couldn’t build the app", JOptionPane.ERROR_MESSAGE);
                }
                relayout();
            }
        }.execute();
    }

    private void showResults(Engine.Outcome o) {
        results.removeAll();
        Card card = new Card(false);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(new EmptyBorder(12, 18, 14, 18));
        for (Patches.Result r : o.results) {
            JLabel l = new JLabel((r.applied ? "✓  " : "✗  ") + r.feature + (r.applied ? "" : " — " + r.detail));
            l.setForeground(r.applied ? UIManager.getColor("Label.foreground") : Theme.warning());
            l.setBorder(new EmptyBorder(2, 0, 2, 0));
            card.add(left(l));
        }
        reveal.setText(Theme.isMac() ? "Show in Finder" : Theme.isWindows() ? "Show in Explorer" : "Show Folder");
        for (java.awt.event.ActionListener a : reveal.getActionListeners()) reveal.removeActionListener(a);
        reveal.addActionListener(e -> Dialogs.reveal(lastOutput));
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(10, 0, 0, 0));
        JLabel saved = new JLabel("Saved as " + o.apk.getFileName() + "   ");
        saved.setForeground(Theme.secondary());
        row.add(saved);
        row.add(reveal);
        card.add(left(row));
        results.add(left(card));
        results.setVisible(true);
    }

    // ------------------------------------------------------------ settings

    private Signing.Key createdKey() throws Exception {
        Path f = home.resolve("signing-key.p12");
        Path pwFile = home.resolve("signing-key.password");
        if (Files.exists(f) && Files.exists(pwFile)) {
            return new Signing.Key(f, Files.readString(pwFile).trim().toCharArray(), null);
        }
        Signing.Key k = Signing.create(f);
        Files.writeString(pwFile, new String(k.password));
        return k;
    }

    private void loadSettings() {
        try { Files.createDirectories(home); } catch (IOException ignored) { }
        Path p = home.resolve("settings.properties");
        if (Files.exists(p)) try (InputStream in = Files.newInputStream(p)) { settings.load(in); } catch (IOException ignored) { }
    }

    private void saveSettings(boolean own, char[] pw) {
        settings.setProperty("key.mode", own ? "own" : "create");
        if (ownKey != null) settings.setProperty("key.file", ownKey.toString());
        if (own && rememberPassword.isSelected()) settings.setProperty("key.password", new String(pw));
        else settings.remove("key.password");
        try (OutputStream o = Files.newOutputStream(home.resolve("settings.properties"))) {
            settings.store(o, TITLE + " settings");
        } catch (IOException ignored) { }
    }

    static Path appFolder() {
        String os = System.getProperty("os.name").toLowerCase();
        String user = System.getProperty("user.home");
        if (os.contains("mac")) return Path.of(user, "Library", "Application Support", TITLE);
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            return Path.of(appData != null ? appData : user, TITLE);
        }
        return Path.of(user, ".xperia-music-rebuilder");
    }

    // ------------------------------------------------------------ painting

    /** A rounded card; the drop card has a dashed outline. */
    static final class Card extends JPanel {
        private final boolean dashed;
        Card(boolean dashed) { this.dashed = dashed; setOpaque(false); }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            RoundRectangle2D r = new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1f, getHeight() - 1f, 14, 14);
            g2.setColor(Theme.card());
            g2.fill(r);
            g2.setColor(Theme.cardBorder());
            g2.setStroke(dashed ? new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{6f, 5f}, 0f) : new BasicStroke(1f));
            g2.draw(r);
            g2.dispose();
            super.paintComponent(g);
        }
        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
    }

    /** The main button, in the system accent colour. */
    static final class AccentButton extends JButton {
        private boolean hover;
        AccentButton(String text) {
            super(text);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setForeground(Color.WHITE);
            setFont(Theme.font(Font.BOLD, 13.5f));
            setBorder(new EmptyBorder(9, 22, 9, 22));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
            });
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color c = isEnabled() ? (hover ? Theme.accent().darker() : Theme.accent()) : Theme.disabledFill();
            g2.setColor(c);
            g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 10, 10));
            g2.dispose();
            setForeground(isEnabled() ? Color.WHITE : Theme.secondary());
            super.paintComponent(g);
        }
    }
}
