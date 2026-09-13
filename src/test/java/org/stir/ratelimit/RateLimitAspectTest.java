package org.stir.ratelimit;

import es.idynamicsax.idax.security.CurrentUser;
import java.util.Set;
import java.util.UUID;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class RateLimitAspectTest {
    @RateLimited(key = "probe", limit = 2, windowSeconds = 60)
    private void annotatedProbe() {}

    private final RateLimited annotation;
    { try { annotation = getClass().getDeclaredMethod("annotatedProbe").getAnnotation(RateLimited.class); }
      catch (NoSuchMethodException e) { throw new RuntimeException(e); } }

    private ProceedingJoinPoint joinPointFor(CurrentUser user) throws Throwable {
        var pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getArgs()).thenReturn(new Object[] { user });
        when(pjp.proceed()).thenReturn("ok");
        return pjp;
    }

    private CurrentUser user(UUID id) { return new CurrentUser(id, "user@stir.test", UUID.randomUUID(), false, Set.of()); }

    @Test void allowsCallsWithinTheLimit() throws Throwable {
        var aspect = new RateLimitAspect();
        var actor = user(UUID.randomUUID());
        assertThat(aspect.enforce(joinPointFor(actor), annotation)).isEqualTo("ok");
        assertThat(aspect.enforce(joinPointFor(actor), annotation)).isEqualTo("ok");
    }

    @Test void rejectsTheCallThatExceedsTheLimitWithAPlainCodedMessage() throws Throwable {
        var aspect = new RateLimitAspect();
        var actor = user(UUID.randomUUID());
        aspect.enforce(joinPointFor(actor), annotation);
        aspect.enforce(joinPointFor(actor), annotation);
        assertThatThrownBy(() -> aspect.enforce(joinPointFor(actor), annotation))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("RATE_LIMITED:");
    }

    @Test void tracksDifferentActorsIndependently() throws Throwable {
        var aspect = new RateLimitAspect();
        var alice = user(UUID.randomUUID());
        var bob = user(UUID.randomUUID());
        aspect.enforce(joinPointFor(alice), annotation);
        aspect.enforce(joinPointFor(alice), annotation);
        assertThatThrownBy(() -> aspect.enforce(joinPointFor(alice), annotation)).isInstanceOf(ResponseStatusException.class);
        assertThat(aspect.enforce(joinPointFor(bob), annotation)).isEqualTo("ok");
    }
}
