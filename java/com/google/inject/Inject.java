// Shim: the plugin sources use @Inject purely as a marker for KewlKlient's own reflection injector
// (java/kewl/rl/Injector.java). No Guice -- just enough annotation to keep the sources unmodified.
package com.google.inject;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.CONSTRUCTOR, ElementType.FIELD, ElementType.METHOD})
public @interface Inject
{
	boolean optional() default false;
}
