package org.stir.ratelimit;

import java.lang.annotation.*;

/** Marks a controller method as subject to {@link RateLimitAspect}'s per-actor sliding window. */
@Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME)
public @interface RateLimited {
    String key();
    int limit();
    int windowSeconds();
}
