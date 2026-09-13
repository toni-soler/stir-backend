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
        var added = ostris.addCredential(binding.communityId, binding.controllerId, publicKeyBase64url);
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
        ostris.revokeCredential(binding.communityId, ostrisCredentialId);
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

    public record DeviceView(UUID credentialId, String label, boolean revoked, Instant addedAt) {}
}
