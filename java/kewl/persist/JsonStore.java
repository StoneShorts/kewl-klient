package kewl.persist;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

import kewl.json.Json;

/**
 * The disk half of the profile store: read a JSON file, write one, and never lose the old copy while
 * doing it.
 *
 * <p>Everything here exists because the process this runs in is killed without warning -- the game
 * closes, Wine is shut down, the machine loses power. So writes go to a temp file in the same
 * directory and are renamed over the target ({@code ATOMIC_MOVE}), which on every filesystem this
 * ships on either replaces the whole file or does not happen. A half-written config.json is not a
 * state this code can produce.</p>
 *
 * <p>Corruption that does reach the disk (an older bug, a bad sector, a user with an editor) is
 * handled by the caller-friendly shape of {@link #read}: it returns {@code null} for a missing file
 * <i>and</i> for an unparseable one, moving the bad copy to {@code <name>.bad} first so the evidence
 * survives and stdout says what happened. The caller then falls back -- a fresh default profile, an
 * empty installed list -- rather than refusing to start over a file nobody can read anyway.</p>
 */
public final class JsonStore {

    private JsonStore() {}

    /**
     * The parsed contents of {@code file}, or {@code null} when it does not exist or cannot be read.
     * A file that parses but is not the shape the caller wanted is the caller's problem -- this class
     * knows JSON, not schemas.
     */
    public static Map<String, Object> read(Path file) {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException missingOrUnreadable) {
            return null;
        }
        try {
            Object v = Json.parse(text);
            return v instanceof Map<?, ?> m ? asMap(m) : null;
        } catch (RuntimeException broken) {
            quarantine(file, broken);
            return null;
        }
    }

    /** The raw text of {@code file}, or null when it does not exist or cannot be read. */
    public static String readText(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Write {@code json} to {@code file}, atomically, creating parent directories on the way. Returns
     * false when the write could not land -- the caller logs it and moves on; a profile that fails to
     * save is a state to surface, never a reason to stop the client.
     */
    public static boolean write(Path file, Object json) {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.writeString(tmp, Json.write(json), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException noAtomic) {
                // Rare (some network/9p filesystems under Wine). The non-atomic move still happens
                // after a complete write, so the window where the target is partial is one rename.
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            System.out.println("[persist] could not write " + file + ": " + e);
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            return false;
        }
    }

    /** Move the unreadable file out of the way (to {@code <name>.bad}) and say so on stdout. */
    private static void quarantine(Path file, RuntimeException why) {
        Path bad = file.resolveSibling(file.getFileName() + ".bad");
        try {
            Files.move(file, bad, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.out.println("[persist] corrupt " + file + " could not be moved aside either: " + e);
        }
        System.out.println("[persist] " + file + " was corrupt (" + why.getMessage()
                + "); moved to " + bad.getFileName() + " and starting fresh");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Map<?, ?> m) { return (Map<String, Object>) m; }
}
