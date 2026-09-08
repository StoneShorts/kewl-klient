// The pure half of absolute widget geometry: WHEN a widgetAbs answer may be treated as a canvas
// rectangle, and what the root self-test actually asserts. No client, no natives.
//
// Background. Every widget position in this shim was parent-relative pretending to be absolute: a
// single packed id took the component's stored x/y as a canvas position, so the minimap reported
// (53,8) while it visibly sat near x=1143, and both map overlays stood down rather than draw in the
// wrong corner. kewl.Natives.widgetAbs sums the parent chain in C++ now, through a parent link that is
// DERIVED at runtime from the widget tree rather than hardcoded -- which means the derivation can come
// back undecided, and the two flags below are how that undecidedness reaches a caller. Getting this
// predicate wrong is exactly the bug the feature exists to prevent, so it is pinned here.
package net.runelite.api;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WidgetAbsoluteTest
{
	/** {ok, absX, absY, width, height, hidden, depth, complete} -- the native's array shape. */
	private static int[] abs(int ok, int x, int y, int w, int h, int depth, int complete)
	{
		return new int[]{ok, x, y, w, h, 0, depth, complete};
	}

	@Test
	public void aCompleteChainIsUsable()
	{
		// The minimap in resizable mode: 152x152 summed two hops up to the top right of the canvas.
		assertTrue(ClientState.absoluteUsable(abs(1, 1143, 12, 152, 152, 2, 1)));
	}

	@Test
	public void aRootIsUsableWithoutWalkingAnything()
	{
		// depth 0 is not "nothing happened" -- a group root HAS no parent, and its own rectangle is
		// already a canvas rectangle. Refusing depth 0 would refuse every toplevel interface.
		assertTrue(ClientState.absoluteUsable(abs(1, 0, 0, 1314, 900, 0, 1)));
	}

	@Test
	public void anIncompleteChainIsRefusedEvenThoughItLooksLikeAnAnswer()
	{
		// THE case this predicate exists for. The walk gave up partway -- a torn read, a cross-group
		// parent, a depth cap -- so x/y are a HALF-SUMMED position: not the stored relative pair and
		// not the canvas one. Drawing there is worse than the old refusal, because it looks fine.
		assertFalse(ClientState.absoluteUsable(abs(1, 53, 8, 152, 152, 1, 0)));
		// The same shape the shim had before any of this work: no link derived at all, so widgetAbs
		// hands back exactly what widget() always did and says complete = 0.
		assertFalse(ClientState.absoluteUsable(abs(1, 53, 8, 152, 152, 0, 0)));
	}

	@Test
	public void anUnresolvedIdIsRefusedWhateverElseTheArraySays()
	{
		assertFalse(ClientState.absoluteUsable(abs(0, 1143, 12, 152, 152, 2, 1)));
	}

	@Test
	public void anEmptyOrShortOrMissingArrayIsRefused()
	{
		assertFalse("a DLL older than the native returns nothing", ClientState.absoluteUsable(null));
		assertFalse(ClientState.absoluteUsable(new int[0]));
		// widget()'s six-int shape must never be mistaken for widgetAbs's eight-int one: it has no
		// complete flag at all, so index 7 would be an out-of-bounds read, not a false.
		assertFalse(ClientState.absoluteUsable(new int[]{1, 53, 8, 152, 152, 0}));
	}

	@Test
	public void theRootSelfTestPassesOnlyAtTheOriginAtCanvasSize()
	{
		assertTrue(ClientState.rootSelfTestPasses(abs(1, 0, 0, 1314, 900, 0, 1), 1314, 900));
	}

	@Test
	public void theRootSelfTestFailsWhenTheRootIsNotCanvasSized()
	{
		// This is the FORK. A root that is not canvas-sized means the stored width is a cache original
		// rather than a laid-out value, and then summing relative x/y is the wrong algorithm entirely:
		// the position/size MODE fields would have to be derived and the client's alignment
		// re-implemented. The test says so rather than letting a near miss pass as a pass.
		assertFalse(ClientState.rootSelfTestPasses(abs(1, 0, 0, 512, 334, 0, 1), 1314, 900));
		assertFalse(ClientState.rootSelfTestPasses(abs(1, 4, 0, 1314, 900, 0, 1), 1314, 900));
		assertFalse(ClientState.rootSelfTestPasses(abs(1, 0, 4, 1314, 900, 0, 1), 1314, 900));
	}

	@Test
	public void theRootSelfTestIsInconclusiveWithoutACanvasSize()
	{
		// viewport() can come back empty; a self-test against 0x0 would "pass" for a 0x0 root and
		// certify a broken chain.
		assertFalse(ClientState.rootSelfTestPasses(abs(1, 0, 0, 0, 0, 0, 1), 0, 0));
	}

	@Test
	public void theRootSelfTestInheritsTheCompletenessRule()
	{
		assertFalse("an incomplete root cannot certify anything",
			ClientState.rootSelfTestPasses(abs(1, 0, 0, 1314, 900, 0, 0), 1314, 900));
	}
}
