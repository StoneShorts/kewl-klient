// Minimal stand-in for javax.inject.Inject (JSR-330), which Hub plugins annotate constructors and
// fields with. kewl.rl.Injector understands it; there is no real DI container here.
package javax.inject;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD})
public @interface Inject
{
}
