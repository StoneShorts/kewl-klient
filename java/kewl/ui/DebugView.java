package kewl.ui;

import java.awt.Graphics2D;
import java.util.List;

import kewl.Natives;
import kewl.api.Game;
import kewl.api.Local;

/**
 * The debug tab: a live readout of what the memory reads are actually returning. This is the panel's
 * answer to "how do I know an offset is right" -- the numbers on screen can be checked against the game
 * itself, right now. Unchanged by the RuneLite restyle; no part of RuneLite's panel does this job.
 */
final class DebugView {

    private DebugView() {}

    static int draw(PanelCtx ctx, int y) {
        y = Widgets.title(ctx, ctx.left, y, "Debug", "what the memory reads return right now");

        y = line(ctx, y, "ready", String.valueOf(Game.ready()));
        if (!Game.ready()) return y;

        Local me = Game.me();
        y = line(ctx, y, "you", me.worldX() + ", " + me.worldY() + " plane " + me.plane());
        y = line(ctx, y, "hp / run", me.health() + " / " + me.runEnergy() + "%");

        y = section(ctx, y, "npcs in view (id, name)");
        var npcs = Game.npcs();
        int shown = 0;
        for (var n : npcs) {
            if (shown++ >= 10) { y = dim(ctx, y, "... " + (npcs.size() - 10) + " more"); break; }
            String nm = n.name();
            y = line(ctx, y, String.valueOf(n.id()), nm.isEmpty() ? "(" + n + ")" : nm);
        }
        if (npcs.isEmpty()) y = dim(ctx, y, "(none)");

        y = section(ctx, y, "inventory (container 93)");
        int[] inv = Natives.container(93);
        if (inv == null || inv.length == 0) {
            y = dim(ctx, y, "(empty or container not found)");
        } else {
            y = dim(ctx, y, inv.length / 2 + " slots:");
            StringBuilder row = new StringBuilder();
            for (int i = 0; i * 2 < inv.length && i < 14; i++) {
                if (row.length() > 0) row.append(' ');
                row.append(inv[2 * i]).append('x').append(inv[2 * i + 1]);
                if ((i + 1) % 5 == 0) { y = dim(ctx, y, row.toString()); row.setLength(0); }
            }
            if (row.length() > 0) y = dim(ctx, y, row.toString());
        }

        y = section(ctx, y, "varps (first 6)");
        StringBuilder vp = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            vp.append(i).append('=').append(Natives.varp(i)).append("  ");
        }
        y = dim(ctx, y, vp.toString());

        // The one thing this tab can prove without a login: the pathfinder core, computing a real
        // route over the bundled map. Runs once per session, the first time this tab is drawn --
        // a half-second hitch on open is the honest cost of showing a real answer instead of a
        // guess (see PathCheck).
        y = section(ctx, y, "pathfinder self-check (Lumbridge -> Varrock)");
        if (!PATHCHECK_DONE) {
            PATHCHECK_DONE = true;
            kewl.rl.PathCheck.run();
        }
        return dim(ctx, y, kewl.rl.PathCheck.result());
    }

    private static boolean PATHCHECK_DONE;

    private static int section(PanelCtx ctx, int y, String title) {
        y += 10;
        ctx.g.setFont(Theme.UI_BOLD);
        ctx.g.setColor(Theme.RL_ORANGE);
        ctx.g.drawString(title, ctx.left, y + 10);
        return y + 20;
    }

    private static int line(PanelCtx ctx, int y, String k, String v) {
        Graphics2D g = ctx.g;
        g.setFont(Theme.MONO);
        g.setColor(Theme.TEXT_DIM);
        g.drawString(k, ctx.left, y + 12);
        g.setColor(Theme.TEXT);
        g.drawString(Widgets.clip(g, v == null ? "" : v, ctx.right - ctx.left - 90), ctx.left + 90, y + 12);
        return y + 17;
    }

    private static int dim(PanelCtx ctx, int y, String s) {
        ctx.g.setFont(Theme.MONO);
        ctx.g.setColor(Theme.TEXT_DIM);
        ctx.g.drawString(Widgets.clip(ctx.g, s, ctx.right - ctx.left), ctx.left, y + 12);
        return y + 15;
    }
}
