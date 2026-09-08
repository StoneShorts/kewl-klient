// Shim of net.runelite.client.plugins.PluginDescriptor (BSD-2, RuneLite). Only the fields the
// ported plugins actually set are read; the rest exist so sources compile unmodified.
package net.runelite.client.plugins;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Documented
public @interface PluginDescriptor
{
	String name() default "";

	String description() default "";

	String[] tags() default {};

	boolean loadInDevBuild() default true;

	boolean disabledByDefault() default false;
}
