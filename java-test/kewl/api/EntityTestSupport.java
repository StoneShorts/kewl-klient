// Test-only factory for kewl.api.Entity, whose constructor is package-private (Game.refresh is the
// only production caller). Lives in the kewl.api package for that reason; no natives are touched.
package kewl.api;

public final class EntityTestSupport
{
	private EntityTestSupport()
	{
	}

	/** Same argument order as nEntities' ten-int stride: uid, sceneX, sceneY, isPlayer, id, anim, orient, fineX, fineH, fineY. */
	public static Entity of(int uid, int sceneX, int sceneY, boolean player, int id, int anim, int orient,
		int fineX, int fineH, int fineY)
	{
		return new Entity(uid, sceneX, sceneY, player, id, anim, orient, fineX, fineH, fineY);
	}

	/** An NPC on a tile, fine position at the tile centre, at datum height. */
	public static Entity npc(int uid, int id, int sceneX, int sceneY)
	{
		return of(uid, sceneX, sceneY, false, id, -1, 0, (sceneX << 7) + 64, 0, (sceneY << 7) + 64);
	}

	/** Another player on a tile. */
	public static Entity player(int uid, int sceneX, int sceneY)
	{
		return of(uid, sceneX, sceneY, true, -1, -1, 0, (sceneX << 7) + 64, 0, (sceneY << 7) + 64);
	}
}
