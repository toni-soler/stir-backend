package org.stir.observability;

import es.idynamicsax.idax.service.audit.AuditRequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Bridges the request/correlation id IDAX's own {@code AuditRequestContextFilter} already resolves
 * for every request into SLF4J's MDC, so STIR's own log lines can be tied back to one request
 * without STIR reimplementing correlation-id generation or response headers.
 */
@Component
public class CorrelationMdcInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        MDC.put("requestId", AuditRequestContext.getRequestId());
        MDC.put("correlationId", AuditRequestContext.getCorrelationId());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        MDC.remove("requestId");
        MDC.remove("correlationId");
    }
}
