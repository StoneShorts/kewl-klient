// Shim of net.runelite.client.config.ConfigItem (BSD-2, RuneLite).
package net.runelite.client.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Documented
public @interface ConfigItem
{
	String keyName();

	String name();

	String description();

	int position() default -1;

	String section() default "";

	boolean hidden() default false;
}
