package kewl.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.awt.Color;

import org.junit.Test;

import kewl.Plugin;
import kewl.config.Setting;

/**
 * The value codec the profile store persists through. The colour case is the one that has bitten:
 * the encoder writes {@link Color#getRGB}, which is ARGB (alpha in the TOP byte), and a decoder that
 * assumes any other order round-trips a stored alpha into the blue channel -- or, as the length
 * checks stood for a while, fails to decode an alpha colour at all and silently reverts the setting
 * to its default on every profile apply. These tests pin the round trip, which is the actual
 * contract: whatever encode writes, decode must read back as the same colour.
 */
public class SettingCodecTest
{
	/** One colour setting, so the codec has something real to work on. */
	private static final class ColourPlugin extends Plugin
	{
		@Override public String name() { return "ColourPlugin"; }
	}

	private static Setting colourSetting(Color def)
	{
		ColourPlugin p = new ColourPlugin();
		return p.config.colour("tint", "Tint", "", def);
	}

	private static void roundTrips(Color c)
	{
		Setting s = colourSetting(c);
		Object encoded = SettingCodec.encode(s);
		assertEquals("encode(" + c + ") must decode back to the same colour", c,
				SettingCodec.decode(s, encoded));
	}

	@Test
	public void opaqueColoursRoundTrip()
	{
		roundTrips(new Color(0x5adc78));
		roundTrips(Color.WHITE);
		roundTrips(new Color(0x000000));
	}

	@Test
	public void alphaColoursRoundTrip()
	{
		// Alpha in every byte position, not just the convenient ones: a decoder that reads the same
		// byte for blue and alpha (or drops the alpha byte) fails exactly one of these.
		roundTrips(new Color(0, 255, 0, 128));      // shortestpath's colourTransports default
		roundTrips(new Color(255, 0, 0, 1));
		roundTrips(new Color(0, 0, 255, 254));
		roundTrips(new Color(0x80, 0x40, 0xC0, 0x20));
	}

	@Test
	public void alphaIsStoredInTheTopByte()
	{
		// Pin the byte order itself, not just the round trip: two wrongs can cancel in a round trip
		// (swap alpha and blue twice and you are back where you started), and the stored string is
		// also what a human reads out of config.json. Color.getRGB is ARGB, so alpha goes first --
		// the same order Color.decode parses.
		Setting s = colourSetting(new Color(0, 255, 0, 128));
		assertEquals("#8000ff00", SettingCodec.encode(s));
	}

	@Test
	public void secretTextRoundTripsExactlyLikeText()
	{
		// A secret is a display concern only: the codec must treat it as the TEXT it is, so the value
		// persists and comes back verbatim (including characters a masked field never showed).
		ColourPlugin p = new ColourPlugin();
		Setting s = p.config.secret("pw", "Password", "", "");
		s.set("Qq7#with spaces and =signs");
		Object encoded = SettingCodec.encode(s);
		assertEquals("Qq7#with spaces and =signs", encoded);
		assertEquals("Qq7#with spaces and =signs", SettingCodec.decode(s, encoded));
		assertEquals("an empty secret persists as the empty string, not as nothing", "",
				SettingCodec.encode(p.config.secret("pw2", "Password", "", "")));
	}

	@Test
	public void storedStringsDecodeDirectly()
	{
		Setting s = colourSetting(Color.WHITE);
		assertEquals(new Color(0, 255, 0, 128), SettingCodec.decode(s, "#8000ff00"));
		assertEquals(new Color(0x5adc78), SettingCodec.decode(s, "#5adc78"));
	}

	@Test
	public void junkStaysNull()
	{
		Setting s = colourSetting(Color.WHITE);
		assertNull("not a colour", SettingCodec.decode(s, "red"));
		assertNull("the wrong digit count", SettingCodec.decode(s, "#5adc7"));
		assertNull("too long", SettingCodec.decode(s, "#5adc7800ff"));
	}

	@Test
	public void theOtherKindsRoundTripToo()
	{
		ColourPlugin p = new ColourPlugin();
		Setting flag = p.config.bool("flag", "Flag", "", false);
		Setting radius = p.config.number("radius", "Radius", "", 5, 0, 20);
		Setting note = p.config.text("note", "Note", "", "");

		flag.set(true);
		radius.set(12);
		note.set("stairs");

		assertEquals(Boolean.TRUE, SettingCodec.decode(flag, SettingCodec.encode(flag)));
		assertEquals(12, SettingCodec.decode(radius, SettingCodec.encode(radius)));
		assertEquals("stairs", SettingCodec.decode(note, SettingCodec.encode(note)));
	}
}
