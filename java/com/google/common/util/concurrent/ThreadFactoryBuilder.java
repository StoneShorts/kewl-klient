// Shim: the plugin names its pathfinder thread with a Guava builder. This is the whole used surface.
package com.google.common.util.concurrent;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ThreadFactory;

public final class ThreadFactoryBuilder
{
	private String nameFormat;

	public ThreadFactoryBuilder setNameFormat(String nameFormat)
	{
		this.nameFormat = nameFormat;
		return this;
	}

	public ThreadFactory build()
	{
		String format = nameFormat;
		AtomicInteger counter = new AtomicInteger();
		return r ->
		{
			Thread t = new Thread(r, format == null ? "thread-" + counter.incrementAndGet()
				: String.format(format, counter.incrementAndGet()));
			t.setDaemon(true);
			return t;
		};
	}
}
