package org.stir.listing;

import es.idynamicsax.idax.security.CurrentUser;
import es.idynamicsax.idax.security.JwtAuthFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.stir.security.StirJwtAuthFilter;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PersonalEndpointAuthTest {
    @Test void validatedServicePrincipalIsRejectedAndContextCleared()throws Exception {
        var core=mock(JwtAuthFilter.class); var user=mock(CurrentUser.class);when(user.isService()).thenReturn(true);
        doAnswer(invocation->{SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,java.util.List.of()));((FilterChain)invocation.getArgument(2)).doFilter(invocation.getArgument(0),invocation.getArgument(1));return null;}).when(core).doFilter(any(),any(),any());
        var response=new MockHttpServletResponse();var downstream=mock(FilterChain.class);
        new StirJwtAuthFilter(core).doFilter(new MockHttpServletRequest("GET","/api/stir/tenants/test/listings"),response,downstream);
        assertEquals(403,response.getStatus());verifyNoInteractions(downstream);assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
