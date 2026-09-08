// Vendored from RuneLite (BSD-2): documents how many args a client script takes. Documentation only.
package net.runelite.api;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Documented
@Retention(RetentionPolicy.SOURCE)
public @interface ScriptArguments
{
	int integer() default 0;

	int string() default 0;
}
