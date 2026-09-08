package kewl.plugin.hub;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

import kewl.Plugin;

/**
 * Turns a verified jar into a live {@link Plugin}, in a classloader of its own.
 *
 * <h2>The classloader, and what "child-first" means here</h2>
 *
 * <p>Each plugin gets its own {@link URLClassLoader} whose parent is this client's loader, and whose
 * {@code loadClass} looks in the plugin's jar <b>first</b> -- so a plugin that bundles its own copy of
 * a library ships that copy instead of inheriting whatever version the client happens to have, and
 * two plugins can disagree about a library without either winning.</p>
 *
 * <p>Child-first stops at a short list of prefixes, and that exception is the whole point: {@code
 * kewl.*} and the shim ({@code net.runelite.*}, {@code org.slf4j.*}) must come from the parent,
 * because those are <i>identity</i>, not bytecode. The client hands a plugin live objects -- a
 * {@code Client}, an {@code OverlayManager}, the {@code Plugin} base class itself. If the jar's own
 * copy of {@code net.runelite.api.Client} were loaded, that object would be an instance of a
 * different class with the same name, and the first cast would throw {@link ClassCastException} with
 * an error message nobody could act on. Java has no way to say "same name, same class"; the only
 * defence is to never load those names twice.</p>
 *
 * <h2>What this is not</h2>
 *
 * <p><b>This is not a security sandbox, and nothing in this file should be described as one.</b> A
 * {@link Plugin} runs with every permission this process has: it can read and write files, open
 * sockets, load natives and call the game's memory. The classloader isolates <i>namespaces</i> so
 * that independent plugins cannot break each other and can be unloaded by closing the loader -- it
 * does not constrain what a plugin may do. Installing one from a hub is running someone else's code
 * inside your client, and the hub UI says so in those words.</p>
 */
public final class HubLoader {

    /** Names that must resolve from the client, never from a plugin's jar. See the class comment. */
    private static final String[] PARENT_FIRST = {
        "java.", "javax.", "jdk.", "sun.", "com.sun.",      // the JVM's own
        "kewl.",                                            // the plugin API: identity is the contract
        "net.runelite.", "org.slf4j.",                      // the shim the plugin is compiled against
    };

    private HubLoader() {}

    /**
     * Load {@code mainClass} out of {@code jar} and instantiate it.
     *
     * @return the plugin and the loader that holds it, or null with the reason on stdout -- a jar
     *         whose main class is not a {@code kewl.Plugin} is refused here, before it can be
     *         registered anywhere
     */
    static Loaded load(Path jar, String mainClass) {
        PluginClassLoader loader;
        try {
            loader = new PluginClassLoader(jar);
        } catch (IOException e) {
            System.out.println("[hub] cannot open " + jar + ": " + e);
            return null;
        }
        try {
            Class<?> c = loader.loadClass(mainClass, true);
            if (!Plugin.class.isAssignableFrom(c)) {
                System.out.println("[hub] " + mainClass + " does not extend kewl.Plugin -- refusing");
                return null;
            }
            if (c.getClassLoader() != loader) {
                // A mainClass like "kewl.plugins.PlayerVisuals" would resolve from the parent and pass
                // the check above; that would be a built-in wearing a hub entry, not an external plugin.
                System.out.println("[hub] " + mainClass + " resolved from the client, not from "
                        + jar.getFileName());
                return null;
            }
            Object instance = c.getDeclaredConstructor().newInstance();
            if (!(instance instanceof Plugin p)) return null;
            return new Loaded(p, loader);
        } catch (Throwable t) {
            System.out.println("[hub] loading " + mainClass + " from " + jar + " failed: " + t);
            try { loader.close(); } catch (IOException ignored) {}
            return null;
        }
    }

    /** A plugin plus the loader it lives in. The loader is kept so removal can unload the plugin. */
    record Loaded(Plugin plugin, PluginClassLoader loader) {}

    static final class PluginClassLoader extends URLClassLoader {
        PluginClassLoader(Path jar) throws IOException {
            super("hub:" + jar.getFileName(), new URL[] {jar.toUri().toURL()},
                    HubLoader.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    if (fromParent(name)) {
                        c = super.loadClass(name, false);
                    } else {
                        try {
                            c = findClass(name);                    // the plugin's jar first
                        } catch (ClassNotFoundException notOurs) {
                            c = super.loadClass(name, false);       // then the client's classpath
                        }
                    }
                }
                if (resolve) resolveClass(c);
                return c;
            }
        }

        private static boolean fromParent(String name) {
            for (String prefix : PARENT_FIRST) {
                if (name.startsWith(prefix)) return true;
            }
            return false;
        }
    }
}
