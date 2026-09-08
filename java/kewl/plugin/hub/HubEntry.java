package kewl.plugin.hub;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One row of the hub manifest: what a plugin is, where its jar is, and how to check it.
 *
 * <p>This is also the validator. {@link #fromManifest} returns null for an entry it does not believe,
 * and the hub drops those with a log line rather than failing the whole manifest -- one malformed row
 * in somebody else's file should not empty the tab. The rules are the ones the loader actually
 * depends on:</p>
 *
 * <ul>
 *   <li>{@code id} -- 1..64 chars of {@code [A-Za-z0-9._-]}, no leading dot. It becomes a directory
 *       name under the external-plugins dir, which is why path-shaped ids are rejected outright.</li>
 *   <li>{@code name}, {@code version}, {@code mainClass} -- non-empty. mainClass is checked again
 *       after loading, when it has to actually extend {@code kewl.Plugin}.</li>
 *   <li>{@code artifact} -- an https URL (a {@code file:} URL is accepted too, which is how a local
 *       hub is developed and how the tests build one; anything else is rejected, because a manifest
 *       that can hand out {@code ftp://} or a plain {@code jar:} is a manifest that can hand out
 *       something the classloader will choke on).</li>
 *   <li>{@code sha256} -- 64 hex characters, required. A hub without checksums is a hub that asks the
 *       user to trust every mirror in between, and the download path refuses to install an artifact
 *       it cannot check.</li>
 * </ul>
 *
 * <p>{@code description} and {@code author} are display-only and default to empty. Anything the
 * manifest adds beyond these fields is ignored -- a hub may grow, and an old client skipping what it
 * does not understand is better than one that rejects the file.</p>
 */
public final class HubEntry {

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern HEX64 = Pattern.compile("[0-9a-fA-F]{64}");

    private final String id, name, version, author, description, mainClass, artifact, sha256;

    HubEntry(String id, String name, String version, String author, String description,
             String mainClass, String artifact, String sha256) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.author = author;
        this.description = description;
        this.mainClass = mainClass;
        this.artifact = artifact;
        this.sha256 = sha256.toLowerCase(Locale.ROOT);
    }

    public String id()          { return id; }
    public String name()        { return name; }
    public String version()     { return version; }
    public String author()      { return author; }
    public String description() { return description; }
    public String mainClass()   { return mainClass; }
    public String artifactUrl() { return artifact; }
    /** The SHA-256 the artifact must hash to, lower-case hex. */
    public String sha256()      { return sha256; }

    /**
     * One manifest object, validated. Null when the entry is not something this client will install;
     * the reason is on stdout, because a hub author is the person who needs it.
     */
    static HubEntry fromManifest(Object o) {
        if (!(o instanceof Map<?, ?> m)) return null;
        String id = text(m.get("id"));
        String name = text(m.get("name"));
        String version = text(m.get("version"));
        String mainClass = text(m.get("mainClass"));
        String artifact = text(m.get("artifact"));
        String sha256 = text(m.get("sha256"));
        String author = text(m.get("author"));
        String description = text(m.get("description"));

        if (id == null || !ID.matcher(id).matches()) { reject(id, "id"); return null; }
        if (name == null)  { reject(id, "name"); return null; }
        if (version == null) { reject(id, "version"); return null; }
        if (mainClass == null) { reject(id, "mainClass"); return null; }
        if (artifact == null || !schemeOk(artifact)) { reject(id, "artifact"); return null; }
        if (sha256 == null || !HEX64.matcher(sha256).matches()) { reject(id, "sha256"); return null; }

        return new HubEntry(id, name, version, author == null ? "" : author,
                description == null ? "" : description, mainClass, artifact, sha256);
    }

    private static void reject(String id, String field) {
        System.out.println("[hub] manifest entry rejected: " + (id == null ? "(no id)" : id)
                + " -- bad or missing " + field);
    }

    private static boolean schemeOk(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("https://") || lower.startsWith("file:");
    }

    private static String text(Object o) {
        return o instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    @Override public String toString() { return name + " " + version; }
}
