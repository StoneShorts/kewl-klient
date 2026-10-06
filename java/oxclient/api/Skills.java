package oxclient.api;

import oxclient.Natives;

/**
 * Your levels and experience.
 *
 * <p>Three numbers exist per skill and they mean different things:</p>
 *
 * <ul>
 *   <li>{@link #level} -- what your experience has earned you. This is the one that gates content.</li>
 *   <li>{@link #effective} -- that number after potions, prayers and drains. It is what the game
 *       actually uses when it decides whether you can chop a tree right now, and it is what the skill
 *       tab shows in a different colour when you are boosted.</li>
 *   <li>{@link #experience} -- total xp. Snapshot it at startup and subtract to get xp/hour.</li>
 * </ul>
 *
 * <p>Reading the whole table costs one call into the game, cached for the frame, so asking for twenty
 * skills is no more expensive than asking for one.</p>
 */
public final class Skills {

    private Skills() {}

    private static int[] cache = new int[0];
    private static int cachedAtFrame = -1;
    private static int frame;

    /** Called once per frame by the client so the cache expires exactly when the world does. */
    public static void newFrame() { frame++; }

    private static int[] table() {
        if (cachedAtFrame != frame) {
            cache = Natives.skills();
            cachedAtFrame = frame;
        }
        return cache;
    }

    private static int at(int block, Skill skill) {
        int[] t = table();
        int i = block * Skill.ARRAY_SIZE + skill.index();
        return i < t.length ? t[i] : 0;
    }

    /** The boosted or drained level -- what the game checks against right now. */
    public static int effective(Skill skill) { return at(0, skill); }

    /** The level your experience has earned, ignoring boosts. */
    public static int level(Skill skill) { return at(1, skill); }

    /** Total experience in this skill. */
    public static int experience(Skill skill) { return at(2, skill); }

    /** How many levels of boost you currently have; negative when drained. */
    public static int boost(Skill skill) { return effective(skill) - level(skill); }

    /** Your total level, added up across every skill. */
    public static int totalLevel() {
        int sum = 0;
        for (Skill s : Skill.values()) sum += level(s);
        return sum;
    }
}
