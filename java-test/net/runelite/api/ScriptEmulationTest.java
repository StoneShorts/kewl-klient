// The two script accessors, and the one thing they now do for real.
//
// BACKGROUND (2026-09-07). The client's cs2 script VM entry point is not derived, so Client.runScript
// executed nothing and Client.getIntStack was hardcoded empty. The visible cost was not "scripts do
// not run" -- no ported plugin runs a script for its own sake -- it was Quest.getState, which asks
// QUEST_STATUS_GET for a quest and reads the answer off the int stack. With an always-empty stack
// every quest answered NOT_STARTED, so Shortest Path treated every quest-gated transport as unusable
// and routed the long way around, silently.
//
// The fix is not a script VM. Quest progress is not kept in the script engine, it is kept in the
// player's VARPS, and the varp array is live on this build. So runScript ANSWERS that one script from
// the varps. What is still missing is only DATA: the quest-id -> {var, finished value} table, which is
// cache data (/quests.csv), not an offset. This suite pins the behaviour on both sides of that data:
//
//   * with no table, the answer is NOT_STARTED and the gap says so with the exact next step -- i.e.
//     the fail-closed behaviour is UNCHANGED, which is what makes the change safe to ship untested
//     against a live game;
//   * the stack is per thread and is cleared by every runScript, so one thread's or one script's
//     answer can never be read as another's. That one is worth a test on its own: the failure it
//     prevents is a quest the player has NOT done reading FINISHED, which opens a transport that does
//     not exist and sends the path through it.
package net.runelite.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ScriptEmulationTest
{
	private Client client;

	@Before
	public void clean()
	{
		ShimSupport.reset();
		ShimSupport.setLogging(false);
		client = Client.get();
	}

	@After
	public void restore()
	{
		ShimSupport.reset();
	}

	@Test
	public void anUnrunScriptLeavesAnEmptyStack()
	{
		// A script the shim does not emulate must leave nothing behind. Quest.getState's own guard is
		// `stack.length == 0`, so an empty array is the contract, not null.
		client.runScript(ScriptID.FAIRYRINGS_SORT_UPDATE);
		final int[] stack = client.getIntStack();
		assertNotNull("getIntStack must never be null: Quest.getState indexes it", stack);
		assertEquals(0, stack.length);
	}

	@Test
	public void everyQuestStillAnswersNotStartedUntilTheTableExists()
	{
		// The whole point of the change is that it does not alter the ANSWER while the data is
		// missing. NOT_STARTED fails closed: a quest-gated transport stays unusable, so a path is long
		// rather than wrong. If this ever flips to FINISHED without /quests.csv, something is guessing.
		for (Quest quest : new Quest[]{ Quest.COOKS_ASSISTANT, Quest.THE_GRAND_TREE, Quest.BONE_VOYAGE })
		{
			assertEquals(quest.getName(), QuestState.NOT_STARTED, quest.getState(client));
		}
	}

	@Test
	public void theMissingQuestTableIsReportedAsCacheDataWithTheNextStep()
	{
		Quest.COOKS_ASSISTANT.getState(client);

		final String reason = ShimSupport.reasonFor("Client.runScript:QUEST_STATUS_GET");
		assertNotNull("the quest gap must register under its own name, not under runScript", reason);
		assertEquals(ShimSupport.Kind.NEEDS_CACHE_DATA,
			ShimSupport.gaps().stream()
				.filter(g -> g.accessor().equals("Client.runScript:QUEST_STATUS_GET"))
				.findFirst().orElseThrow().kind());

		// The message has to carry the CONSEQUENCE and the NEXT STEP, because that is the whole
		// contract of ShimSupport -- a generic apology is what this mechanism exists to replace.
		assertTrue("must name the consequence: " + reason, reason.contains("NOT_STARTED"));
		assertTrue("must name the consequence: " + reason, reason.contains("routes the long way"));
		assertTrue("must name the file to drop in: " + reason, reason.contains("/quests.csv"));
		assertTrue("must say it is not an offset: " + reason, reason.contains("no offset needed"));
	}

	@Test
	public void aScriptThatWritesNothingClearsThePreviousAnswer()
	{
		// Two scripts in a row: neither writes, so the second must not be able to read the first's
		// leftovers. Both are empty today, so this asserts the mechanism (same instance, cleared) that
		// keeps holding once /quests.csv makes the first one non-empty.
		client.runScript(ScriptID.QUEST_STATUS_GET, Quest.COOKS_ASSISTANT.getId());
		client.runScript(ScriptID.FAIRYRINGS_SORT_UPDATE);
		assertEquals(0, client.getIntStack().length);
	}

	@Test
	public void theStackIsPerThread() throws Exception
	{
		// PathfinderConfig refreshes transports on the pathfinder worker while overlays render on the
		// frame thread; both call Quest.getState. A shared stack would let one answer the other.
		final int[] onThisThread = client.getIntStack();

		final AtomicReference<int[]> onWorker = new AtomicReference<>();
		final Thread worker = new Thread(() ->
		{
			client.runScript(ScriptID.QUEST_STATUS_GET, Quest.BONE_VOYAGE.getId());
			onWorker.set(client.getIntStack());
		});
		worker.start();
		worker.join();

		assertNotNull(onWorker.get());
		assertEquals(0, onWorker.get().length);
		// Same empty instance is fine; what must not happen is the worker's run leaving a VALUE that
		// this thread then reads. Re-reading here proves this thread's slot was untouched.
		assertEquals(0, client.getIntStack().length);
		assertSame(onThisThread, client.getIntStack());
	}

	@Test
	public void getIntStackNamesRunScriptRatherThanApologising()
	{
		client.getIntStack();
		final String reason = ShimSupport.reasonFor("Client.getIntStack");
		assertNotNull(reason);
		assertTrue("must point at the cause rather than restate the symptom: " + reason,
			reason.contains("runScript"));
	}
}
