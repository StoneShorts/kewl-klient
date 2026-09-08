// Shim: the plugin declares one @Provides method for its config interface; KewlKlient's injector
// recognises it as the config factory (java/kewl/rl/Injector.java). No Guice.
package com.google.inject;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Provides
{
}
