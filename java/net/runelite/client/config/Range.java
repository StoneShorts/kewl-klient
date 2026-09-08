// Shim of net.runelite.client.config.Range (BSD-2, RuneLite).
package net.runelite.client.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Documented
public @interface Range
{
	int min() default 0;

	int max() default Integer.MAX_VALUE;
}
