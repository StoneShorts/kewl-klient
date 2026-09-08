package kewl.ui;

import java.awt.Graphics2D;
import java.util.List;

import kewl.profile.Profile;
import kewl.profile.ProfileManager;

/**
 * The Profiles tab: create a profile from how the client is set up right now, switch between them,
 * delete one. RuneLite's profile panel talks to ConfigManager; this one talks to
 * {@link ProfileManager}, which is the same store the ImGui launcher's PROFILE_* edits land in --
 * two views, one state, and neither keeps its own copy. Switching here persists (debounced, atomic
 * writes) and applies the target profile's enabled map and settings through {@code Setting.set}, so
 * plugins hear the changes exactly as if each control had been clicked.
 *
 * <p>Profile names are generated ("profile 1", ...) because typing a name needs a keyboard the panel
 * does not have yet; renaming is one more thing blocked on the same gap, and the launcher's
 * PROFILE_RENAME edit covers it in the meantime.</p>
 */
final class ProfilesView {

    private ProfilesView() {}

    private static final int ROW_H = 22;
    private static final int GAP = 5;

    static int draw(PanelCtx ctx, int y) {
        y = Widgets.title(ctx, ctx.left, y, "Profiles",
                "persisted with the client's data; click to switch, cross to delete");

        int x = ctx.left;
        int w = ctx.right - x;

        ProfileManager profiles = ProfileManager.instance();
        if (profiles == null) {
            ctx.g.setFont(Theme.UI);
            ctx.g.setColor(Theme.TEXT_DIM);
            ctx.g.drawString("(no profile store)", x, y + 12);
            return y + 24;
        }

        Widgets.button(ctx, x, y, w, 22, "save current as " + nextName(profiles),
                () -> profiles.create(nextName(profiles)));
        y += 28;

        List<Profile> list = profiles.profiles();
        int active = profiles.activeIndex();
        for (int i = 0; i < list.size(); i++) {
            if (!ctx.visible(y, ROW_H)) { y += ROW_H + GAP; continue; }

            Profile p = list.get(i);
            boolean is_active = i == active;
            boolean hover = ctx.hover(x, y, w, ROW_H);
            ctx.g.setFont(Theme.UI);
            ctx.g.setColor(is_active ? Theme.RL_ORANGE : hover ? Theme.RL_LABEL : Theme.TEXT_DIM);
            ctx.g.drawString(Widgets.clip(ctx.g, (is_active ? "* " : "") + p.name(), w - 30), x + 2, y + 14);
            Widgets.cross(ctx.g, ctx.right - 12, y + 7, hover ? Theme.WARN : Theme.TEXT_DIM);

            // A click on the row switches to it; the cross deletes it. First match wins, so the cross
            // is registered before the row.
            int n = i;
            ctx.hit(ctx.right - 18, y, 18, ROW_H, () -> profiles.delete(n));
            ctx.hit(x, y, w - 22, ROW_H, () -> profiles.switchTo(n));
            if (hover) ctx.tooltip(is_active ? "active profile" : "click to switch to " + p.name());
            y += ROW_H + GAP;
        }
        return y + SidePanel.PAD;
    }

    /** The name the next create will start from; the store de-collides it if it is somehow taken. */
    private static String nextName(ProfileManager profiles) {
        for (int i = 1; ; i++) {
            String name = "profile " + i;
            boolean taken = false;
            for (Profile p : profiles.profiles()) if (p.name().equalsIgnoreCase(name)) taken = true;
            if (!taken) return name;
        }
    }
}
