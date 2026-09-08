// Shim of net.runelite.client.config.Units (BSD-2, RuneLite). Read for the panel label; otherwise
// ignored.
package net.runelite.client.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Documented
public @interface Units
{
	String value();

	// The label constants plugins put here; TICKS is the one shortest-path uses.
	String TICKS = " ticks";
	String POINTS = " points";
	String SECONDS = " seconds";
	String MINUTES = " minutes";
	String MS = "ms";
	String K = "K";
	String M = "M";
}
