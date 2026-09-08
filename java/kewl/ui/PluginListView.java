package kewl.ui;

import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import kewl.Plugin;
import kewl.api.Game;
import kewl.api.Local;

/**
 * The plugins list: RuneLite's PluginListPanel. One 22px row per plugin -- name on the left, gear and
 * switch on the right -- sorted alphabetically, exactly the shape of a PluginListItem row minus the pin
 * star (pinning needs persistence the kewl config store does not have, so it is left out rather than
 * faked with a toggle that forgets).
 *
 * <p>RuneLite's rows are one line on purpose: the description and the live status are tooltips, not
 * second lines, and this list keeps that rule -- hover a row and the description (plus a running
 * plugin's status) shows in a tooltip. Clicking the name or the gear opens the plugin's config, which
 * in RuneLite pushes a ConfigPanel onto the panel's CardLayout stack.</p>
 */
final class PluginListView {

    private PluginListView() {}

    private static final int ROW_H = 22;
    private static final int GAP = 5;      // DynamicGridLayout(0, 1, 0, 5) in PluginListPanel

    static int draw(PanelCtx ctx, List<Plugin> plugins, int y) {
        y = Widgets.title(ctx, ctx.left, y, "KewlKlient", Game.ready() ? "in game" : "waiting for the game...");

        // The old panel's live position line, kept so the header still says whether the memory reads work.
        if (Game.ready()) {
            Graphics2D g = ctx.g;
            Local me = Game.me();
            g.setFont(Theme.MONO);
            g.setColor(Theme.TEXT_DIM);
            g.drawString(me.worldX() + ", " + me.worldY() + "   run " + me.runEnergy() + "%", ctx.left, y);
            y += 18;
        }
        y += 6;

        // Alphabetical, but developer scaffolding (kewl.Plugin.developer(): the shim smoke tests and
        // the two kewl box drawers the RuneLite ports replaced) sorts below everything and draws
        // under its own heading. Same grouping the ImGui panel does, so the two panels still agree
        // about what the list looks like.
        List<Plugin> sorted = new ArrayList<>(plugins);
        sorted.sort(Comparator.comparing((Plugin p) -> p.developer())
                .thenComparing((Plugin p) -> p.name().toLowerCase()));

        boolean headingDrawn = false;
        for (Plugin p : sorted) {
            if (p.developer() && !headingDrawn) {
                headingDrawn = true;
                y = developerHeader(ctx, y);
            }
            if (p.developer() && !developerOpen) continue;
            y = row(ctx, p, y);
        }
        return y + SidePanel.PAD;
    }

    // Collapsed by default, like the launcher's Developer header: the point of the grouping is a
    // list of the plugins someone actually runs. One static flag -- there is one panel, drawn on one
    // thread, and the state is a UI preference that is fine to forget between sessions.
    private static boolean developerOpen;

    /** The "Developer" divider: chevron, dim bold name, 1px line -- ConfigView.sectionHeader's shape. */
    private static int developerHeader(PanelCtx ctx, int y) {
        Graphics2D g = ctx.g;
        int x = ctx.left;
        int w = ctx.right - x;
        y += 4;
        if (!ctx.visible(y, 18)) {
            ctx.hit(x, y, w, 18, () -> developerOpen = !developerOpen);
            return y + 18 + GAP;
        }
        boolean hover = ctx.hover(x, y, w, 18);

        Widgets.chevron(g, x, y + 5, !developerOpen, hover ? Theme.RL_LABEL : Theme.TEXT_DIM);
        g.setFont(Theme.UI_BOLD);
        g.setColor(hover ? Theme.RL_LABEL : Theme.TEXT_DIM);
        g.drawString("Developer", x + 14, y + 12);
        g.setColor(Theme.RL_DIVIDER);
        g.drawLine(x, y + 17, x + w, y + 17);

        ctx.hit(x, y, w, 18, () -> developerOpen = !developerOpen);
        if (hover) ctx.tooltip("smoke tests and worked examples, kept for development");
        return y + 18 + GAP;
    }

    private static int row(PanelCtx ctx, Plugin p, int y) {
        Graphics2D g = ctx.g;
        int x = ctx.left;
        int w = ctx.right - x;
        boolean hasConfig = !p.config.isEmpty();

        if (!ctx.visible(y, ROW_H)) return y + ROW_H + GAP;

        boolean hover = ctx.hover(x, y, w, ROW_H);

        // Name, white, turning RuneLite's orange on hover like PluginListItem's label.
        g.setFont(Theme.UI);
        g.setColor(hover ? Theme.RL_ORANGE : Theme.RL_LABEL);
        g.drawString(Widgets.clip(g, p.name(), w - 90), x + 2, y + 14);

        // Gear, only when there is something to configure -- PluginListItem does the same.
        if (hasConfig) {
            Widgets.gear(g, ctx.right - 46, y + 5, hover ? Theme.RL_ORANGE : Theme.TEXT_DIM);
        }
        Widgets.toggle(g, ctx.right - 28, y + 4, p.isEnabled());

        // First match wins, so the specific controls are registered before the row they sit in.
        if (hasConfig) {
            ctx.hit(ctx.right - 50, y, 18, ROW_H, () -> SidePanel.openConfig(p));
        }
        ctx.hit(ctx.right - 32, y, 32, ROW_H, () -> p.later(() -> p.setEnabled(!p.isEnabled())));
        if (hasConfig) {
            ctx.hit(x, y, w - 60, ROW_H, () -> SidePanel.openConfig(p));
        }

        // Description and live status as a tooltip; the row is one line, like RuneLite's.
        if (hover) {
            String tip = p.description();
            if (p.isEnabled() && !p.status().isEmpty()) {
                tip = (tip.isEmpty() ? "" : tip + "  |  ") + p.status();
            }
            if (p.hotkey() >= 0 && p.hotkey() < 8) {
                tip = (tip.isEmpty() ? "" : tip + "  ") + "[F" + (p.hotkey() + 1) + "]";
            }
            ctx.tooltip(tip);
        }
        return y + ROW_H + GAP;
    }
}
