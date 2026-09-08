package kewl.plugins.autologin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The login credentials for one attempt: the two settings on the AutoLogin panel ({@code username},
 * {@code password}) when BOTH are non-empty, otherwise the two lines of
 * {@code ~/.kewlklient/autologin.properties}: {@code username=} and {@code password=}. Which one
 * won is {@link #source()}, and that word -- "panel" or "file" -- is all any log or status line
 * ever says about it, next to the set/empty flags.
 *
 * <p>The file is LOCAL. It lives in the client's data directory ({@code KewlKlient.dataDir()}),
 * outside the repository, and nothing in this class -- not {@link #describe()}, not
 * {@link #toString()}, not the failure log line -- ever includes a value. The only things that leave
 * this class as text are presence flags: file present or missing, username set or empty, password
 * set or empty. The plugin reads the fields package-privately, hands them to the login sequence, and
 * drops the reference; the sequence holds them only as a queue of characters it clears as it types.</p>
 *
 * <p><b>Why not {@code java.util.Properties}:</b> its loader treats backslash as an escape, so a
 * password containing one would silently lose it. Each line is read raw instead: everything after
 * the first {@code =} is the value, verbatim, with only the surrounding whitespace trimmed. Lines
 * starting with {@code #} or {@code !} are comments, as in a properties file.</p>
 */
public final class Credentials {

    /** The file's name under the data directory. Nothing under that directory is in the repo. */
    public static final String FILE = "autologin.properties";

    /** Where the values came from. Safe to print: it names a place, never a value. */
    public enum Source { PANEL, FILE }

    final String username;
    final String password;
    final boolean filePresent;
    final Source source;

    private Credentials(Source source, boolean filePresent, String username, String password) {
        this.source = source;
        this.filePresent = filePresent;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
    }

    /**
     * The per-attempt resolution: the panel's two settings when both are non-empty (surrounding
     * whitespace trimmed, the way the file's values are), otherwise {@link #load} on the file. Both
     * rather than either, deliberately: a username typed into the panel with the password still in
     * the file would pair one source's username with the other's password, and a half-filled panel
     * should not silently change which account gets typed. The plugin calls this once per attempt,
     * so filling the panel in while the client sits at the title screen is enough.
     */
    public static Credentials resolve(String panelUsername, String panelPassword, Path dir) {
        String user = panelUsername == null ? "" : panelUsername.strip();
        String pass = panelPassword == null ? "" : panelPassword.strip();
        if (!user.isEmpty() && !pass.isEmpty()) return new Credentials(Source.PANEL, false, user, pass);
        return load(dir);
    }

    /**
     * Read {@code dir/autologin.properties}. A missing file is not an error -- it is the "user has
     * not set this up" state and reports as such. An unreadable one is logged by exception class
     * only and reports the same way, so the plugin never types anything on a half-read file.
     */
    public static Credentials load(Path dir) {
        Path file = dir.resolve(FILE);
        if (!Files.isRegularFile(file)) return new Credentials(Source.FILE, false, "", "");
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("[autologin] cannot read " + FILE + ": " + e.getClass().getSimpleName());
            return new Credentials(Source.FILE, false, "", "");
        }
        String user = "", pass = "";
        boolean first = true;
        for (String raw : lines) {
            String line = raw;
            if (first && !line.isEmpty() && line.charAt(0) == '\uFEFF') line = line.substring(1); // BOM
            first = false;
            line = line.strip();
            if (line.isEmpty() || line.charAt(0) == '#' || line.charAt(0) == '!') continue;
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String key = line.substring(0, eq).strip();
            String value = line.substring(eq + 1).strip();
            if (key.equals("username")) user = value;
            else if (key.equals("password")) pass = value;
        }
        return new Credentials(Source.FILE, true, user, pass);
    }

    /**
     * Hand the values to a sequence's script. The only way the strings leave this class, and they
     * leave it into a character queue that is drained as it types -- there is no getter, so no
     * caller outside this package can put them in a string, a status line or a log.
     */
    public void armInto(LoginSequence sequence, int canvasW, int canvasH) {
        armInto(sequence, canvasW, canvasH, false);
    }

    /**
     * As {@link #armInto(LoginSequence, int, int)}, with {@code fieldsAlreadySet} saying the values are
     * already in the client's own buffers ({@link FieldWriter}); the sequence then types nothing and
     * only clicks its way to the submit.
     */
    public void armInto(LoginSequence sequence, int canvasW, int canvasH, boolean fieldsAlreadySet) {
        sequence.arm(username, password, canvasW, canvasH, fieldsAlreadySet);
    }

    /**
     * Try to put both values straight into the client's fields. Here rather than in the plugin for the
     * same reason {@link #armInto} is: the two strings never leave this package. The result carries a
     * verdict and a status line built from counts and reasons -- never a value.
     */
    public FieldWriter.Result setDirectly(FieldWriter.Memory memory) {
        return FieldWriter.write(memory, this);
    }

    /** Panel or file -- see {@link #resolve}. */
    public Source source() { return source; }

    /** True when the values are the panel's settings rather than the file's lines. */
    public boolean fromPanel() { return source == Source.PANEL; }

    /** True when the file exists and was readable. Always false for panel credentials: the file was not consulted. */
    public boolean filePresent() { return filePresent; }

    public boolean usernameSet() { return !username.isEmpty(); }

    public boolean passwordSet() { return !password.isEmpty(); }

    /**
     * Source and presence flags only -- "credentials: panel, ..." or "credentials: file, ..." --
     * safe for the log and the status line. Never a value, never a length.
     */
    public String describe() {
        if (source == Source.PANEL) return "credentials: panel, username set, password set";
        if (!filePresent) return "credentials: file, missing (" + FILE + " in the data directory)";
        return "credentials: file, username " + (usernameSet() ? "set" : "empty")
                + ", password " + (passwordSet() ? "set" : "empty");
    }

    /** Same as {@link #describe()}: no accidental value leak through string concatenation. */
    @Override
    public String toString() { return describe(); }
}
