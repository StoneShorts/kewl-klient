package kewl.ui;

import java.awt.Color;
import java.awt.Font;

/**
 * The colours and fonts, in one place, so the client looks like one thing rather than nine.
 *
 * <p>Change them here and both the control panel and every overlay follow. That is the entire reason
 * this class exists -- the first version of this client had colours written inline in six files and
 * restyling it meant finding all six.</p>
 */
public final class Theme {

    private Theme() {}

    // -- the panel
    public static final Color BACKGROUND = new Color(28, 28, 32);
    public static final Color SURFACE    = new Color(38, 38, 44);
    public static final Color SURFACE_HI = new Color(50, 50, 58);
    public static final Color BORDER     = new Color(58, 58, 66);
    public static final Color TEXT       = new Color(226, 226, 232);
    public static final Color TEXT_DIM   = new Color(150, 150, 160);
    public static final Color ACCENT     = new Color(120, 190, 255);
    public static final Color ON         = new Color(110, 220, 140);
    public static final Color OFF        = new Color(120, 120, 130);
    public static final Color WARN       = new Color(255, 140, 120);

    // -- RuneLite's ColorScheme, for the panel pieces that copy it view for view. Kept beside the
    //    palette above rather than replacing it: ACCENT and the HUD colours are shared with the
    //    overlays, and recolouring those was not the point of imitating the side panel.
    public static final Color RL_ORANGE  = new Color(220, 138, 0);   // BRAND_ORANGE: active tab, section names, hover
    public static final Color RL_TAB     = new Color(30, 30, 30);    // DARKER_GRAY_COLOR: tab backgrounds
    public static final Color RL_TAB_HI  = new Color(60, 60, 60);    // DARKER_GRAY_HOVER_COLOR: tab hover
    public static final Color RL_DIVIDER = new Color(77, 77, 77);    // MEDIUM_GRAY_COLOR: 1px section dividers
    public static final Color RL_LABEL   = Color.WHITE;              // plugin and setting names are pure white

    // -- the overlay. Semi-transparent so the game stays readable underneath.
    public static final Color HUD_BACK   = new Color(20, 20, 24, 190);
    public static final Color HUD_BORDER = new Color(90, 90, 105, 190);

    /**
     * Load the fonts by file rather than by name. Asking for "Segoe UI" goes through the platform's
     * font registry, and under Wine that registry can point at fonts that are not installed, which
     * makes every glyph in the panel render as a box. The file is right there in both cases -- a real
     * Windows machine and a Wine prefix both have segoeui.ttf in the same place -- so read it directly
     * and skip the registry entirely.
     */
    private static Font fromFile(String[] paths, String fallbackName) {
        for (String p : paths) {
            try {
                return Font.createFont(Font.TRUETYPE_FONT, new java.io.File(p));
            } catch (Throwable ignored) {
                // try the next candidate
            }
        }
        return new Font(fallbackName, Font.PLAIN, 12);
    }

    private static final Font SANS = fromFile(new String[]{
            "C:\\Windows\\Fonts\\segoeui.ttf",
            "C:\\Windows\\Fonts\\arial.ttf",
            "Z:\\usr\\share\\fonts\\truetype\\dejavu\\DejaVuSans.ttf",
    }, Font.SANS_SERIF);

    private static final Font MONO_BASE = fromFile(new String[]{
            "C:\\Windows\\Fonts\\consola.ttf",
            "C:\\Windows\\Fonts\\cour.ttf",
            "Z:\\usr\\share\\fonts\\truetype\\dejavu\\DejaVuSansMono.ttf",
    }, Font.MONOSPACED);

    public static final Font UI      = SANS.deriveFont(Font.PLAIN, 12f);
    public static final Font UI_BOLD = SANS.deriveFont(Font.BOLD, 12f);
    public static final Font TITLE   = SANS.deriveFont(Font.BOLD, 15f);
    public static final Font MONO    = MONO_BASE.deriveFont(Font.PLAIN, 12f);

    /** The same colour at a different opacity. Handy for "fill faintly, outline solid". */
    public static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, a)));
    }
}
