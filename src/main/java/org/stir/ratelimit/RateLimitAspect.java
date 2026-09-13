package org.stir.ratelimit;

import es.idynamicsax.idax.security.CurrentUser;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;

/**
 * Single-node in-memory sliding-window limiter for a handful of especially sensitive STIR
 * endpoints (uploads, listing creation, economic actions) - deliberately not a distributed
 * limiter, since STIR runs as one backend instance in the current deployment shape.
 */
@Aspect @Component
public class RateLimitAspect {
    private static final Logger log = LoggerFactory.getLogger(RateLimitAspect.class);
    // Bounded in practice: one entry per (endpoint key, actor) pair that has ever called it, each
    // holding at most `limit` timestamps - acceptable for a single-instance pilot deployment.
    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    @Around("@annotation(rateLimited)")
    public Object enforce(ProceedingJoinPoint pjp, RateLimited rateLimited) throws Throwable {
        String key = rateLimited.key() + ":" + actorId(pjp.getArgs());
        long now = Instant.now().getEpochSecond();
        long windowStart = now - rateLimited.windowSeconds();
        Deque<Long> hits = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (hits) {
            while (!hits.isEmpty() && hits.peekFirst() < windowStart) hits.pollFirst();
            if (hits.size() >= rateLimited.limit()) {
                log.warn("Rate limit exceeded for {}", key);
                throw new ResponseStatusException(TOO_MANY_REQUESTS, "RATE_LIMITED: Too many requests, please wait a moment and try again");
            }
            hits.addLast(now);
        }
        return pjp.proceed();
    }

    private UUID actorId(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof CurrentUser user) return user.getUserId() != null ? user.getUserId() : user.getTenantId();
        }
        return null;
    }
}
