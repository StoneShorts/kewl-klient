package kewl.ui;

import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;

/**
 * The panel's control primitives, drawn rather than instantiated: a switch, a gear, an arrow, a button.
 *
 * <p>RuneLite builds its panel out of Swing components with PNG icons; ours are vector glyphs on the
 * canvas, so "the same widget" here means "the same shape and behaviour at a similar size", not the
 * same pixels. Everything takes absolute coordinates and colours from {@link Theme}, and anything
 * clickable takes the {@link PanelCtx} to register its hotspot with.</p>
 */
final class Widgets {

    private Widgets() {}

    /**
     * The on/off switch, RuneLite's PluginToggleButton as a track and knob. 28x14 so it fits a 22px
     * list row; the old 36x18 panel switch needed a 46px card to sit in.
     */
    static void toggle(Graphics2D g, int x, int y, boolean on) {
        g.setColor(on ? Theme.ON : Theme.OFF);
        g.fillRoundRect(x, y, 28, 14, 7, 7);
        g.setColor(Theme.BACKGROUND);
        g.fillOval(on ? x + 15 : x + 2, y + 2, 10, 10);
    }

    /** The config gear, 12px: a ring, a hub, four teeth. Drawn only when a plugin has settings. */
    static void gear(Graphics2D g, int x, int y, Color c) {
        g.setColor(c);
        g.drawOval(x + 3, y + 3, 6, 6);
        g.fillOval(x + 5, y + 5, 2, 2);
        g.fillRect(x + 5, y, 2, 3);
        g.fillRect(x + 5, y + 9, 2, 3);
        g.fillRect(x, y + 5, 3, 2);
        g.fillRect(x + 9, y + 5, 3, 2);
    }

    /** A left-pointing arrow, RuneLite's BACK_ICON: shaft plus head. */
    static void backArrow(Graphics2D g, int x, int y, Color c) {
        g.setColor(c);
        g.drawLine(x + 10, y + 6, x + 1, y + 6);
        g.drawLine(x + 10, y + 6, x + 6, y + 2);
        g.drawLine(x + 10, y + 6, x + 6, y + 10);
    }

    /** A triangle pointing left or right -- the collapsed/expanded section chevron, and the enum stepper. */
    static void chevron(Graphics2D g, int x, int y, boolean left, Color c) {
        g.setColor(c);
        if (left) {
            g.fillPolygon(new int[]{x + 6, x + 6, x}, new int[]{y, y + 8, y + 4}, 3);
        } else {
            g.fillPolygon(new int[]{x, x, x + 6}, new int[]{y, y + 8, y + 4}, 3);
        }
    }

    /** An x, for deleting a profile. */
    static void cross(Graphics2D g, int x, int y, Color c) {
        g.setColor(c);
        g.drawLine(x, y, x + 8, y + 8);
        g.drawLine(x, y + 8, x + 8, y);
    }

    /**
     * A full-width button, RL's Reset/Back row at the bottom of a config panel. Draws its own hover and
     * registers its own hotspot, because every call site wants exactly the same thing.
     */
    static void button(PanelCtx ctx, int x, int y, int w, int h, String label, Runnable action) {
        ctx.surface(x, y, w, h, ctx.hover(x, y, w, h));
        ctx.g.setFont(Theme.UI_BOLD);
        ctx.g.setColor(Theme.TEXT);
        FontMetrics fm = ctx.g.getFontMetrics();
        ctx.g.drawString(label, x + (w - fm.stringWidth(label)) / 2, y + h / 2 + fm.getAscent() / 2 - 2);
        ctx.hit(x, y, w, h, action);
    }

    /** Text ending in "..." when it does not fit -- the same clipping RuneLite's labels get from Swing. */
    static String clip(Graphics2D g, String s, int w) {
        if (s == null) return "";
        FontMetrics fm = g.getFontMetrics();
        if (fm.stringWidth(s) <= w) return s;
        while (s.length() > 1 && fm.stringWidth(s + "...") > w) s = s.substring(0, s.length() - 1);
        return s + "...";
    }

    /**
     * The panel's title block: a bold title and a dim sub-line. On the plugins view this is the
     * "KewlKlient / waiting for the game..." header; the other views use it for their own titles.
     */
    static int title(PanelCtx ctx, int x, int y, String titleText, String sub) {
        Graphics2D g = ctx.g;
        int w = ctx.right - x;
        g.setFont(Theme.TITLE);
        g.setColor(Theme.TEXT);
        g.drawString(clip(g, titleText, w), x, y + 14);
        y += 22;
        if (sub != null && !sub.isEmpty()) {
            g.setFont(Theme.UI);
            g.setColor(Theme.TEXT_DIM);
            g.drawString(clip(g, sub, w), x, y + 10);
            y += 16;
        }
        return y + 6;
    }

    /** Right-aligned text, for values sitting at the control column. */
    static int rightText(Graphics2D g, String s, int rightEdge, int y) {
        g.drawString(s, rightEdge - g.getFontMetrics().stringWidth(s), y);
        return rightEdge;
    }
}
