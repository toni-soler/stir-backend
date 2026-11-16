package org.stir.economic;

import es.idynamicsax.idax.security.CurrentUser;
import es.idynamicsax.idax.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

/**
 * Device/credential lifecycle (section 8 of the 0.4 brief): a participant may authorize additional
 * browsers/devices as signers for their SAME already-active osTRIS controller, and revoke one later
 * - all through osTRIS's own credential/controller_credential_binding contract (never a new
 * controller, never touching AccountControlPolicy's threshold). Private keys never pass through
 * this server: the client generates its own keypair and only ever sends the public key.
 */
@Service @Transactional
public class DeviceCredentialService {
    private static final Logger log = LoggerFactory.getLogger(DeviceCredentialService.class);
    private final ParticipantEconomicBindingRepository bindings;
    private final ParticipantDeviceCredentialRepository devices;
    private final OstrisClient ostris;

    public DeviceCredentialService(ParticipantEconomicBindingRepository bindings, ParticipantDeviceCredentialRepository devices, OstrisClient ostris) {
        this.bindings = bindings; this.devices = devices; this.ostris = ostris;
    }

    private UUID tenant() {
        var context = TenantContext.get();
        if (context == null || context.getTenantId() == null) throw new AccessDeniedException("Tenant required");
        return context.getTenantId();
    }
    private UUID actor(CurrentUser user) {
        if (user == null || user.isService() || user.getUserId() == null) throw new AccessDeniedException("User required");
        return user.getUserId();
    }
    private ParticipantEconomicBinding myBinding(UUID tenant, UUID actor) {
        return bindings.findByTenantIdAndUserId(tenant, actor)
            .orElseThrow(() -> new ResponseStatusException(CONFLICT, "Activate economic exchange before managing devices"));
    }

    public DeviceView addDevice(CurrentUser user, String label, String publicKeyBase64url) {
        UUID tenant = tenant(), actor = actor(user);
        var binding = myBinding(tenant, actor);
        OstrisClient.CredentialAdded added;
        try { added = ostris.addCredential(binding.communityId, binding.controllerId, publicKeyBase64url); }
        catch (StirOstrisException ex) { throw approvalAware(ex); }
        var device = new ParticipantDeviceCredential();
        device.id = UUID.randomUUID(); device.tenantId = tenant; device.userId = actor;
        device.ostrisCredentialId = added.credentialId(); device.label = label.trim(); device.createdAt = Instant.now();
        devices.saveAndFlush(device);
        log.info("Device credential {} added for controller {}, tenant={} actor={}", added.credentialId(), binding.controllerId, tenant, actor);
        return new DeviceView(added.credentialId(), device.label, false, device.createdAt);
    }

    /** osTRIS discovery, not the local label table, decides whether the credential is the
     * caller's to revoke: the FIRST device (bound during activate(), never through addDevice())
     * has no row here at all, yet must be revocable just like any later device. */
    public void revokeDevice(CurrentUser user, UUID ostrisCredentialId) {
        UUID tenant = tenant(), actor = actor(user);
        var binding = myBinding(tenant, actor);
        boolean ownedByCaller = ostris.credentialsForController(binding.communityId, binding.controllerId).stream()
            .anyMatch(c -> c.credentialId().equals(ostrisCredentialId));
        if (!ownedByCaller) throw new ResponseStatusException(NOT_FOUND, "Device not found");
        try { ostris.revokeCredential(binding.communityId, ostrisCredentialId); }
        catch (StirOstrisException ex) { throw approvalAware(ex); }
        log.info("Device credential {} revoked, tenant={} actor={}", ostrisCredentialId, tenant, actor);
    }

    /** osTRIS discovery is the sole source of truth for active/revoked; STIR only adds the label. */
    public List<DeviceView> myDevices(CurrentUser user) {
        UUID tenant = tenant(), actor = actor(user);
        var binding = myBinding(tenant, actor);
        var labels = devices.findByTenantIdAndUserId(tenant, actor).stream()
            .collect(java.util.stream.Collectors.toMap(d -> d.ostrisCredentialId, d -> d));
        return ostris.credentialsForController(binding.communityId, binding.controllerId).stream()
            .map(c -> {
                var device = labels.get(c.credentialId());
                String label = device != null ? device.label : null;
                Instant createdAt = device != null ? device.createdAt : null;
                return new DeviceView(c.credentialId(), label, c.revocationSequence() != null, createdAt);
            }).toList();
    }

    /**
     * What the caller must get approved (IDAX Core per-operation approval) before osTRIS accepts a
     * device change where the deployment requires it. STIR only describes the operation - action,
     * resource and intent digest for the caller's OWN controller - so the client can ask the
     * identity service for an approval and send it back in X-Operation-Approval. STIR cannot grant
     * anything by describing it wrongly: osTRIS recomputes the digest from the change it is about
     * to make and refuses any approval that does not match exactly.
     */
    public ApprovalIntent addDeviceIntent(CurrentUser user, String publicKeyBase64url) {
        UUID tenant = tenant(), actor = actor(user);
        var binding = myBinding(tenant, actor);
        if (publicKeyBase64url == null || publicKeyBase64url.isBlank()) throw new ResponseStatusException(BAD_REQUEST, "Public key required");
        return new ApprovalIntent(CREDENTIAL_ADD, binding.controllerId.toString(),
            intentDigest(CREDENTIAL_ADD, tenant.toString(), binding.communityId.toString(), binding.controllerId.toString(), publicKeyBase64url));
    }
    public ApprovalIntent revokeDeviceIntent(CurrentUser user, UUID ostrisCredentialId) {
        UUID tenant = tenant(), actor = actor(user);
        var binding = myBinding(tenant, actor);
        return new ApprovalIntent(CREDENTIAL_REVOKE, ostrisCredentialId.toString(),
            intentDigest(CREDENTIAL_REVOKE, tenant.toString(), binding.communityId.toString(), ostrisCredentialId.toString()));
    }

    static final String CREDENTIAL_ADD = "OSTRIS_CREDENTIAL_ADD", CREDENTIAL_REVOKE = "OSTRIS_CREDENTIAL_REVOKE";
    /** Same encoding as IDAX Core's OperationIntentDigest: sha256 over each part as <utf8-length>:<part>. */
    static String intentDigest(String... parts) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                byte[] bytes = part.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                digest.update((bytes.length + ":").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update(bytes);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    /** osTRIS's approval and ownership refusals reach the client with their own status and code
     * (428 = repeat with an approval); everything else keeps propagating unchanged. */
    private static RuntimeException approvalAware(StirOstrisException ex) {
        boolean relay = ex.code != null && (ex.code.startsWith("OPERATION_APPROVAL_") || ex.code.startsWith("CONTROLLER_"));
        var status = relay ? org.springframework.http.HttpStatus.resolve(ex.status) : null;
        return status == null ? ex : new ResponseStatusException(status, ex.code);
    }

    public record ApprovalIntent(String action, String resource, String intentDigest) {}
    public record DeviceView(UUID credentialId, String label, boolean revoked, Instant addedAt) {}
}
