// The decoding behind the game-menu probe, with no game behind it.
//
// MenuPopup.GameMenuProbe is the thing that will say whether the menu layout derived from
// build/game/osclient.exe (see MenuPopup's header: entry stride 0x150, text as an NxtString at +0x50
// with its flag at +0x67, a second string at +0xB8, an int32 at +0x134) is real. It says so by
// finding a menu entry's text with Natives.findString and checking that the bytes at hit-0x50 decode
// BACK to what was searched for. That check is the whole probe: a parser bug in it either invents a
// confirmation that is not there or hides one that is, and both send whoever writes the dumpMenu
// native in the wrong direction. So the parser is pinned here, against dumps built by hand.
//
// Everything under test is pure: hex string in, value out. The scan itself needs the game and is not
// tested here.
package kewl.rl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class GameMenuProbeTest
{
	private static final int STRIDE = MenuPopup.GameMenuProbe.ENTRY_STRIDE;
	private static final int TEXT = MenuPopup.GameMenuProbe.ENTRY_TEXT;
	private static final int TEXT2 = MenuPopup.GameMenuProbe.ENTRY_TEXT2;
	private static final int ID = MenuPopup.GameMenuProbe.ENTRY_ID;

	/** A blank 336-byte entry, as Natives.peek would hand it back: lower-case hex, no separators. */
	private static byte[] entry()
	{
		return new byte[STRIDE];
	}

	private static String hex(byte[] buf)
	{
		StringBuilder sb = new StringBuilder();
		for (byte b : buf)
		{
			sb.append(String.format("%02x", b & 0xFF));
		}
		return sb.toString();
	}

	/**
	 * Write an NxtString the way the client stores a short one: the characters in place, and
	 * {@code 0x17 - length} in the flag byte at {@code +0x17}. This is the shape the probe's
	 * self-test depends on -- if the client did not store menu text this way, findString's hit would
	 * not be at {@code entry + 0x50} and nothing would ever confirm.
	 */
	private static void putInline(byte[] buf, int off, String s)
	{
		for (int i = 0; i < s.length(); i++)
		{
			buf[off + i] = (byte) s.charAt(i);
		}
		buf[off + 0x17] = (byte) (0x17 - s.length());
	}

	/** Write an NxtString the way the client stores a long one: a heap pointer, a length, flag bit 0x80. */
	private static void putHeap(byte[] buf, int off, long ptr, long len)
	{
		for (int i = 0; i < 8; i++)
		{
			buf[off + i] = (byte) (ptr >>> (8 * i));
			buf[off + 8 + i] = (byte) (len >>> (8 * i));
		}
		buf[off + 0x17] = (byte) 0x80;
	}

	private static void putInt(byte[] buf, int off, int v)
	{
		for (int i = 0; i < 4; i++)
		{
			buf[off + i] = (byte) (v >>> (8 * i));
		}
	}

	// -- the self-test the whole probe rests on -----------------------------------------------------

	/**
	 * THE CONFIRMATION. An entry whose text sits inline at +0x50 decodes back to exactly the needle,
	 * which is what lets the probe say "this address really is a menu entry" about a findString hit.
	 */
	@Test
	public void anInlineEntryTextDecodesBackToTheNeedle()
	{
		byte[] e = entry();
		putInline(e, TEXT, "Walk here");

		assertEquals("Walk here", MenuPopup.GameMenuProbe.inlineText(hex(e), TEXT));
	}

	/**
	 * THE REFUTATION, which matters just as much: the probe must not confirm on a hit that is merely
	 * NEAR an entry. findString scans all writable memory and the JVM's own heap holds copies of
	 * these very strings, so a decode that accepted loosely would report a layout that does not
	 * exist. Text one byte off from +0x50 leaves a flag byte that is not a length, and that is a
	 * refusal, not a shifted string.
	 */
	@Test
	public void textAtTheWrongOffsetDoesNotDecode()
	{
		byte[] e = entry();
		putInline(e, TEXT + 1, "Walk here");

		assertEquals("", MenuPopup.GameMenuProbe.inlineText(hex(e), TEXT));
	}

	/** A zero flag byte is the client's encoding for a FULL-length inline string, not for "no string". */
	@Test
	public void aZeroFlagByteMeansAFullLengthInlineString()
	{
		byte[] e = entry();
		for (int i = 0; i < 9; i++)
		{
			e[TEXT + i] = (byte) "Walk here".charAt(i);
		}
		// No flag byte written, so +0x67 is 0, which claims a 23-byte inline string. The decode reads
		// 23 bytes and stops at the first NUL, so it comes back "Walk here" -- and the caller's
		// equals() against the needle is what makes that a pass. The point of this test is that a
		// zero flag is NOT treated as "no string": it is the client's own encoding for a full-length
		// one, and nxtString() in the DLL reads it the same way.
		assertEquals("Walk here", MenuPopup.GameMenuProbe.inlineText(hex(e), TEXT));
	}

	/** The flag's top bit means the characters are on the heap; there is nothing inline to read. */
	@Test
	public void aHeapStringHasNoInlineText()
	{
		byte[] e = entry();
		putHeap(e, TEXT, 0x7FF6_1234_5678L, 40);

		assertTrue(MenuPopup.GameMenuProbe.heapStored(hex(e), TEXT));
		assertEquals("", MenuPopup.GameMenuProbe.inlineText(hex(e), TEXT));
		assertEquals(0x7FF6_1234_5678L, MenuPopup.GameMenuProbe.hexLong(hex(e), TEXT));
		assertEquals(40, MenuPopup.GameMenuProbe.hexLong(hex(e), TEXT + 8));
	}

	@Test
	public void anInlineStringIsNotReportedAsHeapStored()
	{
		byte[] e = entry();
		putInline(e, TEXT, "Cancel");

		assertFalse(MenuPopup.GameMenuProbe.heapStored(hex(e), TEXT));
	}

	/** Both string slots are read independently: settling which is option and which is target is the probe's job. */
	@Test
	public void bothStringSlotsAreReadable()
	{
		byte[] e = entry();
		putInline(e, TEXT, "Attack");
		putInline(e, TEXT2, "Goblin");

		assertEquals("Attack", MenuPopup.GameMenuProbe.inlineText(hex(e), TEXT));
		assertEquals("Goblin", MenuPopup.GameMenuProbe.inlineText(hex(e), TEXT2));
	}

	// -- the numbers ---------------------------------------------------------------------------------

	/** +0x134 is little-endian, and the client's own "not a real entry" sentinel must survive the trip. */
	@Test
	public void theIdFieldIsLittleEndianAndCarriesTheSentinel()
	{
		byte[] e = entry();
		putInt(e, ID, 0x7FFFFFFE);
		assertEquals(0x7FFFFFFE, MenuPopup.GameMenuProbe.hexInt(hex(e), ID));

		putInt(e, ID, -1);
		assertEquals(-1, MenuPopup.GameMenuProbe.hexInt(hex(e), ID));

		putInt(e, ID, 0x01020304);
		assertEquals(0x01020304, MenuPopup.GameMenuProbe.hexInt(hex(e), ID));
	}

	@Test
	public void bytesReadBackAtTheirOffsets()
	{
		byte[] e = entry();
		e[MenuPopup.GameMenuProbe.ENTRY_KIND] = 2;
		e[MenuPopup.GameMenuProbe.ENTRY_KIND2] = (byte) 0xFF;

		String h = hex(e);
		assertEquals(2, MenuPopup.GameMenuProbe.hexByte(h, MenuPopup.GameMenuProbe.ENTRY_KIND));
		assertEquals(255, MenuPopup.GameMenuProbe.hexByte(h, MenuPopup.GameMenuProbe.ENTRY_KIND2));
	}

	/**
	 * A short or empty dump is what peek() returns for an address that is not readable, and the probe
	 * walks addresses it has NOT established are mapped (the neighbours either side of a confirmed
	 * entry). Every read past the end must be -1 rather than an exception, or an unmapped neighbour
	 * takes the frame down instead of ending the walk.
	 */
	@Test
	public void readingPastAShortDumpIsRefusedNotThrown()
	{
		assertEquals(-1, MenuPopup.GameMenuProbe.hexByte("", 0));
		assertEquals(-1, MenuPopup.GameMenuProbe.hexByte("aabb", 2));
		assertEquals(-1, MenuPopup.GameMenuProbe.hexByte("aabb", -1));
		assertEquals(0, MenuPopup.GameMenuProbe.hexInt("aabb", 0));
		assertEquals(0, MenuPopup.GameMenuProbe.hexLong("aabbccdd", 0));
		assertEquals("", MenuPopup.GameMenuProbe.inlineText("aabb", 0));
		assertEquals("", MenuPopup.GameMenuProbe.slice("", 0, 16));
	}

	/** Non-hex input (a peek() that returned something unexpected) is refused, not parsed. */
	@Test
	public void nonHexIsRefused()
	{
		assertEquals(-1, MenuPopup.GameMenuProbe.hexByte("zz", 0));
	}

	// -- printing ------------------------------------------------------------------------------------

	/**
	 * The dump is read by a human against the offsets in MenuPopup's header, so the bytes are spaced
	 * and start where they are asked to start.
	 */
	@Test
	public void sliceIsSpacedHexFromTheGivenOffset()
	{
		assertEquals("aa bb cc", MenuPopup.GameMenuProbe.slice("aabbccdd", 0, 3));
		assertEquals("cc dd", MenuPopup.GameMenuProbe.slice("aabbccdd", 2, 8));
	}

	/**
	 * Text stops at the NUL so an inline buffer's tail never leaks, and anything unprintable becomes
	 * '.' -- a wrong offset produces garbage, and garbage must not scramble the log it is being read
	 * out of. The game's own {@code <col=...>} markup is printable and survives.
	 */
	@Test
	public void asciiStopsAtNulAndTamesGarbage()
	{
		assertEquals("Hi", MenuPopup.GameMenuProbe.ascii("486900ff41"));
		assertEquals("<col=ff9040>", MenuPopup.GameMenuProbe.ascii("3c636f6c3d6666393034303e"));
		assertEquals("..", MenuPopup.GameMenuProbe.ascii("01ff00"));
	}

	// -- arming --------------------------------------------------------------------------------------

	/**
	 * The probe scans all writable memory, which is not free and which has crashed this game before
	 * in another probe's hands, so it is opt-in. "1" means "use the rows the client always builds";
	 * anything else lets a user who can SEE a row we did not guess probe with its exact text.
	 */
	@Test
	public void theDefaultNeedlesAreTheRowsTheClientAlwaysBuilds()
	{
		List<String> defaults = MenuPopup.GameMenuProbe.needlesFrom("1");
		assertEquals(List.of("Walk here", "Examine", "Cancel"), defaults);
		assertEquals(defaults, MenuPopup.GameMenuProbe.needlesFrom("true"));
		assertEquals(defaults, MenuPopup.GameMenuProbe.needlesFrom("  "));
	}

	@Test
	public void customNeedlesAreSplitTrimmedAndDeduplicated()
	{
		assertEquals(List.of("Attack", "Trade with"),
			MenuPopup.GameMenuProbe.needlesFrom(" Attack , Trade with ,Attack"));
	}

	/**
	 * Two refusals, both of which would otherwise waste the one scan the session gets: findString
	 * rejects a needle under three characters (it would match everywhere), and a needle over 23
	 * characters cannot be stored inline, so its findString hit would be a heap buffer rather than
	 * entry+0x50 and the self-test could never pass however right the layout is.
	 */
	@Test
	public void needlesThatCouldNeverConfirmAreDropped()
	{
		assertEquals(List.of(), MenuPopup.GameMenuProbe.needlesFrom("Go"));
		assertEquals(List.of(), MenuPopup.GameMenuProbe.needlesFrom(
			"Walk here to the place that is much too far away"));
		assertEquals(List.of("Examine"),
			MenuPopup.GameMenuProbe.needlesFrom("Go,Examine,aaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
	}
}
