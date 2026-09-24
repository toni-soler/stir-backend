package org.stir.economic;

import com.sun.net.httpserver.HttpServer;
import es.idynamicsax.idax.tenant.TenantContext;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import static org.junit.jupiter.api.Assertions.*;

/**
 * osTRIS rejects an inbound call that carries no resolvable tenant (see
 * OstrisJwtAuthFilter.resolveTenant). STIR's own TenantContextFilter resolves the active tenant
 * for every inbound request - including ones where the caller's JWT itself has no tenant claim,
 * like the admin-bootstrap superuser identity - so OstrisClient must forward that resolution on
 * every outbound call, not just the bearer token.
 */
class OstrisClientHttpTest {
    private HttpServer server;

    @AfterEach void cleanup() {
        if (server != null) server.stop(0);
        RequestContextHolder.resetRequestAttributes();
        TenantContext.clear();
    }

    @Test void forwardsTheActiveTenantAsAHeader() throws Exception {
        UUID tenant = UUID.randomUUID();
        AtomicReference<String> tenantHeader = new AtomicReference<>();
        AtomicReference<String> authHeader = new AtomicReference<>();
        OstrisClient client = client(exchange -> {
            tenantHeader.set(exchange.getRequestHeaders().getFirst("X-Tenant"));
            authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"communityId\":\"" + UUID.randomUUID() + "\"}");
        });
        inboundRequestWithBearerToken("test-token");
        TenantContext.set(new TenantContext(tenant, null, UUID.randomUUID(), "test", TenantContext.DbRole.IDAX_APP));

        client.createCommunity("Test");

        assertEquals(tenant.toString(), tenantHeader.get());
        assertEquals("Bearer test-token", authHeader.get());
    }

    @Test void omitsTheTenantHeaderWhenNoTenantIsResolved() throws Exception {
        AtomicReference<String> tenantHeader = new AtomicReference<>();
        AtomicReference<Boolean> tenantHeaderPresent = new AtomicReference<>();
        OstrisClient client = client(exchange -> {
            tenantHeaderPresent.set(exchange.getRequestHeaders().containsKey("X-Tenant"));
            tenantHeader.set(exchange.getRequestHeaders().getFirst("X-Tenant"));
            respond(exchange, 200, "{\"communityId\":\"" + UUID.randomUUID() + "\"}");
        });
        inboundRequestWithBearerToken("test-token");

        client.createCommunity("Test");

        assertFalse(Boolean.TRUE.equals(tenantHeaderPresent.get()), "X-Tenant must be absent, not null-valued");
        assertNull(tenantHeader.get());
    }

    private void inboundRequestWithBearerToken(String token) {
        var request = new MockHttpServletRequest("POST", "/api/stir/economic/activate");
        request.addHeader("Authorization", "Bearer " + token);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private OstrisClient client(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try { handler.handle(exchange); } finally { exchange.close(); }
        });
        server.start();
        var env = new MockEnvironment();
        env.setProperty("ostris.base-url", "http://127.0.0.1:" + server.getAddress().getPort());
        return new OstrisClient(env);
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
