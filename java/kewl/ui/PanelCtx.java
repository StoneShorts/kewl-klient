package kewl.ui;

import java.awt.Color;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-frame drawing context handed to every panel view: the graphics object, the mouse position, the
 * drawable bounds, and the hit regions being rebuilt.
 *
 * <p>The views are stateless -- all the state that survives a frame lives in {@link SidePanel} -- so a
 * view is just "draw, and register what you drew as clickable". Hit regions are rebuilt on every render
 * like the pixels are, which means a click is answered with the layout the user was actually looking at
 * one frame ago. That is the one-frame latency the panel has always had, and at 30 fps nobody can see
 * it.</p>
 */
final class PanelCtx {

    /** A clickable rectangle. First hit in the list wins, so specific controls are registered before the row they sit in. */
    record Hit(int x, int y, int w, int h, Runnable action) {
        boolean hits(int px, int py) { return px >= x && px < x + w && py >= y && py < y + h; }
    }

    final Graphics2D g;
    final int left, right;                    // drawable x range: PAD .. BODY_W - PAD
    final int top, bottom;                    // drawable y range, i.e. below the tab strip

    // Where the mouse is, in panel coordinates. Written by SidePanel.mouse between frames.
    int mx = -1, my = -1;

    /** Left clicks. Cleared and refilled every frame by SidePanel, which owns the lists between frames. */
    final List<Hit> hits;
    /** Right clicks -- the config rows use it for RuneLite's per-item "Reset" menu entry. */
    final List<Hit> rightHits;
    /** Tooltips queued by the views and drawn last, so nothing overdraws them. */
    private final List<String[]> tooltips = new ArrayList<>();

    PanelCtx(Graphics2D g, int bodyW, int bodyH, List<Hit> hits, List<Hit> rightHits) {
        this.g = g;
        this.left = SidePanel.PAD;
        this.right = bodyW - SidePanel.PAD;
        this.top = SidePanel.TABS_H;
        this.bottom = bodyH;
        this.hits = hits;
        this.rightHits = rightHits;
    }

    void hit(int x, int y, int w, int h, Runnable action) { hits.add(new Hit(x, y, w, h, action)); }

    void right(int x, int y, int w, int h, Runnable action) { rightHits.add(new Hit(x, y, w, h, action)); }

    /** True when the mouse is inside the rectangle. */
    boolean hover(int x, int y, int w, int h) { return mx >= x && mx < x + w && my >= y && my < y + h; }

    /** True when the row at [y, y+h) is at least partly inside the drawable area -- the cull test every view uses. */
    boolean visible(int y, int h) { return y + h >= top && y <= bottom; }

    /** Queue a tooltip near the mouse, RuneLite's answer for descriptions that have no room on the row. */
    void tooltip(String text) { if (text != null && !text.isEmpty()) tooltips.add(new String[]{text}); }

    /** Draw the queued tooltips. Called once, after every view, so a tooltip is never overdrawn. */
    void drawTooltips() {
        for (String[] tt : tooltips) {
            String text = tt[0];
            g.setFont(Theme.UI);
            int w = g.getFontMetrics().stringWidth(text) + 12;
            int x = Math.min(mx + 10, right - w);
            int y = my + 16;
            g.setColor(Theme.alpha(Theme.HUD_BACK, 240));
            g.fillRoundRect(x, y, w, 16, 4, 4);
            g.setColor(Theme.HUD_BORDER);
            g.drawRoundRect(x, y, w, 16, 4, 4);
            g.setColor(Theme.TEXT);
            g.drawString(text, x + 6, y + 12);
        }
        tooltips.clear();
    }

    /** Fill a rounded control surface, lighter under the mouse -- the shared hover affordance. */
    void surface(int x, int y, int w, int h, boolean hovered) {
        g.setColor(hovered ? Theme.SURFACE_HI : Theme.SURFACE);
        g.fillRoundRect(x, y, w, h, 6, 6);
    }
}
