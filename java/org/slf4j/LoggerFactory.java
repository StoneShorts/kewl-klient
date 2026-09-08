// Minimal stand-in for org.slf4j.LoggerFactory: every logger is the same stderr writer.
package org.slf4j;

public final class LoggerFactory
{
	private LoggerFactory()
	{
	}

	public static Logger getLogger(Class<?> type)
	{
		return getLogger(type == null ? "unknown" : type.getSimpleName());
	}

	public static Logger getLogger(String name)
	{
		return new Logger()
		{
			@Override
			public void debug(String format)
			{
			}

			@Override
			public void debug(String format, Object arg)
			{
			}

			@Override
			public void debug(String format, Object... args)
			{
			}

			@Override
			public void info(String format)
			{
			}

			@Override
			public void info(String format, Object arg)
			{
			}

			@Override
			public void info(String format, Object... args)
			{
			}

			@Override
			public void warn(String format)
			{
				System.err.println("[warn] " + name + ": " + format);
			}

			@Override
			public void warn(String format, Object arg)
			{
				System.err.println("[warn] " + name + ": " + fill(format, arg));
			}

			@Override
			public void warn(String format, Object... args)
			{
				System.err.println("[warn] " + name + ": " + fillAll(format, args));
			}

			@Override
			public void error(String format)
			{
				System.err.println("[error] " + name + ": " + format);
			}

			@Override
			public void error(String format, Object arg)
			{
				System.err.println("[error] " + name + ": " + fill(format, arg));
			}

			@Override
			public void error(String format, Object... args)
			{
				System.err.println("[error] " + name + ": " + fillAll(format, args));
			}

			@Override
			public void error(String format, Throwable t)
			{
				System.err.println("[error] " + name + ": " + format);
				t.printStackTrace();
			}

			@Override
			public void error(String format, Object argOne, Object argTwo)
			{
				error(format, new Object[]{argOne, argTwo});
			}

			private String fill(String format, Object arg)
			{
				int at = format.indexOf("{}");
				if (at < 0)
				{
					return format;
				}
				return format.substring(0, at) + arg + format.substring(at + 2);
			}

			private String fillAll(String format, Object... args)
			{
				StringBuilder sb = new StringBuilder(format);
				for (Object a : args)
				{
					int at = sb.indexOf("{}");
					if (at < 0)
					{
						break;
					}
					sb.replace(at, at + 2, String.valueOf(a));
				}
				return sb.toString();
			}
		};
	}
}
