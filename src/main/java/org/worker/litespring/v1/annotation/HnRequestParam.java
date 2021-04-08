package org.worker.litespring.v1.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Documented
public @interface HnRequestParam {
    String value() default "";
}
