package org.stir.economic;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The only place STIR crosses into osTRIS: its public HTTP API, over the same bearer token the
 * caller already authenticated with (osTRIS validates it against the same shared IDAX Core public
 * key - no separate service credential, no SQL, no JPA repository shared between processes). STIR
 * never fabricates a signature: submitAuthorization only relays bytes the client itself signed.
 */
@Component
public class OstrisClient {
    private final RestClient client;

    public OstrisClient(org.springframework.core.env.Environment env) {
        this.client = RestClient.builder().baseUrl(env.getProperty("ostris.base-url", "http://ostris:8095")).build();
    }

    private String currentAuthorizationHeader() {
        var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) throw new IllegalStateException("No inbound request to relay authorization from");
        String header = attrs.getRequest().getHeader("Authorization");
        if (header == null || header.isBlank()) throw new StirOstrisException(401, "MISSING_AUTHORIZATION", "No bearer token to relay to osTRIS");
        return header;
    }
    private <T> T get(String uri, Class<T> type) {
        try {
            return client.get().uri(uri).header("Authorization", currentAuthorizationHeader()).retrieve().body(type);
        } catch (org.springframework.web.client.RestClientResponseException ex) { throw StirOstrisException.from(ex); }
    }
    private <T> T post(String uri, Object body, Class<T> type) {
        try {
            var spec = client.post().uri(uri).header("Authorization", currentAuthorizationHeader()).contentType(org.springframework.http.MediaType.APPLICATION_JSON);
            var response = body == null ? spec.retrieve() : spec.body(body).retrieve();
            // A terminal call is required even for a bodiless response: without one, retrieve()'s
            // default error handling never runs and a 4xx/5xx from osTRIS would pass as "success".
            if (type == Void.class) { response.toBodilessEntity(); return null; }
            return response.toEntity(type).getBody();
        } catch (org.springframework.web.client.RestClientResponseException ex) { throw StirOstrisException.from(ex); }
    }

    public UUID createCommunity(String name) {
        return post("/api/ostris/communities", Map.of("name", name), CommunityCreated.class).communityId();
    }
    public UnitCreated createUnit(UUID community, String code, int scale) {
        return post("/api/ostris/communities/" + community + "/units", Map.of("code", code, "scale", scale), UnitCreated.class);
    }
    public ActivationResult activateParticipant(UUID community, String displayName, UUID unit, String publicKeyBase64url, BigInteger creditFloor) {
        var body = new java.util.HashMap<String, Object>();
        body.put("displayName", displayName); body.put("unitId", unit.toString()); body.put("publicKeyBase64url", publicKeyBase64url);
        if (creditFloor != null) body.put("creditFloor", creditFloor);
        return post("/api/ostris/communities/" + community + "/participants/activate", body, ActivationResult.class);
    }
    public CommunityView community(UUID community) { return get("/api/ostris/communities/" + community, CommunityView.class); }
    public UnitView unit(UUID community, UUID unit) { return get("/api/ostris/communities/" + community + "/units/" + unit, UnitView.class); }
    public AccountView account(UUID community, UUID account) { return get("/api/ostris/communities/" + community + "/accounts/" + account, AccountView.class); }
    public TransactionStatus transaction(UUID id) { return get("/api/ostris/transactions/" + id, TransactionStatus.class); }

    public ProposalView propose(UUID community, UUID unit, UUID transactionId, List<Entry> entries, String contractualMetadataDigest) {
        var body = Map.of("communityId", community.toString(), "unitId", unit.toString(), "transactionId", transactionId.toString(),
            "purpose", "EXCHANGE", "entries", entries, "references", Map.of(), "contractualMetadataDigest", contractualMetadataDigest);
        return post("/api/ostris/transactions/proposals", body, ProposalView.class);
    }
    public void authorize(UUID transactionId, UUID accountId, UUID credentialId, String signatureBase64url) {
        post("/api/ostris/transactions/" + transactionId + "/authorizations",
            Map.of("accountId", accountId.toString(), "credentialId", credentialId.toString(), "signatureBase64url", signatureBase64url), Void.class);
    }
    public CommitReceipt commit(UUID transactionId) {
        return post("/api/ostris/transactions/" + transactionId + "/commit", null, CommitReceipt.class);
    }

    public record Entry(String accountId, String amount) {}
    public record CommunityCreated(UUID communityId) {}
    public record UnitCreated(UUID unitId, String code, int scale) {}
    public record ActivationResult(UUID participantId, UUID accountId, UUID controllerId, UUID credentialId, UUID unitId, String creditFloor, String balanceProjection) {}
    public record CommunityView(UUID communityId, String name, long nextSequence) {}
    public record UnitView(UUID unitId, String code, int scale) {}
    public record AccountView(UUID accountId, UUID unitId, UUID participantId, String accountType, String name, String creditFloor, String balanceProjection, String riskState) {}
    public record ProposalView(UUID transactionId, String authorizationDigest, String status) {}
    public record CommitReceipt(UUID transactionId, long communitySequence, String protocolDigest, java.time.Instant committedAt) {}
    public record TransactionStatus(UUID transactionId, UUID communityId, UUID unitId, String purpose, String wireFormat,
        String ostrisCoreVersion, String authorizationPayload, String authorizationDigest, String status,
        Long committedSequence, String protocolDigest, java.time.Instant committedAt, List<UUID> authorizedAccountIds) {}
}
