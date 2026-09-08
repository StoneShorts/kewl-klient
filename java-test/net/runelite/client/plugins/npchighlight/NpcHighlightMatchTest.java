// The "NPCs to highlight" list, parsed and matched without a Client: names via WildcardMatcher,
// plain numbers as NPC type ids (the shim extension for a build whose NPC names read ""), and the
// (id, name)-keyed cache that must recompute once a name stops reading "".
package net.runelite.client.plugins.npchighlight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import net.runelite.client.plugins.npchighlight.NpcIndicatorsPlugin.Filter;

public class NpcHighlightMatchTest
{
	@Test
	public void parsesNamesAndIds()
	{
		Filter f = Filter.parse("Goblin, Cow*, 1234, , Ban?ker");
		assertEquals(3, f.nameCount());
		assertEquals(1, f.idCount());
	}

	@Test
	public void idEntriesMatchByTypeIdEvenWithoutAName()
	{
		Filter f = Filter.parse("Goblin, Cow*, 1234, , Ban?ker");
		assertTrue("id on the list, name unread", f.matches(1234, ""));
		assertFalse("id not on the list, name unread", f.matches(1, ""));
	}

	@Test
	public void nameEntriesMatchWildcardsCaseInsensitively()
	{
		Filter f = Filter.parse("Goblin, Cow*, 1234, , Ban?ker");
		assertTrue(f.matches(2, "Cow calf"));
		assertTrue(f.matches(3, "goblin"));
		assertTrue(f.matches(4, "Banker"));
		assertFalse(f.matches(5, "Chicken"));
		assertFalse("whole-string: 'Goblin' does not match 'Goblin guard'", f.matches(6, "Goblin guard"));
	}

	@Test
	public void nbspInNamesIsFolded()
	{
		Filter f = Filter.parse("Big Bob");
		assertTrue(f.matches(7, "Big Bob"));
		assertTrue(f.matches(7, "Big\u00A0Bob\u00A0"));
	}

	@Test
	public void cacheRecomputesWhenTheNameArrives()
	{
		Filter f = Filter.parse("Goblin");
		assertFalse("cached as no-match while the name reads empty", f.matches(9, ""));
		assertTrue("same id, name now readable: a new key, a new answer", f.matches(9, "Goblin"));
		assertEquals(2, f.cacheSize());
		assertFalse("the empty-name answer is still cached, still false", f.matches(9, ""));
		assertEquals(2, f.cacheSize());
	}

	@Test
	public void emptyListMatchesNothing()
	{
		Filter f = Filter.parse("");
		assertFalse(f.matches(1234, "Goblin"));
		assertEquals(0, f.nameCount() + f.idCount());
	}

	@Test
	public void overlongNumberIsANamePatternNotACrash()
	{
		Filter f = Filter.parse("99999999999999999999");
		assertEquals(1, f.nameCount());
		assertEquals(0, f.idCount());
		assertFalse(f.matches(1, ""));
	}
}
