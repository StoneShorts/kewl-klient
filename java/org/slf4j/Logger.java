// Minimal stand-in for org.slf4j.Logger, generated into ported plugins by lombok's @Slf4j. Logs to
// stderr with the same {} substitution for the handful of call shapes the plugins use.
package org.slf4j;

public interface Logger
{
	void debug(String format);

	void debug(String format, Object arg);

	void debug(String format, Object... args);

	void info(String format);

	void info(String format, Object arg);

	void info(String format, Object... args);

	void warn(String format);

	void warn(String format, Object arg);

	void warn(String format, Object... args);

	void error(String format);

	void error(String format, Object arg);

	void error(String format, Object... args);

	void error(String format, Throwable t);

	void error(String format, Object argOne, Object argTwo);
}
