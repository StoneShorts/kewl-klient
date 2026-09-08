// A plain overworld walk over the bundled collision map: Lumbridge to Varrock on foot.
//
// The transport- and destination-specific tests in PathfinderTest cover the tricky routing rules;
// this one exists to answer the blunter question -- "does the ported core find an ordinary walking
// path at all" -- in one glance.
package shortestpath.pathfinder;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.Mock;
import static org.mockito.Mockito.when;
import org.mockito.junit.MockitoJUnitRunner;
import shortestpath.ShortestPathConfig;
import shortestpath.TeleportationItem;
import shortestpath.WorldPointUtil;

@RunWith(MockitoJUnitRunner.class)
public class SmokeTest
{
	// Lumbridge castle courtyard and the centre of Varrock square.
	private static final int LUMBRIDGE = WorldPointUtil.packWorldPoint(3222, 3218, 0);
	private static final int VARROCK = WorldPointUtil.packWorldPoint(3213, 3424, 0);

	@Mock
	private Client client;

	@Mock
	private ShortestPathConfig config;

	private PathfinderConfig pathfinderConfig;

	@Before
	public void setUp()
	{
		pathfinderConfig = new TestPathfinderConfig(
			client,
			config,
			QuestState.FINISHED,
			true,  // Ignore Varbit checks
			true   // Ignore Varplayer checks
		);

		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenReturn(Thread.currentThread());
		when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(99);
		when(config.calculationCutoff()).thenReturn(600);
		when(config.useTeleportationItems()).thenReturn(TeleportationItem.NONE);

		pathfinderConfig.refresh();
	}

	@Test
	public void lumbridgeToVarrockOnFoot()
	{
		Pathfinder pathfinder = new Pathfinder(pathfinderConfig, LUMBRIDGE, java.util.Set.of(VARROCK));
		pathfinder.run();

		assertTrue("pathfinder found no path", pathfinder.getPath() != null && !pathfinder.getPath().isEmpty());

		int start = pathfinder.getPath().get(0).getPackedPosition();
		int end = pathfinder.getPath().get(pathfinder.getPath().size() - 1).getPackedPosition();
		assertEquals("path starts at the origin", LUMBRIDGE, start);
		assertEquals("path ends at the destination", VARROCK, end);

		int length = pathfinder.getPath().size();
		// Straight-line distance is ~206 tiles; a walking path must be longer than that and far
		// shorter than wandering the whole map.
		assertTrue("suspiciously short path: " + length, length > 206);
		assertTrue("suspiciously long path: " + length, length < 400);
	}
}
