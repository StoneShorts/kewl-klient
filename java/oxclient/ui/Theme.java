package oxclient.ui;

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

    // -- the 0xClient palette: near-black surfaces, one signal green (the logo's), nothing else
    //    saturated. The green is for what is SELECTED or ON; everything at rest is a shade of black.
    public static final Color BRAND      = new Color(64, 246, 80);   // the logo green
    public static final Color BRAND_DIM  = new Color(38, 150, 50);   // the same green, held down / de-emphasised

    // -- the panel
    public static final Color BACKGROUND = new Color(8, 10, 9);
    public static final Color SURFACE    = new Color(16, 19, 17);
    public static final Color SURFACE_HI = new Color(24, 29, 25);
    public static final Color BORDER     = new Color(34, 44, 36);
    public static final Color TEXT       = new Color(228, 236, 230);
    public static final Color TEXT_DIM   = new Color(132, 146, 136);
    public static final Color ACCENT     = BRAND;
    public static final Color ON         = BRAND;
    public static final Color OFF        = new Color(70, 80, 72);
    public static final Color WARN       = new Color(255, 110, 96);

    // -- The panel pieces that copy RuneLite's side panel view for view keep RuneLite's NAMES for
    //    the colours (so a diff against upstream still reads), but take 0xClient's VALUES: the
    //    brand green where RuneLite has its orange, and the deeper blacks of the logo.
    public static final Color RL_ORANGE  = BRAND;                    // was BRAND_ORANGE: active tab, section names, hover
    public static final Color RL_TAB     = new Color(4, 5, 4);       // was DARKER_GRAY_COLOR: tab backgrounds
    public static final Color RL_TAB_HI  = new Color(22, 28, 23);    // was DARKER_GRAY_HOVER_COLOR: tab hover
    public static final Color RL_DIVIDER = new Color(36, 46, 38);    // was MEDIUM_GRAY_COLOR: 1px section dividers
    public static final Color RL_LABEL   = Color.WHITE;              // plugin and setting names are pure white

    // -- the overlay. Semi-transparent so the game stays readable underneath.
    public static final Color HUD_BACK   = new Color(6, 8, 6, 200);
    public static final Color HUD_BORDER = new Color(64, 246, 80, 150);

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
