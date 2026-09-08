package kewl.api;

/**
 * The skills, in the game's own array order.
 *
 * <p>The order is not alphabetical and not the order the skill tab shows -- it is the order the client
 * stores them in, and the index is what indexes the three stat arrays. Do not reorder these.</p>
 *
 * <p>{@code HITPOINTS} being index 3 is worth knowing on its own: your current and maximum health are
 * just the effective and base values of that skill, which is why there is no separate health field
 * anywhere in this client.</p>
 */
public enum Skill {

    ATTACK(0),
    DEFENCE(1),
    STRENGTH(2),
    HITPOINTS(3),
    RANGED(4),
    PRAYER(5),
    MAGIC(6),
    COOKING(7),
    WOODCUTTING(8),
    FLETCHING(9),
    FISHING(10),
    FIREMAKING(11),
    CRAFTING(12),
    SMITHING(13),
    MINING(14),
    HERBLORE(15),
    AGILITY(16),
    THIEVING(17),
    SLAYER(18),
    FARMING(19),
    RUNECRAFT(20),
    HUNTER(21),
    CONSTRUCTION(22),
    SAILING(23);

    /** How many entries the client's stat arrays actually hold -- more than there are skills. */
    public static final int ARRAY_SIZE = 25;

    private final int index;

    Skill(int index) { this.index = index; }

    /** This skill's position in the client's stat arrays. */
    public int index() { return index; }

    /** A display name, e.g. "Woodcutting". */
    public String displayName() {
        String n = name().toLowerCase();
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }
}
