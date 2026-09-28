package org.stir.reference;

import es.idynamicsax.idax.security.CurrentUser;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

/** Registration-ceremony lifecycle for WebAuthn/hardware-backed Seven Keys credentials
 * (WEBAUTHN_HARDWARE_CUSTODY.md). Owns exactly two things: issuing single-use, expiring,
 * actor/authority/context-bound registration challenges, and verifying a completed registration
 * response into a stored credential row. It never touches constitutional_seat/
 * constitutional_credential_history itself - binding a verified credential to a seat, the Guardian,
 * or a rotation stays entirely inside SevenKeysService's existing proposal/activation flow, exactly
 * the same as a locally-generated Ed25519 public key is only ever "used" once it is submitted as
 * part of a bootstrap/proposal payload. This keeps the one governance authority boundary
 * (SevenKeysService) as the only place a credential ever becomes constitutionally meaningful. */
@Service @Transactional
public class WebAuthnCredentialService {
    private final JdbcTemplate db;
    @Value("${stir.webauthn.rp-id}") String rpId;
    @Value("${stir.webauthn.rp-name}") String rpName;
    @Value("${stir.webauthn.allowed-origins}") String allowedOriginsCsv;
    private static final long CHALLENGE_TTL_SECONDS = 300;

    public WebAuthnCredentialService(JdbcTemplate db) { this.db = db; }

    Set<String> allowedOrigins() { return Set.of(allowedOriginsCsv.split(",")); }

    public record RegistrationOptions(String challenge, String rpId, String rpName, String userHandle, String userName) {}
    public record RegistrationRequest(UUID authorityId, String context, String userName) {}
    public record RegistrationResponse(UUID credentialId, String attestationObject, String clientDataJson,
                                        String webauthnCredentialId, boolean userVerificationRequired) {}
    public record RegisteredCredential(String algorithm, String publicKeyBase64url) {}

    /** `context` names what this registration is for (e.g. "seat-3", "guardian",
     * "seat-3-incoming" during a pending rotation) purely as an anti-mixup label the caller must
     * echo back unchanged at finish time - it is never itself constitutional authority. */
    public RegistrationOptions beginRegistration(CurrentUser user, RegistrationRequest input) {
        UUID actor = ReferenceService.actor(user);
        if (input == null || input.authorityId() == null || input.context() == null || input.context().isBlank() || input.context().length() > 60)
            throw new ResponseStatusException(BAD_REQUEST, "Incomplete registration request");
        byte[] challengeBytes = new byte[32];
        new SecureRandom().nextBytes(challengeBytes);
        String challenge = WebAuthnCrypto.base64url(challengeBytes);
        byte[] userHandleBytes = new byte[32];
        new SecureRandom().nextBytes(userHandleBytes);
        db.update("insert into stir.constitutional_webauthn_challenge values (?,?,?,?,'REGISTRATION',?,?,?,null,?)",
            UUID.randomUUID(), ReferenceService.tenant(), input.authorityId(), actor, input.context(), challenge,
            Timestamp.from(Instant.now().plusSeconds(CHALLENGE_TTL_SECONDS)), Timestamp.from(Instant.now()));
        // The WebAuthn user handle is a purely protocol-level opaque identifier (spec: MUST NOT
        // carry PII) - a fresh random value per ceremony, never the actor's own user id or email.
        return new RegistrationOptions(challenge, rpId, rpName, WebAuthnCrypto.base64url(userHandleBytes),
            input.userName() == null || input.userName().isBlank() ? input.context() : input.userName());
    }

    public RegisteredCredential finishRegistration(CurrentUser user, RegistrationRequest input, RegistrationResponse response) {
        UUID actor = ReferenceService.actor(user);
        if (response == null || response.credentialId() == null || response.attestationObject() == null || response.clientDataJson() == null)
            throw new ResponseStatusException(BAD_REQUEST, "Incomplete registration response");
        var rows = db.queryForList("select id,challenge from stir.constitutional_webauthn_challenge "+
            "where tenant_id=? and authority_id=? and actor_id=? and purpose='REGISTRATION' and context=? "+
            "and consumed_at is null and expires_at>now() order by created_at desc limit 1",
            ReferenceService.tenant(), input.authorityId(), actor, input.context());
        if (rows.isEmpty()) throw new ResponseStatusException(CONFLICT, "No pending or expired registration challenge");
        var challengeRow = rows.getFirst();
        int consumed = db.update("update stir.constitutional_webauthn_challenge set consumed_at=now() where tenant_id=? and id=? and consumed_at is null",
            ReferenceService.tenant(), challengeRow.get("id"));
        if (consumed == 0) throw new ResponseStatusException(CONFLICT, "Registration challenge already used");
        byte[] expectedChallenge = WebAuthnCrypto.decodeBase64url((String) challengeRow.get("challenge"), "storedChallenge");
        byte[] attestationObject = WebAuthnCrypto.decodeBase64url(response.attestationObject(), "attestationObject");
        byte[] clientDataJson = WebAuthnCrypto.decodeBase64url(response.clientDataJson(), "clientDataJson");
        WebAuthnCrypto.RegistrationResult result;
        try { result = WebAuthnCrypto.verifyRegistration(attestationObject, clientDataJson, expectedChallenge, rpId, allowedOrigins()); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(BAD_REQUEST, "Invalid WebAuthn registration: " + e.getMessage()); }
        if (!WebAuthnCrypto.base64url(result.webauthnCredentialId()).equals(response.webauthnCredentialId()))
            throw new ResponseStatusException(BAD_REQUEST, "Credential id mismatch between response and attestation");
        if (response.userVerificationRequired() && !result.userVerified())
            throw new ResponseStatusException(BAD_REQUEST, "User verification was requested but not performed at registration");
        if (!db.queryForList("select 1 from stir.constitutional_webauthn_credential where tenant_id=? and (credential_id=? or webauthn_credential_id=?)",
            ReferenceService.tenant(), response.credentialId(), response.webauthnCredentialId()).isEmpty())
            throw new ResponseStatusException(CONFLICT, "Credential already registered");
        db.update("insert into stir.constitutional_webauthn_credential values (?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID(), ReferenceService.tenant(), input.authorityId(), response.credentialId(),
            WebAuthnCrypto.base64url(result.webauthnCredentialId()), rpId, result.signCount(), response.userVerificationRequired(), Timestamp.from(Instant.now()));
        return new RegisteredCredential(result.algorithm(), WebAuthnCrypto.base64url(result.coseKeyCbor()));
    }

    /** Looked up by SevenKeysService when verifying an assertion against a WEBAUTHN-type seat/
     * guardian credential - the extra material a signature envelope alone cannot carry (which
     * WebAuthn credential id to expect, the last known sign_count, whether this credential's own
     * registration required user verification). */
    Map<String, Object> credentialFor(UUID credentialId) {
        var rows = db.queryForList("select webauthn_credential_id,sign_count,user_verification_required from stir.constitutional_webauthn_credential where tenant_id=? and credential_id=?",
            ReferenceService.tenant(), credentialId);
        if (rows.isEmpty()) throw new ResponseStatusException(CONFLICT, "No WebAuthn material registered for this credential");
        return rows.getFirst();
    }

    void recordSignCount(UUID credentialId, long newSignCount) {
        db.update("update stir.constitutional_webauthn_credential set sign_count=? where tenant_id=? and credential_id=?",
            newSignCount, ReferenceService.tenant(), credentialId);
    }
}
