// Shim of net.runelite.client.config.ConfigGroup (BSD-2, RuneLite).
package net.runelite.client.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Documented
public @interface ConfigGroup
{
	String value();

	boolean withinPlugin() default false;
}
