package kewl.plugins;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.util.HashSet;
import java.util.Set;

import kewl.Plugin;
import kewl.api.Entity;
import kewl.api.Game;
import kewl.api.Npcs;
import kewl.ui.Hud;
import kewl.ui.Theme;

/**
 * Draws a box over every NPC, optionally only the ones you care about.
 *
 * <p>THE WORKED EXAMPLE, not the client's NPC visuals. Slightly more involved than the player one
 * because of the id filter, which is also the most useful thing to learn here: leave it blank while
 * you look around, read the ids off the boxes, then type the ones you want. That is how you find an
 * id without a cache reader.</p>
 *
 * <p>What the client actually draws over NPCs is the RuneLite port, {@code NPC Indicators}
 * ({@code net.runelite.client.plugins.npchighlight}): a convex hull at the NPC's own ground height
 * instead of a fixed 16x30 box, the name (falling back to {@code #id}, the trick this example
 * teaches) instead of the bare id, true/south-west tile styles, a minimap name, and RuneLite's own
 * wildcard list -- with a plain number in that list still matching a type id, so the workflow above
 * carries straight over. This one is registered in the Developer section and stays OFF, so the two
 * never draw over each other (2026-09-06). Nothing in the port reads anything from here.</p>
 */
public final class NpcVisuals extends Plugin {

    private Set<Integer> filter = Set.of();
    private String filterText = "";

    public NpcVisuals() {
        config.colour("colour", "Colour", "Box colour", new Color(255, 210, 90));
        config.text("ids", "Only these ids", "Comma-separated npc ids. Blank shows every npc.", "");
        config.bool("showId", "Show ids", "Draw each npc's type id -- how you find the id you want", true);
        config.bool("tile", "Mark tiles", "Outline the tile each npc is standing on", false);
        config.number("range", "Range", "Only draw npcs within this many tiles", 20, 1, 60);
    }

    @Override public String name() { return "NPC visuals"; }

    @Override public String description() { return "Boxes over NPCs, filtered by id."; }

    @Override public int hotkey() { return 1; }          // F2

    @Override
    public String status() {
        int shown = filter.isEmpty() ? Npcs.all().size() : matching();
        return shown + (filter.isEmpty() ? " visible" : " matching");
    }

    private int matching() {
        int n = 0;
        for (Entity e : Npcs.all()) if (filter.contains(e.id())) n++;
        return n;
    }

    /**
     * Reparse the id list only when the text actually changed.
     *
     * <p>Parsing it every frame would work and would also mean allocating a set thirty times a second
     * to get the same answer, which is the kind of thing that is invisible until it is not.</p>
     */
    @Override
    public void tick() {
        String text = config.text("ids");
        if (text.equals(filterText)) return;
        filterText = text;

        Set<Integer> parsed = new HashSet<>();
        for (String part : text.split(",")) {
            String s = part.trim();
            if (s.isEmpty()) continue;
            try {
                parsed.add(Integer.parseInt(s));
            } catch (NumberFormatException ignored) {
                // Half-typed input is normal while somebody is editing the box. Skip it silently.
            }
        }
        filter = parsed;
    }

    @Override
    public void render(Graphics2D g) {
        if (!Game.ready()) return;

        Color colour = config.colour("colour");
        int range = config.number("range");
        boolean showId = config.bool("showId");
        boolean tiles = config.bool("tile");

        g.setFont(Theme.UI);
        for (Entity npc : Npcs.all()) {
            if (npc.distance() > range) continue;
            if (!filter.isEmpty() && !filter.contains(npc.id())) continue;

            if (tiles) Hud.tile(g, npc.tileOutline(), colour);      // at the NPC's own ground height

            Point at = npc.screen();
            if (at == null) continue;
            Hud.entityBox(g, at, 16, 30, colour);

            if (showId) Hud.textCentred(g, String.valueOf(npc.id()), at.x, at.y - 34, colour);
        }
    }
}
