package kewl.ui;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import kewl.Natives;
import kewl.Plugin;
import kewl.config.Setting;

/**
 * The side panel, drawn ON the game -- a right-edge icon strip and slide-out body, the way RuneLite does
 * it, instead of a separate window floating beside the game (the old Swing panel spawned wherever Wine's
 * virtual desktop fancied and was often nowhere to be seen).
 *
 * <p>It is its own layered window pinned to the game's right edge, which is what makes it clickable:
 * the main overlay is click-through everywhere, but this window has no WS_EX_TRANSPARENT, so a click on
 * one of its opaque pixels stays with the panel and a click on a transparent pixel falls through to the
 * game. That one window split is the entire input story -- no hooking, no message interception.</p>
 *
 * <p>Java draws the whole panel into a small image each frame (same Java2D freedom as plugin overlays)
 * and C++ puts it on screen; {@code Natives.presentPanel} is the only new unsafe surface. Mouse events
 * arrive from the panel window's own message loop via {@code KewlKlient.panelMouse}.</p>
 *
 * <p>The layout copies RuneLite's configuration panel: three horizontal tabs across the top of the body
 * (RuneLite has Configuration / Profiles / Plugin Hub; ours are <b>plugins</b>, <b>profiles</b> and
 * <b>debug</b>, the debug readout being this client's answer to a question the Plugin Hub does not
 * answer), switching a full-width view below. The plugins view lists every plugin as one row with a
 * gear and a switch; the gear pushes that plugin's config view, which has its own back arrow, and the
 * views are the canvas equivalent of RuneLite's MultiplexingPluginPanel card stack.</p>
 */
public final class SidePanel {

    private SidePanel() {}

    static final int TAB_W = 36;      // the vertical icon strip on the right edge
    static final int BODY_W = 250;    // the slide-out panel next to it; TAB_W + BODY_W is fixed at 286 by the native side
    static final int PAD = 10;
    static final int TABS_H = 32;     // the horizontal tab strip across the top of the body

    private static BufferedImage canvas;
    private static int[] pixels;

    private enum Tab { PLUGINS, PROFILES, DEBUG }
    private static Tab active = Tab.PLUGINS;
    private static Plugin selected;           // the plugin whose config is pushed; null = the tab's own view
    private static int scroll;                // px, per view
    private static int contentHeight;         // set during render, clamps scroll

    // Where the mouse last was, in panel coordinates. Hover highlights come from this between events.
    private static int mx = -1, my = -1;

    // The number slider being dragged, with the geometry it was drawn at, so moving with the button
    // held keeps changing it. The geometry travels with the drag because the slider's x depends on the
    // row it sits in, and only the frame that drew it knows where that was.
    private record Drag(Setting setting, int trackX, int trackW) {}
    private static Drag dragging;

    // Collapsed/collapsed-open state per section, keyed by plugin + section key, the way ConfigPanel's
    // sectionExpandStates survives rebuilds.
    private static final Map<String, Boolean> SECTION_OPEN = new HashMap<>();

    // Hit regions rebuilt every render; a click looks its action up here. One frame of latency between
    // layout change and clickability, which at 30 fps nobody can perceive.
    private static final List<PanelCtx.Hit> hits = new ArrayList<>();
    private static final List<PanelCtx.Hit> rightHits = new ArrayList<>();

    // ------------------------------------------------------------------ events, from the native side

    /**
     * One mouse event for the panel. button: 0 move, 1 left, 2 middle, 3 right, 4 wheel.
     *
     * <p>x &lt; 0 means "the pointer has left the panel", sent by the native loop because a window
     * only hears WM_MOUSEMOVE while the pointer is on it -- without that signal the last hovered
     * row would stay highlighted forever. A leave during a slider drag keeps the drag alive:
     * re-entering resumes it, which is also what happens without the signal.</p>
     */
    public static void mouse(int x, int y, int button, boolean down) {
        mx = x; my = y;
        if (x < 0) return;
        if (button == 4) {                                   // wheel
            scroll += down ? 40 : -40;
            clampScroll();
            return;
        }
        if (button == 0) {                                   // move
            if (dragging != null) {
                applyDrag();
            }
            return;
        }
        if (button != 1 && button != 3) return;              // left and right mean something; middle does not

        if (!down) {                                         // release ends a drag
            dragging = null;
            return;
        }

        if (x >= BODY_W) {                                   // the vertical icon strip: the same three tabs
            int i = y / TAB_W;
            if (i == 0) selectTab(Tab.PLUGINS);
            else if (i == 1) selectTab(Tab.PROFILES);
            else if (i == 2) selectTab(Tab.DEBUG);
            return;
        }

        List<PanelCtx.Hit> regions = button == 3 ? rightHits : hits;
        for (PanelCtx.Hit h : regions) {
            if (h.hits(x, y)) {
                try {
                    h.action().run();
                } catch (Throwable t) {
                    System.out.println("[panel] click threw: " + t);
                }
                return;
            }
        }
    }

    // ------------------------------------------------------------------ per-frame

    /**
     * Draw the panel and put it on screen. {@code clientH} is the game's client height; the panel is
     * always exactly that tall, pinned to the right edge by the native side.
     */
    public static void frame(int clientH) {
        if (clientH <= 0) return;
        if (canvas == null || canvas.getHeight() != clientH) {
            canvas = new BufferedImage(TAB_W + BODY_W, clientH, BufferedImage.TYPE_INT_ARGB_PRE);
            pixels = ((DataBufferInt) canvas.getRaster().getDataBuffer()).getData();
        }

        java.util.Arrays.fill(pixels, 0);
        Graphics2D g = canvas.createGraphics();
        try {
            pretty(g);
            hits.clear();
            rightHits.clear();
            PanelCtx ctx = new PanelCtx(g, BODY_W, clientH, hits, rightHits);
            ctx.mx = mx;
            ctx.my = my;
            drawBody(ctx, clientH);
            ctx.drawTooltips();                       // last, so nothing draws over a tooltip
            drawScrollbar(ctx, clientH);
            drawIconStrip(g, clientH);
        } finally {
            g.dispose();
        }
        Natives.presentPanel(pixels, TAB_W + BODY_W, clientH);
    }

    private static void pretty(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    // ------------------------------------------------------------------ the body

    private static void drawBody(PanelCtx ctx, int clientH) {
        Graphics2D g = ctx.g;
        g.setColor(Theme.BACKGROUND);
        g.fillRect(0, 0, BODY_W, clientH);
        g.setColor(Theme.BORDER);
        g.drawLine(BODY_W - 1, 0, BODY_W - 1, clientH);

        drawTabStrip(ctx);
        int startY = TABS_H + PAD - scroll;

        int yEnd;
        if (selected != null) {
            // A config view is pushed over whatever tab was showing, like ConfigPanel sitting on
            // PluginListPanel's muxer; the back arrow pops it.
            yEnd = ConfigView.draw(ctx, selected, startY);
        } else {
            switch (active) {
                case PLUGINS -> yEnd = PluginListView.draw(ctx, plugins(), startY);
                case PROFILES -> yEnd = ProfilesView.draw(ctx, startY);
                default -> yEnd = DebugView.draw(ctx, startY);
            }
        }

        // Back into content space: yEnd is a screen y, and the content began scroll pixels above TABS_H.
        contentHeight = yEnd + scroll - TABS_H;
        clampScroll();
    }

    /** The horizontal tab strip across the top -- TopLevelConfigPanel's MaterialTabGroup. */
    private static void drawTabStrip(PanelCtx ctx) {
        Graphics2D g = ctx.g;
        Tab[] tabs = { Tab.PLUGINS, Tab.PROFILES, Tab.DEBUG };
        int margin = 8, gap = 4;
        int tw = (BODY_W - margin * 2 - gap * (tabs.length - 1)) / tabs.length;

        for (int i = 0; i < tabs.length; i++) {
            Tab t = tabs[i];
            int x = margin + i * (tw + gap);
            boolean current = t == active && selected == null;
            boolean hover = ctx.hover(x, 0, tw, TABS_H);

            // MaterialTab: a flat darker block, no bevel, and the selection shown as an orange
            // underline along the BOTTOM of the tab -- not a bar beside it.
            g.setColor(!current && hover ? Theme.RL_TAB_HI : Theme.RL_TAB);
            g.fillRect(x, 4, tw, TABS_H - 8);
            if (current) {
                g.setColor(Theme.RL_ORANGE);
                g.fillRect(x, TABS_H - 3, tw, 2);
            }
            g.setColor(current || hover ? Theme.RL_LABEL : Theme.TEXT_DIM);
            tabIcon(g, x + (tw - 16) / 2, 8, t);
            Tab chosen = t;
            ctx.hit(x, 0, tw, TABS_H, () -> selectTab(chosen));
        }
    }

    /** A thin scroll indicator on the body's right edge, where RuneLite reserves a 17px scrollbar. */
    private static void drawScrollbar(PanelCtx ctx, int clientH) {
        int viewH = clientH - TABS_H;
        if (contentHeight <= viewH || viewH <= 0) return;
        int trackH = viewH - 2;
        int thumbH = Math.max(20, trackH * viewH / contentHeight);
        int thumbY = TABS_H + 1 + (trackH - thumbH) * scroll / (contentHeight - viewH);
        ctx.g.setColor(Theme.RL_DIVIDER);
        ctx.g.fillRect(BODY_W - 3, thumbY, 2, thumbH);
    }

    private static void selectTab(Tab t) {
        active = t;
        selected = null;                     // a tab switch pops the config view, as the muxer would
        scroll = 0;
    }

    /** Open a plugin's config -- the push onto the card stack. */
    static void openConfig(Plugin p) {
        selected = p;
        scroll = 0;
    }

    /** Close it -- the pop. */
    static void closeConfig() {
        selected = null;
        scroll = 0;
    }

    static boolean sectionOpen(Plugin p, RlConfigMeta.Section s) {
        return SECTION_OPEN.computeIfAbsent(p.name() + "/" + s.key(), k -> !s.closedByDefault());
    }

    static void toggleSection(Plugin p, RlConfigMeta.Section s) {
        SECTION_OPEN.put(p.name() + "/" + s.key(), !sectionOpen(p, s));
    }

    static void beginDrag(Setting s, int trackX, int trackW) {
        dragging = new Drag(s, trackX, trackW);
        applyDrag();                         // a click jumps the knob straight to the click point
    }

    private static void applyDrag() {
        Drag d = dragging;
        if (d == null) return;
        int range = Math.max(1, d.setting().max() - d.setting().min());
        int v = d.setting().min() + (int) Math.round((double) (mx - d.trackX()) * range / d.trackW());
        d.setting().set(v);
    }

    private static void clampScroll() {
        int max = Math.max(0, contentHeight - maxVisible());
        scroll = Math.max(0, Math.min(max, scroll));
    }

    /** Visible height for scrolling: everything below the horizontal tab strip. */
    private static int maxVisible() {
        return canvas == null ? 400 : canvas.getHeight() - TABS_H;
    }

    // ------------------------------------------------------------------ the vertical icon strip

    private static void drawIconStrip(Graphics2D g, int clientH) {
        int x = BODY_W;
        g.setColor(Theme.SURFACE);
        g.fillRect(x, 0, TAB_W, clientH);

        Tab[] tabs = { Tab.PLUGINS, Tab.PROFILES, Tab.DEBUG };
        for (int i = 0; i < tabs.length; i++) {
            Tab t = tabs[i];
            int y = i * TAB_W;
            boolean current = t == active && selected == null;
            if (current) {
                g.setColor(Theme.BACKGROUND);
                g.fillRect(x, y, TAB_W, TAB_W);
                g.setColor(Theme.RL_ORANGE);
                g.fillRect(x, y, 3, TAB_W);
            }
            g.setColor(current ? Theme.RL_LABEL : Theme.TEXT_DIM);
            tabIcon(g, x + 10, y + 10, t);
        }
    }

    /** Small vector glyphs -- no icon pack, no font dependency. */
    private static void tabIcon(Graphics2D g, int x, int y, Tab t) {
        switch (t) {
            case PLUGINS -> {                       // a list: three lines of shrinking length
                g.fillRect(x, y + 1, 16, 2);
                g.fillRect(x, y + 7, 12, 2);
                g.fillRect(x, y + 13, 8, 2);
            }
            case PROFILES -> {                      // a person: head and shoulders
                g.drawOval(x + 5, y, 6, 6);
                g.drawArc(x + 1, y + 8, 14, 10, 0, 180);
            }
            case DEBUG -> {                         // a pulse: flat, spike, flat
                int[] ys = { 8, 8, 2, 14, 8, 8 };
                for (int i = 0; i < ys.length - 1; i++) {
                    g.drawLine(x + i * 3, y + ys[i], x + (i + 1) * 3, y + ys[i + 1]);
                }
            }
        }
    }

    // The list comes from KewlKlient via a setter rather than a getter that would create a cycle in
    // the class graph; one field, set once at start-up. Declared defaults need no capture here:
    // Setting.defaultValue() is the Setting's own record of what it was built with, which is also
    // what the launcher's reset edits (bridge kinds 4/5) land on, so both panels reset to the same
    // values no matter how late the panel was built or what a profile applied first.
    private static List<Plugin> pluginList = List.of();

    public static void setPlugins(List<Plugin> plugins) {
        pluginList = plugins;
    }

    private static List<Plugin> plugins() { return pluginList; }
}
