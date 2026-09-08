// Shim of net.runelite.client.eventbus.Subscribe (BSD-2, RuneLite).
package net.runelite.client.eventbus;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Documented
public @interface Subscribe
{
	int priority() default 0;
}
