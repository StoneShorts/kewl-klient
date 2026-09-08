package kewl.plugin.hub;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import kewl.Plugin;
import kewl.json.Json;
import kewl.persist.JsonStore;
import kewl.plugin.PluginManager;

/**
 * The plugin hub: fetch a manifest, install a jar, keep the installed ones across restarts.
 *
 * <h2>Shape</h2>
 *
 * <p>{@link HubConfig} says where the manifest is (nowhere, by default -- an unconfigured hub is a
 * hub tab with a message on it, not an empty one pretending to have loaded). {@link HubEntry} is one
 * validated manifest row. {@link HubLoader} turns a verified jar into a plugin. This class is the
 * state machine around them and the only one of the four that touches the network.</p>
 *
 * <h2>State, and how failures show up</h2>
 *
 * <p>The launcher's model carries one hub state and one error string (the bridge v2 contract), so
 * every failure lands in the same place: {@code state} goes to {@link State#ERROR}, the message is
 * the error string, and the entries that were already on screen stay on screen. That is deliberate --
 * "the manifest is unreachable" and "that one jar failed its checksum" are both things the user
 * should read next to the rows, not a tab that goes blank. Nothing here throws across the boundary;
 * a hub that cannot reach its server is a state, never a crash.</p>
 *
 * <h2>Threading</h2>
 *
 * <p>Every network, hash and file operation runs on a single bounded worker ({@code kewl-hub}); the
 * frame thread only ever flips a volatile. Two things are still routed through {@link Plugin#later}
 * onto the frame thread: registering and unregistering a plugin. The registry is read by the bridge's
 * snapshot and walked by the tick loop, and mutating it from the worker would be a new kind of race
 * in a codebase that has been careful never to have one. Loading the class on the worker and
 * registering it a frame later is the price, and it is a small one.</p>
 */
public final class Hub {

    /** The states the launcher's model carries, in the bridge's numbering. */
    public enum State { IDLE, LOADING, ERROR, READY }

    /** Flags per hub entry, matching the bridge contract's {@code flags} field. */
    public static final int FLAG_INSTALLED = 1;
    public static final int FLAG_HAS_UPDATE = 2;
    public static final int FLAG_BUSY = 4;

    /** A download that claims to be bigger than this is a download we do not want. */
    private static final long MAX_ARTIFACT_BYTES = 64L * 1024 * 1024;

    /**
     * The most manifest entries the tab will show. Must match {@code MAX_HUB} in {@code
     * client/bridge.hpp} (restated in {@code launcher/bridge_layout.hpp}): the shared-memory model
     * carries a fixed hub-entry count, and the DLL rejects an over-cap snapshot WHOLE -- a remote
     * manifest with 65 rows would otherwise freeze every tab of the panel, not just the hub's. A
     * manifest that big is truncated here, with a line saying so, which is the honest version of
     * "the panel can only show so much".
     */
    public static final int MAX_ENTRIES = 64;

    private static volatile Hub instance;

    /** The installed hub, or null before {@link #install} (the bare test suite). */
    public static Hub instance() { return instance; }

    private final PluginManager manager;
    private final Path externalDir;      // <dataDir>/external -- one directory per plugin id
    private final Path tmpDir;           // <dataDir>/hub-tmp -- downloads land here, verified, then move
    private final Path installedFile;    // <dataDir>/hub/installed.json

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "kewl-hub");
        t.setDaemon(true);
        return t;
    });

    /** What the tab shows as in-flight, per entry id. Not a request gate -- see {@link #remove}. */
    private final Set<String> installing = ConcurrentHashMap.newKeySet();
    private final Set<String> removing = ConcurrentHashMap.newKeySet();

    private final Object lock = new Object();
    private final Map<String, Installed> installed = new LinkedHashMap<>();   // hub id -> record
    private volatile List<HubEntry> entries = List.of();
    private volatile State state = State.IDLE;
    private volatile String error = "";
    private volatile long generation;

    private Hub(PluginManager manager, Path dataDir) {
        this.manager = manager;
        this.externalDir = dataDir.resolve("external");
        this.tmpDir = dataDir.resolve("hub-tmp");
        this.installedFile = dataDir.resolve("hub").resolve("installed.json");
    }

    /**
     * Install the hub over a manager and a data directory, and reload whatever was installed before.
     * Reinstalling is asynchronous on the hub worker: start-up must not wait on classloading, and a
     * plugin that fails to come back is a log line, not a broken client.
     */
    public static Hub install(PluginManager manager, Path dataDir) {
        Hub h = new Hub(manager, dataDir);
        instance = h;
        h.restore();
        return h;
    }

    /** Test hook: drop the hub, stopping its worker first. */
    public static void uninstall() {
        Hub h = instance;
        if (h == null) return;
        instance = null;
        h.worker.shutdownNow();
    }

    // --------------------------------------------------------------------------- reading

    public State state() { return state; }
    public String error() { return error; }

    /** The manifest's entries, last fetch. Empty until a refresh has succeeded. */
    public List<HubEntry> entries() { return entries; }

    /** Bumped on every state change the launcher's model needs to hear about. */
    public long generation() { return generation; }

    /** Whether a hub entry's jar is installed and loaded right now. */
    public boolean isInstalled(String id) {
        synchronized (lock) { return installed.containsKey(id); }
    }

    /** The version the installed jar was built with, or null. Compared against the manifest's. */
    public String installedVersion(String id) {
        synchronized (lock) {
            Installed i = installed.get(id);
            return i == null ? null : i.version;
        }
    }

    /** The live plugin an entry installed, or null. The bridge maps this to a panel index. */
    public Plugin installedPlugin(String id) {
        synchronized (lock) {
            Installed i = installed.get(id);
            return i == null ? null : i.plugin;
        }
    }

    /** The bridge's per-entry flags: installed, update available, install/remove in flight. */
    public int flagsOf(HubEntry e) {
        int flags = 0;
        synchronized (lock) {
            Installed i = installed.get(e.id());
            if (i != null) {
                flags |= FLAG_INSTALLED;
                if (!e.version().equals(i.version)) flags |= FLAG_HAS_UPDATE;
            }
        }
        if (installing.contains(e.id()) || removing.contains(e.id())) flags |= FLAG_BUSY;
        return flags;
    }

    // --------------------------------------------------------------------------- commands

    /**
     * Fetch the manifest, on the worker. An unconfigured hub reports that as the error state rather
     * than silently doing nothing, because a user who clicked "refresh" deserves to know why nothing
     * happened.
     */
    public void refresh() {
        String url = HubConfig.manifestUrl();
        if (url == null) {
            state = State.ERROR;
            error = "no hub configured -- set hub= in kewlklient.ini";
            generation++;
            return;
        }
        state = State.LOADING;
        error = "";
        generation++;
        worker.execute(() -> {
            try {
                fetchManifest(url);
            } catch (Throwable t) {
                state = State.ERROR;
                error = "manifest fetch failed: " + t.getMessage();
                System.out.println("[hub] manifest fetch from " + url + " failed: " + t);
            } finally {
                generation++;
            }
        });
    }

    /** Install (or update) the entry with this id, on the worker. Failures land in the error state. */
    public void install(String id) {
        HubEntry e = entry(id);
        if (e == null) { fail("no manifest entry named \"" + id + "\""); return; }
        if (!installing.add(id)) return;                 // already installing: the click is heard once
        generation++;
        worker.execute(() -> {
            try {
                doInstall(e);
            } catch (Throwable t) {
                fail("install of " + e.id() + " failed: " + t.getMessage());
                System.out.println("[hub] install of " + e.id() + " failed: " + t);
            } finally {
                installing.remove(e.id());
                generation++;
            }
        });
    }

    /**
     * Remove an installed plugin, on the worker: disable, unload, delete, forget. There is no
     * "already removing" gate here, on purpose: {@code installing} guards the download (a double
     * click must not fetch a jar twice), but a remove that races the tail of an install -- the tab
     * already says installed, the install task has not cleared its flag yet -- must still be
     * honoured, not dropped. The worker is single-threaded, so the second of two rapid removes finds
     * nothing left and does nothing; the busy flag below is for the tab's in-flight marker only.
     */
    public void remove(String id) {
        synchronized (lock) {
            if (!installed.containsKey(id)) { fail("cannot remove \"" + id + "\": not installed"); return; }
        }
        removing.add(id);
        generation++;
        worker.execute(() -> {
            try {
                Installed i;
                synchronized (lock) { i = installed.get(id); }
                if (i != null) doRemove(i);          // a second click landed after the first finished
            } catch (Throwable t) {
                fail("removing " + id + " failed: " + t.getMessage());
                System.out.println("[hub] removing " + id + " failed: " + t);
            } finally {
                removing.remove(id);
                generation++;
            }
        });
    }

    // --------------------------------------------------------------------------- worker bodies

    private void fetchManifest(String url) throws Exception {
        String text;
        try (InputStream in = open(url)) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Object json = Json.parse(text);

        List<Object> raw = new ArrayList<>();
        if (json instanceof List<?> l) raw.addAll(l);
        else if (json instanceof Map<?, ?> m && m.get("plugins") instanceof List<?> l) raw.addAll(l);
        else throw new IllegalArgumentException("the manifest is neither a list nor {\"plugins\": [...]}");

        List<HubEntry> parsed = new ArrayList<>();
        for (Object o : raw) {
            if (parsed.size() >= MAX_ENTRIES) {          // see MAX_ENTRIES: the bridge's cap
                System.out.println("[hub] manifest lists more than " + MAX_ENTRIES
                        + " valid entries; the panel shows the first " + MAX_ENTRIES);
                break;
            }
            HubEntry e = HubEntry.fromManifest(o);
            if (e != null) parsed.add(e);
        }
        if (parsed.isEmpty()) throw new IllegalArgumentException("the manifest has no valid entries");

        entries = List.copyOf(parsed);
        state = State.READY;
        error = "";
        System.out.println("[hub] manifest: " + parsed.size() + " plugin(s) from " + url);
    }

    private void doInstall(HubEntry e) throws Exception {
        Path jar = externalDir.resolve(e.id()).resolve(e.version() + ".jar");

        // Already there, same version: the jar on disk was verified when it arrived, so reinstalling
        // it is a reload (the "restore at start-up failed" path) rather than a fresh download.
        if (!Files.exists(jar)) {
            download(e, jar);
        }

        HubLoader.Loaded loaded = loadInto(jar, e);
        if (loaded == null) {
            fail("install of " + e.id() + " failed: the jar is not a kewl plugin");
            return;
        }

        // The registry is frame-thread state (the bridge's snapshot and the tick loop both walk it),
        // so registration joins that thread even though the class was loaded over here. The install
        // record itself moves now, on this thread: "installed" is what the launcher's hub tab shows,
        // and it should not wait a frame for a queue it cannot see.
        Installed next = new Installed(e.id(), e.version(), e.mainClass(), e.sha256(), jar);
        next.plugin = loaded.plugin();
        next.loader = loaded.loader();
        Installed previous;
        synchronized (lock) {
            previous = installed.put(e.id(), next);
        }
        Installed old = previous;
        Plugin outgoing = previous == null ? null : previous.plugin;
        Plugin incoming = loaded.plugin();
        // Same order as doRemove, and for the same reasons: unregister on the frame thread, close the
        // outgoing loader only once its plugin is out of the registry, unlink back on the worker
        // because Windows will not delete a file a handle still holds. Review 2026-09-06: the update
        // path did none of this -- every update leaked a PluginClassLoader (and every class it had
        // loaded) for the life of the process and left the superseded jar open on disk, so a later
        // "remove" could not delete the directory ("[hub] could not delete ...1.0.0.jar") and the
        // next start-up still found it.
        //
        // The old jar is unlinked only when it is a DIFFERENT file from the incoming one: reinstalling
        // the same version is a reload, and both records name the same path -- deleting it there would
        // pull the jar out from under the loader that was just built on it.
        boolean supersededJar = old != null && !old.jar.equals(jar);
        Plugin.later(() -> {
            if (outgoing != null) manager.unregister(outgoing);
            if (old != null) old.closeLoader();
            manager.register(incoming);
            if (supersededJar) worker.execute(() -> deleteIfExists(old.jar));
        });
        persistInstalled();
        state = State.READY;
        error = "";
        System.out.println("[hub] installed " + e.id() + " " + e.version());
    }

    private void doRemove(Installed i) throws Exception {
        // Unregistering joins the frame thread (the registry is frame-thread state) and the loader is
        // closed after the plugin is out of it -- closing a classloader under a live plugin would pull
        // the ground out from under its next tick. The jar is unlinked only AFTER that close, and
        // back on this worker: Windows refuses to delete a file another handle holds open, so
        // deleting here, while the frame thread still owned the loader, left every removed plugin's
        // jar on disk (HubEndToEndTest failed exactly there on Windows, 2026-09-05; Linux never
        // noticed because unlinking an open file is fine there).
        //
        // Queued BEFORE the record leaves the map: "not installed any more" is the signal callers
        // (and the end-to-end test) wait on before draining the frame queue, so the unload must
        // already be in that queue when the signal fires or it is drained past and never runs.
        Path dir = externalDir.resolve(i.id);
        Plugin.later(() -> {
            if (i.plugin != null) manager.unregister(i.plugin);
            i.closeLoader();
            worker.execute(() -> deleteTree(dir));
        });
        // The record leaves now, on this thread, for the same reason doInstall's arrives now: the
        // launcher's tab should not wait a frame to stop advertising something the user deleted.
        synchronized (lock) { installed.remove(i.id); }
        persistInstalled();
        System.out.println("[hub] removed " + i.id);
    }

    /** Download the artifact, verify its hash, and move it into place. Any failure throws. */
    private void download(HubEntry e, Path destination) throws Exception {
        Files.createDirectories(tmpDir);
        Path tmp = tmpDir.resolve("download-" + e.id() + ".jar");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0;

        try (InputStream in = open(e.artifactUrl())) {
            try (var out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_ARTIFACT_BYTES) {
                        throw new IllegalArgumentException("artifact larger than " + MAX_ARTIFACT_BYTES + " bytes");
                    }
                    out.write(buf, 0, n);
                    digest.update(buf, 0, n);
                }
            }
        } catch (IOException downloadFailed) {
            Files.deleteIfExists(tmp);
            throw downloadFailed;
        }

        byte[] hash = digest.digest();
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) sb.append(String.format("%02x", b));
        if (!sb.toString().equals(e.sha256())) {
            Files.deleteIfExists(tmp);
            throw new IllegalArgumentException("SHA-256 mismatch (expected " + e.sha256()
                    + ", got " + sb + ") -- the artifact did not come from where the manifest says");
        }

        // The plugin's directory appears only once the bytes are verified: a failed checksum must not
        // leave an empty directory behind to suggest something was installed.
        Files.createDirectories(destination.getParent());
        try {
            Files.move(tmp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException noAtomic) {
            Files.move(tmp, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Load a plugin from an already-verified jar. The caller wires the loader into the record. */
    private HubLoader.Loaded loadInto(Path jar, HubEntry e) {
        return HubLoader.load(jar, e.mainClass());
    }

    /** Put the installed records back: load each jar that survived, forget the ones that did not. */
    private void restore() {
        for (Map<String, Object> r : readInstalled()) {
            String id = text(r.get("id"));
            String version = text(r.get("version"));
            String mainClass = text(r.get("mainClass"));
            String sha256 = text(r.get("sha256"));
            String jar = text(r.get("jar"));
            if (id == null || version == null || mainClass == null || jar == null) continue;

            Path path = Path.of(jar);
            if (!Files.exists(path)) {
                System.out.println("[hub] " + id + " is in the installed list but its jar is gone; dropping it");
                continue;
            }
            worker.execute(() -> {
                try {
                    HubLoader.Loaded loaded = HubLoader.load(path, mainClass);
                    if (loaded == null) {
                        // The jar stays on disk and the record stays in installed.json -- a plugin
                        // that will not load after an update must not cost the user its saved state.
                        System.out.println("[hub] " + id + " failed to load from " + jar
                                + " -- it stays installed on disk, disabled");
                        return;
                    }
                    Installed i = new Installed(id, version, mainClass, sha256 == null ? "" : sha256, path);
                    i.plugin = loaded.plugin();
                    i.loader = loaded.loader();
                    synchronized (lock) { installed.put(id, i); }
                    Plugin.later(() -> manager.register(loaded.plugin()));
                    generation++;
                    System.out.println("[hub] restored " + id + " " + version);
                } catch (Throwable t) {
                    System.out.println("[hub] restoring " + id + " failed: " + t);
                }
            });
        }
    }

    // --------------------------------------------------------------------------- installed.json

    private void persistInstalled() {
        List<Object> list = new ArrayList<>();
        synchronized (lock) {
            for (Installed i : installed.values()) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("id", i.id);
                e.put("version", i.version);
                e.put("mainClass", i.mainClass);
                e.put("jar", i.jar.toString());
                e.put("sha256", i.sha256);
                list.add(e);
            }
        }
        JsonStore.write(installedFile, list);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readInstalled() {
        Object json = null;
        String text = JsonStore.readText(installedFile);
        if (text != null) {
            try { json = Json.parse(text); } catch (RuntimeException corrupt) { quarantine(); }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        if (json instanceof List<?> l) {
            for (Object o : l) if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        }
        return out;
    }

    private void quarantine() {
        System.out.println("[hub] " + installedFile + " is corrupt; starting with nothing installed");
    }

    // --------------------------------------------------------------------------- plumbing

    private static final class Installed {
        final String id, version, mainClass, sha256;
        final Path jar;
        Plugin plugin;
        HubLoader.PluginClassLoader loader;

        Installed(String id, String version, String mainClass, String sha256, Path jar) {
            this.id = id;
            this.version = version;
            this.mainClass = mainClass;
            this.sha256 = sha256;
            this.jar = jar;
        }

        void closeLoader() {
            if (loader == null) return;
            try { loader.close(); } catch (IOException e) { /* closing is best-effort */ }
        }
    }

    private HubEntry entry(String id) {
        for (HubEntry e : entries) if (e.id().equals(id)) return e;
        return null;
    }

    private void fail(String message) {
        state = State.ERROR;
        error = message;
        generation++;
    }

    private static InputStream open(String url) throws Exception {
        return java.net.URI.create(url).toURL().openStream();
    }

    private static String text(Object o) {
        return o instanceof String s && !s.isBlank() ? s : null;
    }

    /** Unlink one superseded jar, saying why if it will not go (see deleteTree's note on Windows). */
    private static void deleteIfExists(Path file) {
        try { Files.deleteIfExists(file); }
        catch (IOException e) { System.out.println("[hub] could not delete " + file + ": " + e); }
    }

    private static void deleteTree(Path dir) {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); }
                catch (IOException e) {
                    // Say which file and why: on Windows this is how "somebody still has the jar
                    // open" shows up, and a swallowed exception here hid exactly that for a day.
                    System.out.println("[hub] could not delete " + p + ": " + e);
                }
            });
        } catch (IOException e) {
            System.out.println("[hub] could not delete " + dir + ": " + e);
        }
    }
}
