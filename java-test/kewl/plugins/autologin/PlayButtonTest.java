package kewl.plugins.autologin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import kewl.api.Widgets;
import kewl.plugins.autologin.LoginSequence.PlayTarget;

/**
 * Finding "CLICK HERE TO PLAY" by its rectangle instead of by a measurement. Pure: the component
 * list is handed in, so none of this needs a game, a DLL or a window.
 *
 * <p>The dump lines here are in the exact format {@code Natives.dumpWidgetText} writes -- see
 * {@code nDumpWidgetText} in client/jvm.hpp: {@code "group:component x,y wxh shown|hidden text"},
 * with "-" for a component that carries no text. The welcome screen's button IS one of those: live
 * on 2026-09-06 that screen had groups loaded and not one component carried text, which is why the
 * search is by rectangle and seeded with the coordinate that is known to work.</p>
 */
public class PlayButtonTest {

	/** The measured coordinate the resolver is seeded with (centre - 3, top + 334 on 1314x900). */
	private static final int SEED_X = 654, SEED_Y = 334;
	private static final int CANVAS_W = 1314, CANVAS_H = 900;

	/** A Source with a fixed list; records how often the tree was walked. */
	private static final class Fake implements PlayButton.Source {
		final List<Widgets.Component> all = new ArrayList<>();
		int walks;

		@Override public List<Widgets.Component> loaded(int max) { walks++; return all; }

		@Override public Widgets.Component byId(int id) {
			for (Widgets.Component c : all) if (c.id() == id) return c;
			return null;
		}
	}

	private static Widgets.Component of(String line) {
		Widgets.Component c = Widgets.parseLine(line);
		assertNotNull("test fixture does not parse: " + line, c);
		return c;
	}

	private static Fake welcomeScreen() {
		Fake f = new Fake();
		// A canvas-sized top-level interface, a mid-sized panel, and the sprite button that contains
		// the measured point -- the shape the welcome screen has.
		f.all.addAll(Arrays.asList(
				of("548:0 0,0 1314x900 shown -"),
				of("549:1 337,180 640x400 shown -"),
				of("549:12 577,320 154x30 shown -"),
				of("549:13 577,320 154x30 hidden -")));
		return f;
	}

	// -----------------------------------------------------------------------------------------------
	// The parser
	// -----------------------------------------------------------------------------------------------

	@Test
	public void parsesTheDumpLineTheDllActuallyWrites()
	{
		Widgets.Component c = of("90:37 3,17 134x30 shown When the timer hits 0:00 YOU'LL DIE");
		assertEquals((90 << 16) | 37, c.id());
		assertEquals(90, c.group());
		assertEquals(37, c.component());
		assertEquals(3, c.x());
		assertEquals(17, c.y());
		assertEquals(134, c.width());
		assertEquals(30, c.height());
		assertEquals("When the timer hits 0:00 YOU'LL DIE", c.text());
		assertEquals(3 + 67, c.centreX());
		assertEquals(17 + 15, c.centreY());
	}

	@Test
	public void aSpriteHasNoText()
	{
		// "-" is the dump's placeholder, not a label; the play button arrives exactly like this.
		assertEquals("", of("549:12 577,320 154x30 shown -").text());
		assertTrue(of("7:1 0,0 184x20 hidden -").hidden());
	}

	@Test
	public void garbageLinesAreDroppedRatherThanThrowing()
	{
		// This reads a DLL's output on the frame thread: a mismatched DLL must degrade to "found
		// nothing", never to an exception in the middle of the login.
		assertNull(Widgets.parseLine(null));
		assertNull(Widgets.parseLine(""));
		assertNull(Widgets.parseLine("not a widget line at all"));
		assertNull(Widgets.parseLine("549:12 577,320 154x30 maybe -"));
		assertNull(Widgets.parseLine("549:x 577,320 154x30 shown -"));
		assertEquals(2, Widgets.parse("549:12 577,320 154x30 shown -\nrubbish\n\n7:1 0,0 184x20 shown a").size());
	}

	// -----------------------------------------------------------------------------------------------
	// The pick
	// -----------------------------------------------------------------------------------------------

	@Test
	public void theSmallestVisibleRectangleContainingThePointWins()
	{
		// The point is inside the button AND inside every container above it; the innermost is the
		// least area, and a hidden twin of the button is never the answer.
		Widgets.Component hit = Widgets.smallestContaining(welcomeScreen().all, SEED_X, SEED_Y, 0, 0);
		assertNotNull(hit);
		assertEquals((549 << 16) | 12, hit.id());
	}

	@Test
	public void aContainerIsRefusedByTheSizeBoundEvenWhenNothingSmallerIsLoaded()
	{
		Fake f = new Fake();
		f.all.add(of("548:0 0,0 1314x900 shown -"));            // only the top-level interface
		assertNull(Widgets.smallestContaining(f.all, SEED_X, SEED_Y, CANVAS_W / 2, CANVAS_H / 2));
		// Without the bound it would win, and the click would go to the middle of the screen.
		assertNotNull(Widgets.smallestContaining(f.all, SEED_X, SEED_Y, 0, 0));
	}

	@Test
	public void aPointNothingCoversFindsNothing()
	{
		// This is the case that matters if this build's stored positions are parent-relative: no
		// button-sized rectangle contains the canvas point, so the search comes back empty instead of
		// guessing. (The canvas-sized top-level interface covers every point there is, which is what
		// the size bound is for -- see the test above.)
		assertNull(Widgets.smallestContaining(welcomeScreen().all, 5, 5, CANVAS_W / 2, CANVAS_H / 2));
	}

	// -----------------------------------------------------------------------------------------------
	// The resolver
	// -----------------------------------------------------------------------------------------------

	@Test
	public void resolvesOnceFromTheMeasuredPointThenClicksTheWidgetCentre()
	{
		Fake f = welcomeScreen();
		PlayButton pb = new PlayButton(f);
		PlayTarget.Resolution r = pb.resolve(CANVAS_W, CANVAS_H, SEED_X, SEED_Y);
		assertEquals(577 + 77, r.x());
		assertEquals(320 + 15, r.y());
		assertTrue(r.how(), r.how().startsWith("widget 549:12 "));
		assertTrue(r.how(), r.how().contains("found from the measured 654,334"));
		assertEquals((549 << 16) | 12, pb.foundId());
		assertEquals(1, f.walks);

		// A second login re-reads the one component instead of walking the tree again -- and the
		// centre FOLLOWS it, which is the entire point: a wider window moves the button and the click
		// moves with it, where the measured offset would not have.
		f.all.set(2, of("549:12 700,400 154x30 shown -"));
		r = pb.resolve(1600, 1000, SEED_X, SEED_Y);
		assertEquals(700 + 77, r.x());
		assertEquals(400 + 15, r.y());
		assertEquals("the tree is walked once, not per click", 1, f.walks);
	}

	@Test
	public void fallsBackToTheMeasuredCoordinateAndSaysWhichPathItTook()
	{
		Fake f = new Fake();
		f.all.add(of("548:0 0,0 1314x900 shown -"));
		PlayButton pb = new PlayButton(f);
		PlayTarget.Resolution r = pb.resolve(CANVAS_W, CANVAS_H, SEED_X, SEED_Y);
		assertEquals(SEED_X, r.x());
		assertEquals(SEED_Y, r.y());
		assertTrue(r.how(), r.how().contains("the measured offset"));
		assertTrue(r.how(), r.how().contains("654,334"));
		assertEquals(-1, pb.foundId());
	}

	@Test
	public void anEmptyScreenIsTheFallBackToo()
	{
		// The login screen, live 2026-09-06: "[no groups loaded]" -- nothing to walk, so the login
		// form's own buttons stay on coordinates and so would this one.
		PlayButton pb = new PlayButton(new Fake());
		PlayTarget.Resolution r = pb.resolve(CANVAS_W, CANVAS_H, SEED_X, SEED_Y);
		assertEquals(SEED_X, r.x());
		assertEquals(SEED_Y, r.y());
		assertTrue(r.how(), r.how().contains("no component of the 0 loaded"));
	}

	@Test
	public void aWidgetThatUnloadedBetweenLoginsIsLookedUpAgain()
	{
		Fake f = welcomeScreen();
		PlayButton pb = new PlayButton(f);
		pb.resolve(CANVAS_W, CANVAS_H, SEED_X, SEED_Y);
		assertEquals(1, f.walks);
		// Logged out and back in: the group is gone, so the cached id reads nothing. Walking again is
		// right; clicking the remembered rectangle would be clicking a stale one.
		f.all.clear();
		PlayTarget.Resolution r = pb.resolve(CANVAS_W, CANVAS_H, SEED_X, SEED_Y);
		assertEquals(2, f.walks);
		assertEquals(SEED_X, r.x());
		assertEquals(-1, pb.foundId());
	}

	@Test
	public void aHiddenCachedWidgetIsNotClicked()
	{
		Fake f = new Fake();
		f.all.addAll(Arrays.asList(
				of("548:0 0,0 1314x900 shown -"),
				of("549:12 577,320 154x30 shown -")));
		PlayButton pb = new PlayButton(f);
		pb.resolve(CANVAS_W, CANVAS_H, SEED_X, SEED_Y);
		f.all.set(1, of("549:12 577,320 154x30 hidden -"));
		PlayTarget.Resolution r = pb.resolve(CANVAS_W, CANVAS_H, SEED_X, SEED_Y);
		// Re-walked, found only the hidden one and the canvas-sized container, so: the measurement.
		assertEquals(2, f.walks);
		assertEquals(SEED_X, r.x());
		assertEquals(SEED_Y, r.y());
	}

	/**
	 * The honest limit of "smallest containing": with the button gone, the panel around it still
	 * contains the point and is still the smallest thing that does, so it is what comes back. That is
	 * why {@link PlayButton} only ever resolves at the moment the click is due, on the one frame the
	 * welcome screen is certainly up -- and why the id it settled on is logged.
	 */
	@Test
	public void aContainerAroundTheButtonIsTheAnswerWhenTheButtonItselfIsNotThere()
	{
		Fake f = welcomeScreen();
		f.all.remove(2);
		f.all.remove(2);                                        // both the shown and the hidden button
		PlayTarget.Resolution r = new PlayButton(f).resolve(CANVAS_W, CANVAS_H, SEED_X, SEED_Y);
		assertTrue(r.how(), r.how().startsWith("widget 549:1 "));
	}
}
