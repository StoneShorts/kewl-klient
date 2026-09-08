// Minimal stand-in for JetBrains' org.intellij.lang.annotations.MagicConstant, which vendored
// RuneLite sources use to tell the IDE which constants are legal. No runtime behaviour.
package org.intellij.lang.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.LOCAL_VARIABLE, ElementType.ANNOTATION_TYPE})
public @interface MagicConstant
{
	Class<?> valuesFromClass() default void.class;

	long[] values() default {};

	String[] stringValues() default {};
}
