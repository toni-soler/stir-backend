// Uses the public IDAX Core authentication contract; see NOTICE.
package org.stir.security;
import es.idynamicsax.idax.tenant.TenantContext;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Core validates the bearer; Core TenantContextFilter resolves tenant membership. */
@Component
public class StirJwtAuthFilter extends OncePerRequestFilter {
    private final es.idynamicsax.idax.security.JwtAuthFilter core;
    public StirJwtAuthFilter(es.idynamicsax.idax.security.JwtAuthFilter core) { this.core=core; }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        try {
            core.doFilter(request,response,(req,res)->{
                var auth=SecurityContextHolder.getContext().getAuthentication();
                if(auth!=null && auth.getPrincipal() instanceof es.idynamicsax.idax.security.CurrentUser user && user.isService()) {
                    response.sendError(403); return;
                }
                chain.doFilter(req,res);
            });
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }
}
