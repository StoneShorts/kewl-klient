package kewl.profile;

/**
 * One profile: a stable id and a display name, and nothing else on purpose.
 *
 * <p>The id is what every file name, edit record and UI row keys on; the name is what the user reads
 * and can change as often as they like. Keeping them separate means "rename" is a one-field write
 * that cannot orphan a profile's directory, and means two profiles may both be called "PvM" without
 * becoming the same profile. The id is generated (see {@code ProfileManager#nextId}), never derived
 * from the name, for exactly that reason.</p>
 */
public final class Profile {

    private final String id;
    private String name;

    Profile(String id, String name) {
        this.id = id;
        this.name = name;
    }

    /** The stable identifier. File name on disk, key in the launcher's profile list. */
    public String id() { return id; }

    /** What the user sees. Renameable; never used as a key anywhere. */
    public String name() { return name; }

    void rename(String newName) { this.name = newName; }

    @Override public String toString() { return name + " (" + id + ")"; }
}
