// The registry that replaced the silent zeros.
//
// Background (2026-09-06): "the camera shows zeros" was a true bug report that nothing in the client
// could turn into an actionable sentence, because a hardcoded `return 0` is indistinguishable from a
// camera pointing north. ShimSupport is the one mechanism that makes a placeholder say so. What has
// to hold for it to be worth having, and is therefore tested here:
//
//   * it never spams -- a getter called per NPC per frame logs once per session, not once per call;
//   * it only ever knows about accessors that were actually CALLED, which is what keeps the status
//     line short enough to put in a control panel;
//   * a caller can ask about ONE accessor by name and get the reason or a clean null, so "is this
//     zero real?" has an answer;
//   * it never throws, whatever it is handed, because a diagnostic that can break a frame is worse
//     than no diagnostic at all.
package net.runelite.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ShimSupportTest
{
	@Before
	public void clean()
	{
		ShimSupport.reset();
		ShimSupport.setLogging(false);
	}

	@After
	public void restore()
	{
		ShimSupport.reset();
	}

	@Test
	public void anUncalledAccessorIsNotAGap()
	{
		// The registry is a record of what the running plugins actually hit, not a to-do list of the
		// whole shim. Nothing has been called, so there is nothing to report and the status line is
		// EMPTY -- a panel can print it unconditionally and show nothing on a clean session.
		assertTrue(ShimSupport.gaps().isEmpty());
		assertEquals("", ShimSupport.status());
		assertFalse(ShimSupport.isGap("Client.getCameraPitch"));
		assertNull(ShimSupport.reasonFor("Client.getCameraPitch"));
	}

	@Test
	public void oneAccessorIsRecordedOnceHoweverOftenItIsCalled()
	{
		// Actor.getCombatLevel is called once per NPC per frame by the highlight overlays. Recording
		// the same gap a thousand times must leave one entry and, below, one log line.
		for (int i = 0; i < 1000; i++)
		{
			ShimSupport.note("Actor.getCombatLevel", ShimSupport.Kind.NEEDS_OFFSET, "reads 0");
		}
		assertEquals(1, ShimSupport.gaps().size());
	}

	@Test
	public void theFirstReasonWinsSoTheRecordIsStable()
	{
		// Two call sites in one method would otherwise make the report flip between runs. First one
		// in is what the session reports.
		ShimSupport.note("Client.isResized", ShimSupport.Kind.NEEDS_OFFSET, "the real reason");
		ShimSupport.note("Client.isResized", ShimSupport.Kind.UNSUPPORTABLE, "a later, different one");
		assertEquals("the real reason", ShimSupport.reasonFor("Client.isResized"));
		assertEquals(ShimSupport.Kind.NEEDS_OFFSET, ShimSupport.gaps().get(0).kind());
	}

	@Test
	public void logsOncePerAccessorPerSession()
	{
		// The point of the whole design: a per-frame getter must cost one line, and a SECOND distinct
		// accessor must still get its own. Captured rather than asserted on a boolean, because "is it
		// quiet" is the property that matters and it is only observable at the stream.
		PrintStream out = System.out;
		ByteArrayOutputStream captured = new ByteArrayOutputStream();
		try
		{
			System.setOut(new PrintStream(captured, true));
			ShimSupport.setLogging(true);
			for (int i = 0; i < 50; i++)
			{
				ShimSupport.note("Player.isFriend", ShimSupport.Kind.NEEDS_OFFSET, "reads false");
				ShimSupport.note("Player.getTeam", ShimSupport.Kind.NEEDS_OFFSET, "reads 0");
			}
		}
		finally
		{
			System.setOut(out);
		}
		String text = captured.toString();
		assertEquals("one line per accessor, not per call", 1, countOf(text, "Player.isFriend"));
		assertEquals(1, countOf(text, "Player.getTeam"));
		assertTrue("the line must carry the reason, not just the name: " + text,
			text.contains("reads false"));
	}

	@Test
	public void theStatusLineNamesTheGapsRatherThanCountingThem()
	{
		// "3 shim gaps" tells a user they have a problem and not which. Names tell them which overlay
		// is the one drawing nonsense. The class prefix is dropped: it is noise in a panel line.
		ShimSupport.note("Actor.getCombatLevel", ShimSupport.Kind.NEEDS_OFFSET, "reads 0");
		ShimSupport.note("Client.getMinimapZoom", ShimSupport.Kind.UNSUPPORTABLE, "placeholder 4.0");
		String status = ShimSupport.status();
		assertTrue(status, status.contains("getCombatLevel"));
		assertTrue(status, status.contains("getMinimapZoom"));
		assertFalse("the class name is noise here: " + status, status.contains("Actor."));
		assertTrue(status, status.contains("2 stubs hit"));
	}

	@Test
	public void theStatusLineStaysShortWhenEverythingIsStubbed()
	{
		// It goes in a control-panel row, so a session that has hit twenty placeholders must not
		// produce a twenty-name line. The overflow is counted, not listed.
		for (int i = 0; i < 20; i++)
		{
			ShimSupport.note("Fake.accessor" + i, ShimSupport.Kind.NEEDS_OFFSET, "reads 0");
		}
		String status = ShimSupport.status(3);
		assertTrue(status, status.contains("+17 more"));
		assertTrue("a panel row is ~60 chars: " + status, status.length() < 80);
	}

	@Test
	public void oneStubReadsAsSingular()
	{
		ShimSupport.note("Actor.isDead", ShimSupport.Kind.NEEDS_OFFSET, "reads false");
		assertTrue(ShimSupport.status(), ShimSupport.status().contains("1 stub hit"));
	}

	@Test
	public void theReportGroupsByWhatWouldFixEachGap()
	{
		// The three kinds are three different pieces of work -- run the deob, bundle a cache table,
		// or accept it -- so the summary that goes in the log groups by them. A reader looking for
		// "what can I actually fix" should not have to sort it themselves.
		ShimSupport.note("Actor.getCombatLevel", ShimSupport.Kind.NEEDS_OFFSET, "reads 0");
		ShimSupport.note("ItemDefinition.isStackable", ShimSupport.Kind.NEEDS_CACHE_DATA, "reads false");
		ShimSupport.note("WorldMap.getWorldMapZoom", ShimSupport.Kind.UNSUPPORTABLE, "placeholder");
		String report = ShimSupport.report();
		assertTrue(report, report.contains("3 accessor(s)"));
		for (ShimSupport.Kind kind : ShimSupport.Kind.values())
		{
			assertTrue("missing heading for " + kind + ": " + report, report.contains(kind.phrase()));
		}
		assertTrue(report, report.indexOf("getCombatLevel") < report.indexOf("isStackable"));
		assertTrue(report, report.indexOf("isStackable") < report.indexOf("getWorldMapZoom"));
	}

	@Test
	public void anEmptyReportSaysSoRatherThanPrintingAHeaderWithNothingUnderIt()
	{
		String report = ShimSupport.report();
		assertNotNull(report);
		assertTrue(report, report.contains("nothing stubbed"));
	}

	@Test
	public void aBadCallIsIgnoredRatherThanThrown()
	{
		// This runs inside overlay renders on the frame thread. A NullPointerException out of the
		// diagnostic would take down the frame it was supposed to explain.
		ShimSupport.note(null, ShimSupport.Kind.NEEDS_OFFSET, "reads 0");
		ShimSupport.note("Fake.accessor", null, "reads 0");
		ShimSupport.note("Fake.nullReason", ShimSupport.Kind.NEEDS_OFFSET, null);
		assertEquals(1, ShimSupport.gaps().size());
		assertEquals("", ShimSupport.reasonFor("Fake.nullReason"));
	}

	private static int countOf(String haystack, String needle)
	{
		int n = 0;
		for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1))
		{
			n++;
		}
		return n;
	}
}
