// Shim-only (not an upstream type): the one place the shim admits what it cannot answer.
//
// THE PROBLEM THIS EXISTS FOR. A stub that returns 0 is indistinguishable from a real 0. "The camera
// shows zeros" was a true bug report that nothing in the client could turn into an actionable
// sentence, because a hardcoded `return 0` looks exactly like a camera that happens to be pointing
// north. Every accessor in this shim that hands back a placeholder now says so through here, once,
// and the answer to that report becomes a line somebody can act on:
//
//     [shim] Client.getCameraPitch needs an offset: reads -1 (unknown, NOT a level camera) ...
//
// THE CONTRACT.
//   - {@link #note} is called BEFORE the placeholder is returned, on every call, and is cheap: one
//     concurrent-map probe on a constant string. It logs the FIRST time an accessor is hit and never
//     again, so a getter called per NPC per frame costs one line per session, not one per frame.
//   - Only accessors that are actually CALLED get registered. The registry is therefore scoped to
//     what the running plugins really touch, which is what makes {@link #status} short enough to put
//     in a control-panel line.
//   - Nothing here ever throws. A diagnostic that can break a frame is worse than no diagnostic.
//
// WHAT IT IS NOT. It is not a to-do list of the whole shim: an accessor that is fully wired (varps,
// widgets, game state, the camera yaw and pitch) does not appear here at all, and an accessor that
// appears here is not necessarily wrong -- {@link Kind#UNSUPPORTABLE} entries are the ones nobody can
// fix on this build, and saying so plainly is the whole point.
package net.runelite.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ShimSupport
{
	private ShimSupport()
	{
	}

	/** Why an accessor cannot answer, which is also what would have to happen for it to start. */
	public enum Kind
	{
		/**
		 * A memory offset nobody has derived yet on client-240-6. Fixable: run the deob workflow,
		 * add the offset to client/offsets.hpp and a native to kewl.Natives, fill in the method.
		 */
		NEEDS_OFFSET("needs an offset"),

		/**
		 * The value lives in the game CACHE, not in memory -- item names, NPC definition fields.
		 * Fixable without touching the client at all: bundle the table next to VarbitTable.
		 */
		NEEDS_CACHE_DATA("needs cache data"),

		/**
		 * There is nothing to read on this build and no way to derive it. Not a to-do: an entry here
		 * has been looked for and shown not to exist, and the reason says where that was established.
		 */
		UNSUPPORTABLE("is unsupportable on this build");

		private final String phrase;

		Kind(String phrase)
		{
			this.phrase = phrase;
		}

		public String phrase()
		{
			return phrase;
		}
	}

	/** One registered gap. Immutable; the registry holds one per accessor for the session. */
	public static final class Gap
	{
		private final String accessor;
		private final Kind kind;
		private final String reason;

		Gap(String accessor, Kind kind, String reason)
		{
			this.accessor = accessor;
			this.kind = kind;
			this.reason = reason;
		}

		public String accessor()
		{
			return accessor;
		}

		public Kind kind()
		{
			return kind;
		}

		/** What it returns instead and why, in one sentence -- the text the log line carries. */
		public String reason()
		{
			return reason;
		}

		@Override
		public String toString()
		{
			return accessor + " " + kind.phrase() + ": " + reason;
		}
	}

	/**
	 * A hash map, not a sorted one: {@link #note} is on the hot path (an accessor called per NPC per
	 * frame goes through it), so the steady state has to be ONE hash probe and no allocation. The
	 * ordering {@link #report()} needs is done there, where it costs nothing -- it runs once.
	 * Concurrent because overlays render on the frame thread while the pathfinder worker reads the
	 * same accessors.
	 */
	private static final Map<String, Gap> GAPS = new ConcurrentHashMap<>();

	/** Set false by tests; the registry is still filled, only the printing stops. */
	private static volatile boolean logging = true;

	/**
	 * Record that {@code accessor} could not answer, and say why. Call it immediately before
	 * returning the placeholder:
	 *
	 * <pre>
	 * public int getCombatLevel()
	 * {
	 *     ShimSupport.note("Actor.getCombatLevel", Kind.NEEDS_OFFSET,
	 *         "reads 0 (upstream's \"no combat level\"): no NPC-definition level offset is derived");
	 *     return 0;
	 * }
	 * </pre>
	 *
	 * @param accessor {@code Class.method}, no parentheses -- the name a user would grep for
	 * @param kind     what would have to happen for it to start working
	 * @param reason   what it returns INSTEAD and why, in one sentence; the placeholder must be named
	 *                 in it, because "not live" without "reads 0" does not explain the zeros
	 */
	public static void note(String accessor, Kind kind, String reason)
	{
		if (accessor == null || kind == null)
		{
			return; // a diagnostic must not be the thing that throws
		}
		// The steady state, and the only branch a per-frame getter ever takes: one hash probe, out.
		if (GAPS.containsKey(accessor))
		{
			return;
		}
		if (GAPS.putIfAbsent(accessor, new Gap(accessor, kind, reason == null ? "" : reason)) == null
			&& logging)
		{
			// Inside the putIfAbsent race winner, so exactly one line however many threads arrive at
			// the same accessor on the same frame.
			System.out.println("[shim] " + accessor + " " + kind.phrase() + ": " + reason);
		}
	}

	/** Every gap hit so far this session, sorted by accessor name so a report reads the same twice. */
	public static List<Gap> gaps()
	{
		List<Gap> all = new ArrayList<>(GAPS.values());
		all.sort(Comparator.comparing(Gap::accessor));
		return all;
	}

	/**
	 * What the shim could not answer for a named accessor, or null if it has answered every time it
	 * was asked. This is the lookup that turns "camera shows zeros" into a sentence: ask for
	 * {@code "Client.getMinimapZoom"} and either get the reason or learn that the accessor is live
	 * and the zero is real data.
	 */
	public static String reasonFor(String accessor)
	{
		Gap gap = GAPS.get(accessor);
		return gap == null ? null : gap.reason();
	}

	/** True once {@code accessor} has had to hand back a placeholder this session. */
	public static boolean isGap(String accessor)
	{
		return GAPS.containsKey(accessor);
	}

	/**
	 * One short line for a control panel: how many placeholders the running plugins have actually
	 * hit, and the first few by name. Empty when nothing has been stubbed, so a panel can print it
	 * unconditionally and show nothing on a good day.
	 *
	 * <p>Names rather than a bare count on purpose. "3 shim gaps" tells a user they have a problem
	 * and not which; "combat level, friend list, minimap zoom" tells them which of their overlays is
	 * the one drawing nonsense.</p>
	 */
	public static String status()
	{
		return status(3);
	}

	/** {@link #status()} with an explicit cap on how many accessors are named. */
	static String status(int maxNamed)
	{
		List<Gap> all = gaps();
		if (all.isEmpty())
		{
			return "";
		}
		StringBuilder sb = new StringBuilder();
		sb.append("shim: ").append(all.size()).append(all.size() == 1 ? " stub hit (" : " stubs hit (");
		int named = Math.min(Math.max(0, maxNamed), all.size());
		for (int i = 0; i < named; i++)
		{
			if (i > 0)
			{
				sb.append(", ");
			}
			sb.append(shortName(all.get(i).accessor()));
		}
		if (named < all.size())
		{
			sb.append(", +").append(all.size() - named).append(" more");
		}
		return sb.append(')').toString();
	}

	/** {@code Actor.getCombatLevel} -> {@code getCombatLevel}; the class is noise in a 40-char line. */
	private static String shortName(String accessor)
	{
		int dot = accessor.lastIndexOf('.');
		return dot < 0 ? accessor : accessor.substring(dot + 1);
	}

	/**
	 * The full account, one gap per line, grouped by kind. Printed once per session by kewl.rl so the
	 * KEWL_LOG trace of any run carries the list of everything the shim faked in it -- which is the
	 * document a bug report like "the camera shows zeros" should be answered from.
	 */
	public static String report()
	{
		List<Gap> all = gaps();
		if (all.isEmpty())
		{
			return "[shim] nothing stubbed so far this session";
		}
		StringBuilder sb = new StringBuilder("[shim] ").append(all.size())
			.append(" accessor(s) handed back a placeholder this session:");
		for (Kind kind : Kind.values())
		{
			boolean header = false;
			for (Gap gap : all)
			{
				if (gap.kind() != kind)
				{
					continue;
				}
				if (!header)
				{
					header = true;
					sb.append("\n  -- ").append(kind.phrase()).append(" --");
				}
				sb.append("\n  ").append(gap.accessor()).append(": ").append(gap.reason());
			}
		}
		return sb.toString();
	}

	// -- test seam -------------------------------------------------------------------------------

	/** Tests only: forget every gap, so each test starts from an empty registry. */
	static void reset()
	{
		GAPS.clear();
		logging = true;
	}

	/** Tests only: keep recording, stop printing, so a suite does not spray the build log. */
	static void setLogging(boolean enabled)
	{
		logging = enabled;
	}
}
