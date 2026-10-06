package oxclient.api;

import java.util.List;

/**
 * Finding other players.
 *
 * <p>Deliberately thin. We can see where other players are, what they are animating and their combat
 * level; we cannot see their names, because names live in the game's cache and this client does not read
 * the cache. If you want name tags, that is a real contribution and the README lists it.</p>
 */
public final class Players {

    private Players() {}

    /** Every visible player except you. */
    public static List<Entity> all() { return Game.players(); }

    /** The closest other player, or null when you are alone. */
    public static Entity nearest() {
        Entity best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Entity e : all()) {
            int d = e.distance();
            if (d < bestDist) { bestDist = d; best = e; }
        }
        return best;
    }

    /** How many other players are within {@code radius} tiles of you. */
    public static int countWithin(int radius) {
        int n = 0;
        for (Entity e : all()) if (e.distance() <= radius) n++;
        return n;
    }
}
