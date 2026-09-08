// Shim of net.runelite.api.Client (BSD-2, RuneLite) -- a concrete class backed by kewl.api and the
// natives, not an injected client.
//
// Everything kewl can already read (player, skills, tick count, scene) is wired through. Everything
// that needs a new memory offset (varps, item containers, widgets, world map, menu, game state,
// camera) delegates to net.runelite.api.ClientState, which holds honest defaults until each offset is
// re-derived; each method there names the offset it is waiting for.
package net.runelite.api;

import net.runelite.api.widgets.Widget;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;

public class Client
{
	public static final Client INSTANCE = new Client();

	private final ClientThread clientThread = new ClientThread();
	// Live view: the supplier re-reads kewl's per-frame Local, so getLocalPlayer() always answers with
	// THIS frame's position, not a snapshot taken before the game ever refreshed.
	private final Player player = new Player(() -> kewl.api.Game.me());
	private final WorldView topLevelWorldView = WorldView.TOP_LEVEL;
	private final ClientState state = new ClientState();

	Client()
	{
	}

	public static Client get()
	{
		return INSTANCE;
	}

	public ClientState state()
	{
		return state;
	}

	// -- world and self --------------------------------------------------------------------------

	/**
	 * Null before the player has spawned, exactly like upstream -- plugins null-check this before
	 * projecting from it, and a non-null Player with a garbage position would sail past those checks.
	 */
	public Player getLocalPlayer()
	{
		return kewl.api.Game.me().exists() ? player : null;
	}

	/**
	 * The local Player object itself, never null -- the identity ActorTable puts at the head of
	 * getPlayers() and the one plugins compare other players against by reference. Its primitives
	 * read zeros while the player is absent.
	 */
	Player localPlayer()
	{
		return player;
	}

	// -- actors ------------------------------------------------------------------------------------
	//
	// Backed by kewl.api.Game.npcs()/players() through net.runelite.api.ActorTable, which keeps one
	// NPC/Player object per handle across frames (plugins hold them between NpcSpawned and
	// NpcDespawned). getCachedNPCs()/getCachedPlayers() (deprecated upstream) are deliberately NOT
	// provided: they are arrays indexed by client slot, and the uid range on this build is unknown.

	/** Every NPC in this frame's snapshot, in the client's table order. */
	public List<NPC> getNpcs()
	{
		ActorTable.refresh(kewl.KewlKlient.frame());
		return ActorTable.npcs();
	}

	/** Every player in this frame's snapshot, the LOCAL player included (first), as upstream. */
	public List<Player> getPlayers()
	{
		ActorTable.refresh(kewl.KewlKlient.frame());
		return ActorTable.players();
	}

	public WorldView getTopLevelWorldView()
	{
		return topLevelWorldView;
	}

	public WorldView getWorldView(int id)
	{
		return id == WorldView.TOPLEVEL ? topLevelWorldView : null;
	}

	public WorldView findWorldViewFromWorldPoint(WorldPoint point)
	{
		return topLevelWorldView;
	}

	public int getPlane()
	{
		return getTopLevelWorldView().getPlane();
	}

	// -- skills -----------------------------------------------------------------------------------

	public int getRealSkillLevel(Skill skill)
	{
		kewl.api.Skill k = toKewl(skill);
		return k == null ? 0 : kewl.api.Skills.level(k);
	}

	public int getBoostedSkillLevel(Skill skill)
	{
		kewl.api.Skill k = toKewl(skill);
		return k == null ? 0 : kewl.api.Skills.effective(k);
	}

	public int getTotalLevel()
	{
		return kewl.api.Skills.totalLevel();
	}

	// -- vars (ClientState holds the values; the varps native fills them) ---------------------------

	public int getVarbitValue(int id)
	{
		return state.getVarbitValue(id);
	}

	public int getVarpValue(int id)
	{
		return state.getVarpValue(id);
	}

	// -- state that needs new offsets ---------------------------------------------------------------

	public GameState getGameState()
	{
		return state.getGameState();
	}

	/**
	 * The world's flags, DERIVED from varbits rather than read from a world id.
	 *
	 * <p>The current world id is not derivable statically on client-240-6 (every lead eliminated in
	 * the deob notes), so the mask upstream builds from it does not exist here. What DOES exist is the
	 * live varp array -- {@link ClientState#getVarpValue} reads the client's own varp storage through
	 * {@code VARP_ARRAY_PTR} -- and the game keeps a per-world flag in a varbit for three of these
	 * types. So each flag is answered from the varbit that names it, and the ones with no varbit stay
	 * out of the set.</p>
	 *
	 * <p>WHAT IS DERIVED, and how far to trust each one:</p>
	 * <ul>
	 *   <li>{@link WorldType#SEASONAL}: true when ANY {@code LEAGUE_AREA_SELECTION_*} slot
	 *       (10662..10667) is non-zero. This is the strongest of the three -- slot 0 is pre-set to
	 *       Varlamore by the game on a seasonal world, and on a normal world every slot reads 0 -- and
	 *       it is self-consistent with the only consumer: {@code shortestpath.leagues.LeagueModeState}
	 *       reads exactly these slots for the unlock set once seasonal is true. Failure mode is a false
	 *       NEGATIVE only (a leagues account before the game sets slot 0), which is today's behaviour.</li>
	 *   <li>{@link WorldType#PVP}, {@link WorldType#DEADMAN}, {@link WorldType#TOURNAMENT_WORLD}: taken
	 *       from the varbits the cache names {@code this_is_a_pvp_world} (3984), {@code deadman_mode}
	 *       (4153) and {@code this_is_a_tournament_world} (4621). NOT SEEN LIVE: the mapping rests on
	 *       the cache NAME, and none of the three has a row in VarbitTable yet, so all three
	 *       read 0 today and this method still returns the empty set on a normal world. Adding those
	 *       three rows is the whole fix; no offset is involved.</li>
	 * </ul>
	 *
	 * <p>NOT derived, and there is nothing to derive them from: MEMBERS, BOUNTY, PVP_ARENA,
	 * SKILL_TOTAL, QUEST_SPEEDRUNNING, HIGH_RISK, LAST_MAN_STANDING, BETA_WORLD, FRESH_START_WORLD.
	 * Those live only in the world-list mask the client got at login, which is behind the world id.</p>
	 */
	public EnumSet<WorldType> getWorldType()
	{
		final EnumSet<WorldType> types = EnumSet.noneOf(WorldType.class);

		for (int slot : LEAGUE_AREA_SELECTION_VARBITS)
		{
			if (getVarbitValue(slot) != 0)
			{
				types.add(WorldType.SEASONAL);
				break;
			}
		}
		if (getVarbitValue(VarbitID.THIS_IS_A_PVP_WORLD) != 0)
		{
			types.add(WorldType.PVP);
		}
		if (getVarbitValue(VarbitID.DEADMAN_MODE) != 0)
		{
			types.add(WorldType.DEADMAN);
		}
		if (getVarbitValue(VarbitID.THIS_IS_A_TOURNAMENT_WORLD) != 0)
		{
			types.add(WorldType.TOURNAMENT_WORLD);
		}

		if (types.isEmpty())
		{
			ShimSupport.note("Client.getWorldType", ShimSupport.Kind.NEEDS_CACHE_DATA,
				"reads an EMPTY set, so this looks like a plain non-seasonal, non-PvP world and every"
					+ " caller that gates on a world type takes the permissive branch (Shortest Path"
					+ " applies no Leagues region locks; Player Indicators treats PVP as off). The"
					+ " world id that upstream builds this mask from is not derivable on client-240-6,"
					+ " so this is derived from varbits instead: SEASONAL is LIVE off the league"
					+ " area-selection slots 10662-10667, but PVP/DEADMAN/TOURNAMENT need rows for"
					+ " varbits 3984 (this_is_a_pvp_world), 4153 (deadman_mode) and 4621"
					+ " (this_is_a_tournament_world) in net.runelite.api.VarbitTable -- cache data,"
					+ " not an offset. An empty set on a normal members world is the CORRECT answer");
		}
		return types;
	}

	/**
	 * The {@code LEAGUE_AREA_SELECTION_0..5} varbit slots, the same six
	 * {@code shortestpath.leagues.LeagueModeState} reads. Duplicated rather than imported because the
	 * shim must not depend on a plugin package.
	 */
	private static final int[] LEAGUE_AREA_SELECTION_VARBITS =
		{ 10662, 10663, 10664, 10665, 10666, 10667 };

	/**
	 * The server tick count in RuneLite's semantics (one per 600ms game tick), CONVERTED from kewl's
	 * cycle counter by dividing by 30.
	 *
	 * <p>Registered as a gap, and it is the subtlest kind in the shim: this returns a plausible
	 * number rather than an obvious placeholder, so a wrong one cannot be seen. The division assumes
	 * CYCLE advances once per 20ms, and client/offsets.hpp says of that field in as many words that
	 * "the '+1 per 20ms' cadence is the OSRS frame pace and was not re-proven statically -- what the
	 * binary shows is one bump per frame callback, whatever that frame's length turns out to be." If
	 * that counter is per RENDER frame rather than per fixed timestep, every value here is off by the
	 * frame-rate ratio and every tick-delta comparison in the ported plugins drifts with it, silently.
	 * The divisor is left alone because guessing a different one would be no better founded than this
	 * one -- what closes it is reading the client's OWN tick field, which offsets.hpp already names:
	 * the client's getTickCount Lua binding reads client+0x31A8, not CYCLE.</p>
	 *
	 * <p>The assumption is no longer only an assumption: {@link #measureCycleCadence} times CYCLE
	 * against the wall clock for ten seconds and prints one line saying whether 50 bumps a second is
	 * what actually happens. It reports; it does not change the divisor.</p>
	 */
	public int getTickCount()
	{
		final int cycle = kewl.api.Game.me().cycle();
		measureCycleCadence(cycle, System.nanoTime());
		ShimSupport.note("Client.getTickCount", ShimSupport.Kind.NEEDS_OFFSET,
			"is DERIVED, not read: cycle()/30, which assumes the cycle counter advances once per 20ms."
				+ " offsets.hpp says that cadence was never re-proven -- the binary only shows one bump"
				+ " per frame callback -- so if it is per render frame the tick count is wrong by the"
				+ " frame-rate ratio and every tick-delta comparison drifts. This build now MEASURES"
				+ " the cadence and prints one [shim] line saying whether /30 holds, about ten seconds"
				+ " after the first call (grep 'getTickCount cadence'). Closing it properly means"
				+ " reading the client's own tick field at client+0x31A8 (the offset its getTickCount"
				+ " binding uses)");
		return cycle / 30;
	}

	/** How long the cadence probe watches CYCLE before it will answer. Ten seconds is ~500 cycles. */
	private static final long CYCLE_PROBE_NANOS = 10L * 1_000_000_000L;

	private static volatile long cycleProbeNanos;
	private static volatile int cycleProbeStart = -1;
	private static volatile boolean cycleProbeReported;

	/**
	 * Turn the ONE unproven assumption behind {@link #getTickCount()} into a measurement.
	 *
	 * <p>The divisor 30 encodes "CYCLE bumps once per 20ms", i.e. 50 bumps a second. Nothing in the
	 * binary proved that -- offsets.hpp only established one bump per frame callback -- so the same
	 * code that would be right at a fixed 50Hz timestep is wrong by the frame-rate ratio if the
	 * counter is per RENDERED frame. That difference is invisible in the returned number (both look
	 * like a plausible tick count) but it is trivially visible over ten seconds of wall clock, which
	 * is all this does: sample CYCLE and System.nanoTime once, sample again ten seconds later, print
	 * the rate, and say in the same line whether /30 survives it.</p>
	 *
	 * <p>It measures and REPORTS; it does not change the divisor. Switching divisors mid-session would
	 * make getTickCount jump backwards, and every plugin holding a "last tick" would fire at once.
	 * The number this prints is what a person needs to decide what the divisor should be, and it is a
	 * measurement rather than another guess.</p>
	 *
	 * <p>Costs one volatile read on the steady path once it has answered. Restarts itself if CYCLE
	 * goes backwards (a relog resets it), so a probe spanning a login boundary cannot report nonsense.</p>
	 *
	 * @param cycle this frame's CYCLE value; non-positive means the game has not started counting yet
	 * @param now   a monotonic nanosecond clock; a parameter so the arithmetic can be asserted without
	 *              a ten-second test
	 * @return the line printed, or null on every call that did not answer -- the return exists for the
	 *         test, the print is what a user sees
	 */
	static String measureCycleCadence(int cycle, long now)
	{
		if (cycleProbeReported || cycle <= 0)
		{
			return null;
		}
		final int start = cycleProbeStart;
		if (start < 0 || cycle < start)
		{
			// First sample, or CYCLE went BACKWARDS -- a relog resets it, and a probe that spanned a
			// login boundary would divide a negative bump count by ten seconds and print nonsense.
			cycleProbeStart = cycle;
			cycleProbeNanos = now;
			return null;
		}
		final long elapsed = now - cycleProbeNanos;
		if (elapsed < CYCLE_PROBE_NANOS)
		{
			return null;
		}

		final int bumps = cycle - start;
		if (bumps == 0)
		{
			// CYCLE did not move at all in ten seconds. That is not a cadence of zero, it is a client
			// that was not running frames -- logged out, minimised, or the window blocked. Reporting
			// "0.0/s, wrong by a factor of 0.00" would be a confident wrong answer, so restart the
			// probe instead and measure again over the next ten seconds of actual play.
			cycleProbeStart = cycle;
			cycleProbeNanos = now;
			return null;
		}
		cycleProbeReported = true;

		final double perSecond = bumps * 1e9 / elapsed;
		final String verdict;
		if (perSecond >= 45.0 && perSecond <= 55.0)
		{
			verdict = "that is the 20ms cadence /30 assumes, so getTickCount is RIGHT on this build";
		}
		else
		{
			verdict = "that is NOT the 50/s the /30 divisor assumes -- one 600ms game tick is "
				+ Math.round(perSecond * 0.6) + " cycles here, so getTickCount is wrong by a factor of "
				+ String.format(java.util.Locale.ROOT, "%.2f", perSecond / 50.0)
				+ " and every tick-delta comparison in the ported plugins drifts with it";
		}
		// Locale.ROOT so the number is a dot-decimal wherever this runs: the line is grepped, and a
		// German-locale "50,0/s" would not match what the comments and the ShimSupport text promise.
		final String line = "[shim] Client.getTickCount cadence MEASURED: CYCLE advanced " + bumps
			+ " times in " + (elapsed / 1_000_000L) + " ms = "
			+ String.format(java.util.Locale.ROOT, "%.1f", perSecond) + "/s. " + verdict;
		System.out.println(line);
		return line;
	}

	/** Tests only: forget the probe, so each case starts from a cold measurement. */
	static void resetCycleProbe()
	{
		cycleProbeStart = -1;
		cycleProbeNanos = 0L;
		cycleProbeReported = false;
	}

	public boolean isResized()
	{
		return state.isResized();
	}

	public double getMinimapZoom()
	{
		return state.getMinimapZoom();
	}

	public int getCameraYawTarget()
	{
		return state.getCameraYawTarget();
	}

	/**
	 * Same number as {@link #getCameraYawTarget()}. The derivation measures where the camera IS, so
	 * it cannot separate the current angle from the one a camera move is animating towards; the two
	 * differ upstream only for the handful of frames a move lasts.
	 */
	public int getCameraYaw()
	{
		return state.getCameraYawTarget();
	}

	/**
	 * The camera pitch in 0..{@link Perspective#PITCH_STRAIGHT_DOWN}, or -1 when it has never been
	 * derived. -1 is NOT a level camera -- see {@link ClientState#getCameraPitch()}.
	 */
	public int getCameraPitch()
	{
		return state.getCameraPitch();
	}

	public int getCameraPitchTarget()
	{
		return state.getCameraPitchTarget();
	}

	public Point getMouseCanvasPosition()
	{
		return state.getMouseCanvasPosition();
	}

	public boolean isKeyPressed(int keyCode)
	{
		return state.isKeyPressed(keyCode);
	}

	public boolean isMenuOpen()
	{
		return state.isMenuOpen();
	}

	public Widget getWidget(int... ids)
	{
		return state.getWidget(ids);
	}

	public ItemContainer getItemContainer(int id)
	{
		return state.getItemContainer(id);
	}

	public ItemDefinition getItemDefinition(int itemId)
	{
		return state.getItemDefinition(itemId);
	}

	public net.runelite.api.worldmap.WorldMap getWorldMap()
	{
		return state.getWorldMap();
	}

	public EnumComposition getEnum(int id)
	{
		return state.getEnum(id);
	}

	public Menu getMenu()
	{
		return state.getMenu();
	}

	/** The minimap draw area, resolved the same way the plugin resolves it (widget by interface id). */
	public Widget getMinimapDrawWidget()
	{
		return state.getMinimapDrawWidget();
	}

	// -- scripts ------------------------------------------------------------------------------------

	private static final int[] EMPTY_STACK = new int[0];

	/**
	 * The int stack a script left behind, per thread.
	 *
	 * <p>Per THREAD because the two callers are on different ones: overlays and the menu path call
	 * from the frame thread while {@code PathfinderConfig} refreshes transports on the pathfinder
	 * worker. A single shared array would let one thread's runScript answer the other's getIntStack --
	 * a quest the player has not done reading as FINISHED, which opens a transport that is not
	 * actually usable. Upstream has one stack because it has one client thread; the shim does not.</p>
	 */
	private static final ThreadLocal<int[]> INT_STACK = ThreadLocal.withInitial(() -> EMPTY_STACK);

	/**
	 * Run a cs2 script -- EMULATED for the one script whose absence a user can see, a no-op otherwise.
	 *
	 * <p>The client's script VM entry point is not derived, so nothing here executes cache bytecode.
	 * But QUEST_STATUS_GET does not need a VM: quest progress is not kept in the script engine, it is
	 * kept in the player's VARPS, and the varp array IS live on this build (ClientState reads it
	 * through VARP_ARRAY_PTR, found via the client's own getVarp binding). So this answers that one
	 * script from the varps, pushing the same 0/1/2 onto {@link #getIntStack()} that the real script
	 * pushes, and {@link Quest#getState} works unchanged.</p>
	 *
	 * <p>WHAT IS STILL MISSING, precisely: the quest-id to progress-var table. RuneLite's Quest ids are
	 * quest STRUCT ids, and the real QUEST_STATUS_GET reads that struct's params -- which var holds the
	 * quest's progress, and the value at which it counts as finished. Those params are cache data, in
	 * the same dump that produced VarbitTable; no memory offset is involved. Drop them in as a
	 * classpath resource /quests.csv, one questId,p|b,varId,finishedValue row per line, exactly as
	 * /varbits.csv already overlays the varbit table. Until that file exists this has no row for any
	 * quest and every quest keeps answering NOT_STARTED, which is today's behaviour and fails CLOSED:
	 * quest-gated transports stay unusable and paths route the long way around.</p>
	 *
	 * <p>Deliberately NOT guessed: the var id could be inferred by name for many quests (VarPlayerID
	 * has COOKQUEST, DRUIDQUEST and so on) but the FINISHED threshold cannot -- it differs per quest.
	 * A wrong threshold would silently mark a quest complete and route the player through a transport
	 * they cannot use, which is worse than the long way around.</p>
	 */
	public Object runScript(int id, Object... args)
	{
		// A script that writes nothing must not leave the PREVIOUS script's answer on the stack for
		// the next getIntStack() to read as its own result.
		INT_STACK.set(EMPTY_STACK);

		if (id == ScriptID.QUEST_STATUS_GET)
		{
			final int questId = args != null && args.length > 0 && args[0] instanceof Number
				? ((Number) args[0]).intValue() : -1;
			final int status = questStatus(questId);
			if (status >= 0)
			{
				INT_STACK.set(new int[]{ status });
				return null;
			}
			ShimSupport.note("Client.runScript:QUEST_STATUS_GET", ShimSupport.Kind.NEEDS_CACHE_DATA,
				"cannot answer quest " + questId + " (nor any other): the emulation reads quest"
					+ " progress straight out of the LIVE varp array, but it has no quest-id to"
					+ " {var, finished value} table to read WITH. Every quest therefore answers"
					+ " NOT_STARTED, so Shortest Path treats every quest-gated transport as unusable"
					+ " and routes the long way around. Next step, no offset needed: add /quests.csv"
					+ " to the resources, one 'questId,p|b,varId,finishedValue' row per line, from the"
					+ " quest structs of the same cache dump VarbitTable came from (rev 240, cache"
					+ " 2686). " + QUEST_TABLE_SOURCE);
			return null;
		}

		ShimSupport.note("Client.runScript", ShimSupport.Kind.NEEDS_OFFSET,
			"does NOTHING and returns null for script " + id + ": the client's cs2 script VM entry"
				+ " point is not derived, so no script runs. QUEST_STATUS_GET is emulated separately"
				+ " (see Client.runScript:QUEST_STATUS_GET); what is left unrun here is the"
				+ " fairy-ring panel scroll, which the panel's own revalidation covers");
		return null;
	}

	/**
	 * The int stack the last {@link #runScript} on THIS thread left behind. Empty when that script was
	 * not one the shim emulates -- which is what {@link Quest#getState} tests before it indexes.
	 */
	public int[] getIntStack()
	{
		final int[] stack = INT_STACK.get();
		if (stack.length == 0)
		{
			ShimSupport.note("Client.getIntStack", ShimSupport.Kind.NEEDS_OFFSET,
				"read EMPTY: the last runScript on this thread wrote nothing, because the cs2 script VM"
					+ " is not reachable and the only script the shim emulates (QUEST_STATUS_GET) had"
					+ " no data to answer with. See Client.runScript for both");
		}
		return stack;
	}

	// -- quest progress, read from the live varps -------------------------------------------------

	/** Said in the message that names the missing table, so the size of the job is in the log too. */
	private static final String QUEST_TABLE_SOURCE =
		"73 quests are referenced by the vendored transport data, so that many rows close it";

	/**
	 * Quest struct id to {0 for a varp / 1 for a varbit, var id, value at or above which the quest is
	 * FINISHED}. Loaded once from /quests.csv; empty when that resource is absent, which it is in this
	 * tree.
	 */
	private static final Map<Integer, int[]> QUEST_VARS = loadQuestTable();

	/**
	 * The 0/1/2 the real QUEST_STATUS_GET pushes -- 2 finished, 1 not started, 0 in progress -- or -1
	 * when there is no row for the quest, which is the ONLY case the caller reports as a gap.
	 *
	 * <p>The progress test is the shape every OSRS quest var has: 0 before the quest is started, a
	 * monotonically rising counter while it runs, and a per-quest value at the end. Only that last
	 * value has to come from the table; "0 means untouched" holds for all of them.</p>
	 */
	private int questStatus(int questId)
	{
		final int[] row = QUEST_VARS.get(questId);
		if (row == null)
		{
			return -1;
		}
		final int value = row[0] == 0 ? getVarpValue(row[1]) : getVarbitValue(row[1]);
		if (value >= row[2])
		{
			return 2; // FINISHED
		}
		// getVarpValue answers -1 before the varps are up; that is "nothing known", which has to read
		// as NOT_STARTED rather than as in-progress, so a transport is never opened on a missing read.
		return value <= 0 ? 1 : 0;
	}

	/**
	 * Reads /quests.csv if it is on the classpath: questId,p|b,varId,finishedValue, with '#' comments
	 * and blank lines skipped. Same drop-in shape as ClientState's /varbits.csv, so a cache dump can be
	 * added without recompiling this class. A malformed row is skipped and named rather than taking the
	 * whole table down with it.
	 */
	private static Map<Integer, int[]> loadQuestTable()
	{
		final Map<Integer, int[]> out = new HashMap<>();
		final InputStream in = Client.class.getResourceAsStream("/quests.csv");
		if (in == null)
		{
			return out;
		}
		try (BufferedReader r = new BufferedReader(new InputStreamReader(in)))
		{
			String line;
			while ((line = r.readLine()) != null)
			{
				if (line.isBlank() || line.startsWith("#"))
				{
					continue;
				}
				final String[] parts = line.split(",");
				if (parts.length < 4)
				{
					continue;
				}
				try
				{
					final String kind = parts[1].trim().toLowerCase(java.util.Locale.ROOT);
					out.put(Integer.parseInt(parts[0].trim()), new int[]{
						kind.startsWith("b") ? 1 : 0,
						Integer.parseInt(parts[2].trim()),
						Integer.parseInt(parts[3].trim())});
				}
				catch (NumberFormatException e)
				{
					System.err.println("quests.csv: skipping unparseable row: " + line);
				}
			}
		}
		catch (IOException e)
		{
			System.err.println("quests.csv unreadable: " + e);
		}
		System.out.println("[shim] quest status table: " + out.size() + " row(s) from /quests.csv");
		return out;
	}

	// -- plumbing ------------------------------------------------------------------------------------

	/**
	 * The thread the shim runs the game loop on, matching GameEngine's contract: PathfinderConfig
	 * compares Thread.currentThread() against this to decide whether a refresh may run inline.
	 */
	public Thread getClientThread()
	{
		return ClientThread.getGameThread();
	}

	/** Deferred-run accessor, mirroring the injected ClientThread plugins get. */
	public ClientThread clientThread()
	{
		return clientThread;
	}

	/**
	 * False, and that is a READING rather than a placeholder -- which is why it does not go through
	 * {@link ShimSupport}. This client renders with its own software rasteriser: there is no GPU
	 * plugin here, no OpenGL context and nothing that would make it true, so no offset or cache dump
	 * would ever change the answer. Upstream callers use it to pick a draw path (GPU widens the
	 * clip plane and changes how far entities project); false selects the CPU path, which is the one
	 * this client actually runs.
	 */
	public boolean isGpu()
	{
		return false;
	}

	/** Convenience for the bridge: refresh the frame snapshot kewl already read this frame. */
	public static void newFrame()
	{
		kewl.api.Skills.newFrame();
	}

	/**
	 * The two Skill enums share their constant names; this is the whole mapping. Fail-soft on purpose:
	 * if the shim enum grows a constant the kewl enum has not caught up with, callers get a 0 rather
	 * than an IllegalArgumentException from the middle of a refresh sweep.
	 */
	private static kewl.api.Skill toKewl(Skill skill)
	{
		try
		{
			return kewl.api.Skill.valueOf(skill.name());
		}
		catch (IllegalArgumentException e)
		{
			return null;
		}
	}
}
