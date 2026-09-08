// Minimal stand-in for jsr305's javax.annotation.Nullable, which vendored RuneLite sources
// annotate parameters with. No runtime behaviour; it exists so the sources compile unmodified.
package javax.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE_USE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
public @interface Nullable
{
}
