package oxclient.api;

/**
 * A tile, in world coordinates -- the numbers on your minimap.
 *
 * <p>There are two coordinate systems in this client and mixing them up is the single most common bug.
 * World coordinates are absolute (Lumbridge castle is around 3222, 3218). Scene coordinates are 0..104
 * within the chunk currently loaded in memory, and they are what the game's click function actually
 * takes. Everything a plugin touches is in WORLD coordinates; the conversion happens in one place, in
 * {@link Game#toScene}.</p>
 */
public final class WorldPoint {

    private final int x, y;

    public WorldPoint(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public int x() { return x; }

    public int y() { return y; }

    /** Diagonal (chebyshev) distance, which is how the game measures its own interaction ranges. */
    public int distanceTo(WorldPoint other) {
        if (other == null) return Integer.MAX_VALUE;
        return Math.max(Math.abs(x - other.x), Math.abs(y - other.y));
    }

    /** True when this tile is within {@code radius} of {@code other}. */
    public boolean within(WorldPoint other, int radius) {
        return distanceTo(other) <= radius;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof WorldPoint p && p.x == x && p.y == y;
    }

    @Override
    public int hashCode() { return x * 31 + y; }

    @Override
    public String toString() { return x + "," + y; }
}
