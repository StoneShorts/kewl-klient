// Shim of net.runelite.client.config.Alpha (BSD-2, RuneLite).
package net.runelite.client.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Documented
public @interface Alpha
{
	float min() default 0f;

	float max() default 1f;
}
