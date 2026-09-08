// Shim of net.runelite.client.callback.ClientThread (BSD-2, RuneLite).
//
// In RuneLite this hops onto the client's own thread. Here everything already runs on KewlKlient's
// overlay frame thread, so invoke runs inline from there and queues otherwise (the pathfinder worker
// is the only caller from another thread; the queue drains at the top of the next frame).
package net.runelite.client.callback;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class ClientThread
{
	private static final List<Runnable> queue = new CopyOnWriteArrayList<>();
	private static volatile Thread gameThread;

	/** Called once by the bridge so isClientThread() can answer honestly. */
	public static void setGameThread(Thread t)
	{
		gameThread = t;
	}

	/** The thread the shim treats as the game thread; Client.getClientThread() hands this out. */
	public static Thread getGameThread()
	{
		return gameThread;
	}

	public static boolean isClientThread()
	{
		return Thread.currentThread() == gameThread;
	}

	public void invokeLater(Runnable r)
	{
		if (isClientThread())
		{
			r.run();
			return;
		}
		queue.add(r);
	}

	public void invoke(Runnable r)
	{
		invokeLater(r);
	}

	/**
	 * Drain whatever queued from other threads. Called once per frame by the bridge.
	 *
	 * <p>Each runnable is removed just before it runs, and one throwing does not drop the rest: the
	 * first thing the plugin queues after a refresh is "construct and submit the pathfinder", so a
	 * callback that throws mid-batch used to silently cancel every callback behind it -- including the
	 * one that would have started the pathfinding.</p>
	 */
	public static void drain()
	{
		for (Runnable r : queue)
		{
			if (!queue.remove(r))
			{
				continue; // another drain got it; the queue can only be drained from one thread
			}
			try
			{
				r.run();
			}
			catch (Throwable t)
			{
				System.err.println("ClientThread callback threw: " + t);
				t.printStackTrace();
			}
		}
	}
}
