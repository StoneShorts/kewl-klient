package kewl.plugin.hub;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import kewl.persist.JsonStore;

/**
 * Where the plugin hub's manifest comes from.
 *
 * <p>The hub is configurable and configured-to-nothing by default: an empty answer means the hub tab
 * says "no hub configured" rather than guessing at somebody else's server. {@code hub=} lives in
 * kewlklient.ini because that is where the rest of this client's knobs live -- the launcher reads it
 * with {@code GetPrivateProfileStringW} (launcher/main.cpp {@code iniString}) and the DLL reads the
 * same file next to itself (client/dllmain.cpp). Java has no handle on that file's Windows path, so
 * this class resolves the key by looking in the places the jar already knows it sits, in order:</p>
 *
 * <ol>
 *   <li>the {@code kewl.hub.url} system property (a test, or a hand-run jar)</li>
 *   <li>the {@code KEWL_HUB} environment variable</li>
 *   <li>{@code hub=} in kewlklient.ini next to this jar, then in the working directory</li>
 *   <li>{@code "hub"} in {@code <dataDir>/client.json}, for a knob set without touching the install</li>
 * </ol>
 *
 * <p>Whatever it finds is returned verbatim; validation of the URL is the hub client's job, not the
 * configuration's. Nothing here ever touches the network.</p>
 */
public final class HubConfig {

    /** The ini sections the launcher and the DLL read; either one drives both, so both are accepted. */
    private static final String[] INI_SECTIONS = {"kewl", "kewlklient"};

    private HubConfig() {}

    /** The configured manifest URL, or null for "no hub configured". Never throws. */
    public static String manifestUrl() {
        try {
            String prop = System.getProperty("kewl.hub.url");
            if (prop != null && !prop.isBlank()) return prop.trim();

            String env = System.getenv("KEWL_HUB");
            if (env != null && !env.isBlank()) return env.trim();

            for (Path dir : iniDirectories()) {
                Path ini = dir.resolve("kewlklient.ini");
                String text = readIfExists(ini);
                if (text == null) continue;
                String v = iniValue(text, "hub");
                if (v != null) return v;
            }

            Path dataDir = kewl.KewlKlient.dataDir();
            String json = JsonStore.readText(dataDir.resolve("client.json"));
            if (json != null) {
                Object hub = kewl.json.Json.parse(json);
                if (hub instanceof java.util.Map<?, ?> m) {
                    Object v = m.get("hub");
                    if (v instanceof String s && !s.isBlank()) return s.trim();
                }
            }
        } catch (Throwable t) {
            // Configuration is read from the frame thread's start-up path; a bad file or a bad
            // property must cost the hub tab, not the client.
            System.out.println("[hub] reading the manifest configuration threw: " + t);
        }
        return null;
    }

    /**
     * The directories a kewlklient.ini could plausibly be in: next to this jar (the shipping layout
     * puts kewlklient.jar beside kewlklient.ini and the DLL), then the working directory (which is
     * where the launcher starts the game).
     */
    private static Path[] iniDirectories() {
        try {
            Path jarDir = Path.of(HubConfig.class.getProtectionDomain().getCodeSource().getLocation()
                    .toURI()).getParent();
            Path cwd = Path.of(System.getProperty("user.dir"));
            return jarDir != null && !jarDir.equals(cwd)
                    ? new Path[] {jarDir, cwd}
                    : new Path[] {cwd};
        } catch (Throwable t) {
            return new Path[] {Path.of(System.getProperty("user.dir"))};
        }
    }

    private static String readIfExists(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The value of {@code key} in a Windows-style ini, from the {@code [kewl]} or {@code [kewlklient]}
     * section. A hand-rolled scan rather than the Win32 call because this runs in the game process,
     * where there is no such thing as a Java binding for {@code GetPrivateProfileStringW}.
     */
    static String iniValue(String text, String key) {
        boolean inSection = false;
        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
            if (line.startsWith("[")) {
                String section = line.substring(1, line.endsWith("]") ? line.length() - 1 : line.length())
                        .trim();
                inSection = false;
                for (String s : INI_SECTIONS) if (s.equalsIgnoreCase(section)) inSection = true;
                continue;
            }
            if (!inSection) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            if (line.substring(0, eq).trim().equalsIgnoreCase(key)) {
                String v = line.substring(eq + 1).trim();
                return v.isEmpty() ? null : v;
            }
        }
        return null;
    }
}
