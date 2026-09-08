package kewl;

import java.security.Provider;
import java.security.SecureRandom;
import java.security.SecureRandomSpi;

/**
 * The JDK's entropy machinery does not survive Wine, and this provider exists because of it.
 *
 * <p>Under Wine, {@code GetAdaptersAddresses} fails with error 13 where Windows returns
 * {@code ERROR_INSUFFICIENT_BUFFER}, so {@code NetworkInterface.getAll()} throws a hard
 * {@code java.lang.Error}. The JDK's default SecureRandom reaches that code during entropy
 * collection -- and {@link java.nio.file.Files#createTempFile} uses SecureRandom for file names,
 * so the first plugin that loads an image resource through ImageIO died in its static
 * initializer with a stack trace ending in the IP Helper Library.</p>
 *
 * <p>Inserting this provider at position 1 means {@code new SecureRandom()} resolves to it and
 * the DRBG -- and its Wine-fatal entropy walk -- is never instantiated. That is the honest scope
 * of the claim: nothing here pretends to be cryptographically secure. The only consumers in
 * play are temp-file names and the JDK's internal odds and ends, for which
 * {@link java.util.Random} is entirely sufficient.</p>
 *
 * <p>Inserted by {@link KewlKlient}'s static initializer, which runs before the plugin list is
 * built -- ordering that matters, because the first ImageIO call happens inside a plugin's own
 * static initializer.</p>
 */
public final class WineRandomProvider extends Provider {

    private static final long serialVersionUID = 1L;

    public WineRandomProvider() {
        super("KewlSeeds", "1.0",
                "Non-blocking SecureRandom for KewlKlient under Wine (see class documentation)");
        put("SecureRandom.KewlRandom", KewlRandom.class.getName());
    }

    /** Registered as "KewlRandom"; delegates to a plain Random, seeded at construction. */
    public static final class KewlRandom extends SecureRandomSpi {

        private static final long serialVersionUID = 1L;

        private final java.util.Random rng = new java.util.Random();

        @Override
        protected void engineSetSeed(byte[] seed) {
            // Fold whatever we were given into our state; a bot client has no secrets to protect.
            long s = 0;
            for (int i = 0; i < seed.length; i++) {
                s = (s << 8) | (seed[i] & 0xffL);
            }
            rng.setSeed(s ^ System.nanoTime());
        }

        @Override
        protected void engineNextBytes(byte[] bytes) {
            rng.nextBytes(bytes);
        }

        @Override
        protected byte[] engineGenerateSeed(int numBytes) {
            byte[] b = new byte[numBytes];
            engineNextBytes(b);
            return b;
        }
    }
}
