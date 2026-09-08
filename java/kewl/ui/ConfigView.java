package kewl.ui;

import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import kewl.Plugin;
import kewl.config.Setting;
import net.runelite.client.config.Keybind;

/**
 * One plugin's config screen: RuneLite's ConfigPanel. A top bar of [back arrow] [plugin name]
 * [on/off switch], then the settings grouped under collapsible section headers -- orange bold name, a
 * 1px divider, a chevron -- then Reset and Back buttons at the bottom, all of it matching the layout
 * decisions ConfigPanel.rebuild makes:
 *
 * <ul>
 *   <li>every row is label left, control right, on one line -- except String settings, whose field
 *       goes on a full-width line BELOW the label (ConfigPanel puts them in BorderLayout.SOUTH);</li>
 *   <li>sections collapse, and remember it, the way ConfigPanel's sectionExpandStates does;</li>
 *   <li>right-clicking a label resets that one setting, which is the per-item "Reset" entry on
 *       RuneLite's label pop-up menu.</li>
 * </ul>
 *
 * <p>Two things are ours rather than RuneLite's, for lack of the machinery: text fields show their
 * value but cannot be edited (the panel has no keyboard), and Reset skips RuneLite's confirm dialog in
 * favour of a click-again-to-confirm on the button itself.</p>
 */
final class ConfigView {

    private ConfigView() {}

    private static final int ROW_H = 24;          // one label + control line
    private static final int TEXT_LABEL_H = 18;   // the label line of a String setting
    private static final int TEXT_BOX_H = 20;     // its full-width value line
    private static final int GAP = 5;             // DynamicGridLayout(0, 1, 0, 5)
    private static final int BOX_W = 76;          // drop-down / keybind / spinner box
    private static final int TRACK_W = 60;        // slider track
    private static final int VALUE_W = 48;        // the value's right-aligned column

    /**
     * The Reset button arms on the first click and fires on the second, standing in for the
     * JOptionPane confirm RuneLite shows. Holding the plugin name here disarms naturally when the
     * view is redrawn for a different plugin.
     */
    private static String resetArmedFor;

    static int draw(PanelCtx ctx, Plugin p, int y) {
        y = topBar(ctx, p, y);

        RlConfigMeta.Meta meta = RlConfigMeta.of(p);
        int x = ctx.left;
        int w = ctx.right - x;

        if (p.config.isEmpty()) {
            ctx.g.setFont(Theme.UI);
            ctx.g.setColor(Theme.TEXT_DIM);
            ctx.g.drawString("no settings", x, y + 12);
            return y + 24;
        }

        if (meta.isEmpty()) {
            // A plain kewl plugin: no interface to introspect, so one unsectioned list in declaration
            // order, which is the order Config promises to keep.
            y = items(ctx, new ArrayList<>(p.config.all()), meta, x, w, y);
        } else {
            // RuneLite ordering: sections sorted by position then name, with the settings each one
            // owns beneath it. Settings with no section go above the sections -- ConfigPanel
            // interleaves them by position among the sections, but the only sectionless setting an
            // adapter declares is its own (RlitePlugin's auto-walk switch), and up top is where it
            // reads best.
            Map<String, List<Setting>> bySection = new LinkedHashMap<>();
            List<Setting> loose = new ArrayList<>();
            for (Setting s : p.config.all()) {
                String sec = meta.itemSection.get(s.key());
                if (sec != null && meta.sections.containsKey(sec)) {
                    bySection.computeIfAbsent(sec, k -> new ArrayList<>()).add(s);
                } else {
                    loose.add(s);
                }
            }
            if (!loose.isEmpty()) y = items(ctx, loose, meta, x, w, y);

            for (RlConfigMeta.Section section : RlConfigMeta.sections(p)) {
                List<Setting> in = bySection.get(section.key());
                if (in == null) continue;
                y = sectionHeader(ctx, p, section, x, w, y);
                if (SidePanel.sectionOpen(p, section)) {
                    y = items(ctx, in, meta, x, w, y);
                    // ConfigPanel gives sectionContents its own 1px bottom divider.
                    ctx.g.setColor(Theme.RL_DIVIDER);
                    ctx.g.drawLine(x, y - GAP + 2, x + w, y - GAP + 2);
                }
                y += 4;
            }
        }

        y += 6;
        boolean armed = p.name().equals(resetArmedFor);
        Widgets.button(ctx, x, y, w, 22, armed ? "click again to reset" : "Reset",
                () -> {
                    if (p.name().equals(resetArmedFor)) {
                        resetArmedFor = null;
                        // Setting.reset is the declared default, straight from the Setting itself --
                        // a snapshot taken at start-up would record whatever the active profile had
                        // already applied by the time the panel was built.
                        for (Setting setting : p.config.all()) setting.reset();
                    } else {
                        resetArmedFor = p.name();
                    }
                });
        y += 26;
        Widgets.button(ctx, x, y, w, 22, "Back", SidePanel::closeConfig);
        return y + 22 + SidePanel.PAD;
    }

    /** [back arrow] [name] [switch], ConfigPanel's north panel. */
    private static int topBar(PanelCtx ctx, Plugin p, int y) {
        Graphics2D g = ctx.g;

        Widgets.backArrow(g, ctx.left, y + 5, ctx.hover(ctx.left - SidePanel.PAD, y, 24, 22) ? Theme.RL_ORANGE : Theme.TEXT_DIM);
        ctx.hit(ctx.left - SidePanel.PAD, y, 24, 22, SidePanel::closeConfig);

        g.setFont(Theme.UI_BOLD);
        g.setColor(Theme.RL_LABEL);
        String title = Widgets.clip(g, p.name(), ctx.right - ctx.left - 76);
        int tw = g.getFontMetrics().stringWidth(title);
        g.drawString(title, ctx.left + (ctx.right - ctx.left - tw) / 2, y + 13);

        // The plugin's own switch, duplicated on its config screen exactly as ConfigPanel does.
        Widgets.toggle(g, ctx.right - 28, y + 4, p.isEnabled());
        ctx.hit(ctx.right - 32, y - 2, 36, 24, () -> p.later(() -> p.setEnabled(!p.isEnabled())));

        y += 26;
        g.setColor(Theme.RL_DIVIDER);
        g.drawLine(ctx.left, y, ctx.right, y);
        return y + 10;
    }

    /** One collapsible section header: chevron, orange bold name, 1px divider. */
    private static int sectionHeader(PanelCtx ctx, Plugin p, RlConfigMeta.Section section, int x, int w, int y) {
        Graphics2D g = ctx.g;
        boolean open = SidePanel.sectionOpen(p, section);
        boolean hover = ctx.hover(x, y, w, 18);

        Widgets.chevron(g, x, y + 5, !open, hover ? Theme.RL_LABEL : Theme.RL_ORANGE);
        g.setFont(Theme.UI_BOLD);
        g.setColor(Theme.RL_ORANGE);
        g.drawString(Widgets.clip(g, section.name(), w - 20), x + 14, y + 12);

        g.setColor(Theme.RL_DIVIDER);
        g.drawLine(x, y + 17, x + w, y + 17);

        ctx.hit(x, y, w, 18, () -> SidePanel.toggleSection(p, section));
        if (hover && !section.description().isEmpty()) {
            ctx.tooltip(section.name() + ": " + section.description());
        }
        return y + 18;
    }

    /** The settings of one group, in RuneLite's position-then-name order when there is metadata for it. */
    private static int items(PanelCtx ctx, List<Setting> settings, RlConfigMeta.Meta meta,
                             int x, int w, int y) {
        if (!meta.isEmpty()) {
            List<Setting> sorted = new ArrayList<>(settings);
            // ConfigPanel sorts items by position then name; a stable sort keeps declaration order
            // for everything that has no position (such as the adapter's own settings).
            sorted.sort(Comparator
                    .comparingInt((Setting s) -> meta.positions.getOrDefault(s.key(), Integer.MAX_VALUE))
                    .thenComparing(s -> s.label().toLowerCase()));
            settings = sorted;
        }
        for (Setting s : settings) {
            y = item(ctx, s, meta, x, w, y);
        }
        return y;
    }

    /** One setting: label left, control right -- or the control below, for text. */
    private static int item(PanelCtx ctx, Setting s, RlConfigMeta.Meta meta, int x, int w, int y) {
        Graphics2D g = ctx.g;
        boolean keybind = s.kind() == Setting.Kind.INT && meta.keybinds.contains(s.key());
        String units = meta.units.getOrDefault(s.key(), "");

        boolean isText = s.kind() == Setting.Kind.TEXT;
        int h = isText ? TEXT_LABEL_H + TEXT_BOX_H : ROW_H;
        if (!ctx.visible(y, h)) return y + h + GAP;

        // Label. White, orange on hover, tooltip on hover, right-click resets -- all four are what
        // PluginListItem.addLabelPopupMenu + ConfigPanel give a config entry name.
        int labelW = isText ? w : w - 120;
        boolean hover = ctx.hover(x, y, labelW, h);
        g.setFont(Theme.UI);
        g.setColor(hover ? Theme.RL_ORANGE : Theme.RL_LABEL);
        g.drawString(Widgets.clip(g, s.label(), labelW - 4), x, y + 12);
        if (hover) ctx.tooltip(s.label() + ": " + s.description());
        ctx.right(x, y, labelW, h, s::reset);

        switch (s.kind()) {
            case BOOL -> {
                Widgets.toggle(g, ctx.right - 28, y + 5, s.asBool());
                ctx.hit(ctx.right - 32, y - 2, 36, ROW_H + 2, () -> s.set(!s.asBool()));
            }
            case INT -> {
                if (keybind) keybind(ctx, s, x, w, y);
                else if (s.max() > s.min() && (long) s.max() - s.min() <= 1000) slider(ctx, s, x, y, units);
                else spinner(ctx, s, x, w, y, units);
            }
            case COLOR -> {
                int sw = 34;
                g.setColor(s.asColor());
                g.fillRoundRect(ctx.right - sw, y + 4, sw, 16, 4, 4);
                g.setColor(Theme.RL_DIVIDER);
                g.drawRoundRect(ctx.right - sw, y + 4, sw, 16, 4, 4);
                ctx.hit(ctx.right - sw - 4, y - 2, sw + 8, ROW_H + 2, () -> s.set(nextColour(s.asColor())));
            }
            case ENUM -> {
                Object[] opts = s.options();
                if (opts != null && opts.length > 0) {
                    stepper(ctx, x, w, y, String.valueOf(opts[indexOf(opts, s.value())]),
                            () -> s.set(opts[Math.floorMod(indexOf(opts, s.value()) - 1, opts.length)]),
                            () -> s.set(opts[Math.floorMod(indexOf(opts, s.value()) + 1, opts.length)]));
                }
            }
            case TEXT -> {
                // The value, shown in a full-width box that admits it cannot be edited: text input
                // needs a keyboard the panel does not have yet. RuneLite puts the field below the
                // label too, so at least the geometry matches.
                int by = y + TEXT_LABEL_H;
                ctx.surface(x, by, w, TEXT_BOX_H, false);
                g.setFont(Theme.MONO);
                g.setColor(Theme.TEXT);
                String hint = "no keyboard on panel yet";
                int hintW = g.getFontMetrics().stringWidth(hint);
                // displayText, not asText: a secret (password) setting draws its fixed mask, never
                // the characters and never anything as long as them.
                g.drawString(Widgets.clip(g, s.displayText(), w - hintW - 16), x + 5, by + 14);
                g.setFont(Theme.UI);
                g.setColor(Theme.TEXT_DIM);
                g.drawString(hint, ctx.right - hintW - 4, by + 14);
            }
        }
        return y + h + GAP;
    }

    /** A slider for a bounded int, with the value (and its @Units suffix) right-aligned past it. */
    private static void slider(PanelCtx ctx, Setting s, int x, int y, String units) {
        Graphics2D g = ctx.g;
        int trackX = ctx.right - VALUE_W - 8 - TRACK_W;

        g.setFont(Theme.MONO);
        g.setColor(Theme.TEXT);
        Widgets.rightText(g, s.asInt() + units, ctx.right, y + 14);

        g.setColor(Theme.SURFACE_HI);
        g.fillRoundRect(trackX, y + 9, TRACK_W, 6, 3, 3);
        int range = Math.max(1, s.max() - s.min());
        int px = trackX + (int) ((long) (s.asInt() - s.min()) * TRACK_W / range);
        g.setColor(Theme.RL_ORANGE);
        g.fillOval(px - 5, y + 5, 12, 12);
        ctx.hit(trackX - 4, y - 3, TRACK_W + 8, ROW_H + 3, () -> SidePanel.beginDrag(s, trackX, TRACK_W));
    }

    /** A stepper for an unbounded int -- the spinner an int without a @Range gets, +/- 1 per click. */
    private static void spinner(PanelCtx ctx, Setting s, int x, int w, int y, String units) {
        stepper(ctx, x, w, y, s.asInt() + units,
                () -> s.set(s.asInt() - 1),
                () -> s.set(s.asInt() + 1));
    }

    /**
     * The keybind control. The shim stores a Keybind as an F-index (0 = not set), so the control is a
     * stepper over "not set", F1..F8 rather than a real key catcher -- the panel sees no keystrokes.
     */
    private static void keybind(PanelCtx ctx, Setting s, int x, int w, int y) {
        int f = Math.max(0, Math.min(8, s.asInt()));
        String label = f == 0 ? Keybind.NOT_SET.toString() : "F" + f;
        stepper(ctx, x, w, y, label,
                () -> s.set(Math.max(0, s.asInt() - 1)),
                () -> s.set(Math.min(8, s.asInt() + 1)));
    }

    /** The shared [ < value > ] box: enum drop-down, spinner and keybind all reduce to this. */
    private static void stepper(PanelCtx ctx, int x, int w, int y, String value, Runnable dec, Runnable inc) {
        Graphics2D g = ctx.g;
        int bx = ctx.right - BOX_W;
        ctx.surface(bx, y + 2, BOX_W, 18, ctx.hover(bx, y, BOX_W, ROW_H));

        Widgets.chevron(g, bx + 5, y + 7, true, Theme.TEXT_DIM);
        Widgets.chevron(g, bx + BOX_W - 11, y + 7, false, Theme.TEXT_DIM);

        g.setFont(Theme.UI);
        g.setColor(Theme.TEXT);
        String label = Widgets.clip(g, value, BOX_W - 32);
        int lw = g.getFontMetrics().stringWidth(label);
        g.drawString(label, bx + (BOX_W - lw) / 2, y + 14);

        ctx.hit(bx, y - 2, 20, ROW_H + 2, dec);
        ctx.hit(bx + BOX_W - 20, y - 2, 20, ROW_H + 2, inc);
    }

    /** A swatch click walks a fixed palette -- no modal colour dialog lives on an overlay. */
    private static java.awt.Color nextColour(java.awt.Color c) {
        java.awt.Color[] palette = {
                Theme.RL_ORANGE, Theme.ACCENT, Theme.ON, Theme.WARN, java.awt.Color.WHITE,
                java.awt.Color.MAGENTA, java.awt.Color.YELLOW, java.awt.Color.CYAN,
                java.awt.Color.ORANGE, java.awt.Color.RED, java.awt.Color.GREEN
        };
        for (int i = 0; i < palette.length; i++) {
            if (palette[i].equals(c)) return palette[(i + 1) % palette.length];
        }
        return palette[0];
    }

    private static int indexOf(Object[] a, Object v) {
        for (int i = 0; i < a.length; i++) if (a[i].equals(v)) return i;
        return 0;   // an unknown value (a removed enum constant) reads as the first one rather than crashing
    }
}
