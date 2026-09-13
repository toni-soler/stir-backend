package org.stir.economic;

import es.idynamicsax.idax.security.CurrentUser;
import es.idynamicsax.idax.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.stir.participant.ParticipantProfileRepository;
import static org.springframework.http.HttpStatus.*;

/**
 * tenant<->community/unit and user<->participant/account bindings (section 7/9/12 of the 0.3
 * brief). Bootstrap is an explicit admin action, never inferred; activation reuses the caller's
 * existing ParticipantProfile display name and the marketplace's one bound unit, and only ever
 * registers a public key the client already generated - see AGENTS.md/PRIVACY_MODEL.md for why
 * this service never sees a private key.
 */
@Service @Transactional
public class EconomicActivationService {
    private final MarketplaceEconomicBindingRepository marketplaceBindings;
    private final ParticipantEconomicBindingRepository participantBindings;
    private final ParticipantProfileRepository profiles;
    private final OstrisClient ostris;

    public EconomicActivationService(MarketplaceEconomicBindingRepository marketplaceBindings,
            ParticipantEconomicBindingRepository participantBindings, ParticipantProfileRepository profiles, OstrisClient ostris) {
        this.marketplaceBindings = marketplaceBindings; this.participantBindings = participantBindings;
        this.profiles = profiles; this.ostris = ostris;
    }

    UUID tenant() {
        var context = TenantContext.get();
        if (context == null || context.getTenantId() == null) throw new AccessDeniedException("Tenant required");
        return context.getTenantId();
    }
    UUID owner(CurrentUser user) {
        if (user == null || user.isService() || user.getUserId() == null) throw new AccessDeniedException("User required");
        return user.getUserId();
    }

    public MarketplaceEconomicBinding requireMarketplaceBinding(UUID tenant) {
        return marketplaceBindings.findByTenantId(tenant)
            .orElseThrow(() -> new ResponseStatusException(CONFLICT, "This marketplace has no economic community/unit bound yet"));
    }

    public MarketplaceEconomicView marketplace() {
        var binding = requireMarketplaceBinding(tenant());
        var community = ostris.community(binding.communityId);
        var unit = ostris.unit(binding.communityId, binding.unitId);
        return new MarketplaceEconomicView(binding.communityId, community.name(), binding.unitId, unit.code(), unit.scale());
    }

    public MarketplaceEconomicView bootstrap(String communityName, String unitCode, int unitScale) {
        UUID tenant = tenant();
        if (marketplaceBindings.findByTenantId(tenant).isPresent())
            throw new ResponseStatusException(CONFLICT, "This marketplace is already economically bound");
        UUID community = ostris.createCommunity(communityName);
        var unit = ostris.createUnit(community, unitCode, unitScale);
        var binding = new MarketplaceEconomicBinding();
        binding.tenantId = tenant; binding.communityId = community; binding.unitId = unit.unitId(); binding.createdAt = Instant.now();
        marketplaceBindings.saveAndFlush(binding);
        return new MarketplaceEconomicView(community, communityName, unit.unitId(), unit.code(), unit.scale());
    }

    public ParticipantEconomicView me(CurrentUser user) {
        UUID tenant = tenant(), owner = owner(user);
        var binding = participantBindings.findByTenantIdAndUserId(tenant, owner)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Not activated for economic exchange yet"));
        var account = ostris.account(binding.communityId, binding.accountId);
        return ParticipantEconomicView.of(binding, account);
    }

    /**
     * Idempotent per user, NOT per device: if this tenant/user is already activated (from any
     * device), this returns the existing profile with credentialId=null - the stored binding's
     * credentialId belongs to whichever device activated FIRST, and would be actively misleading
     * to hand back here as "this device's". A second device instead goes through
     * DeviceCredentialService.addDevice() (see MyEconomicProfile's UI flow), which binds its own
     * new credential to the SAME already-active controller.
     */
    public ActivationView activate(CurrentUser user, String publicKeyBase64url) {
        UUID tenant = tenant(), owner = owner(user);
        var existing = participantBindings.findByTenantIdAndUserId(tenant, owner);
        if (existing.isPresent()) return new ActivationView(me(user), null);
        var marketplace = requireMarketplaceBinding(tenant);
        var profile = profiles.findByTenantIdAndUserId(tenant, owner)
            .orElseThrow(() -> new ResponseStatusException(CONFLICT, "PROFILE_REQUIRED_BEFORE_ACTIVATION: Set up your marketplace profile before activating exchanges"));
        var result = ostris.activateParticipant(marketplace.communityId, profile.displayName, marketplace.unitId, publicKeyBase64url, null);
        var binding = new ParticipantEconomicBinding();
        binding.id = UUID.randomUUID(); binding.tenantId = tenant; binding.userId = owner;
        binding.communityId = marketplace.communityId; binding.unitId = marketplace.unitId;
        binding.participantId = result.participantId(); binding.accountId = result.accountId();
        binding.controllerId = result.controllerId(); binding.credentialId = result.credentialId();
        binding.publicKeyBase64url = publicKeyBase64url; binding.createdAt = Instant.now();
        participantBindings.saveAndFlush(binding);
        return new ActivationView(me(user), result.credentialId());
    }

    public record MarketplaceEconomicView(UUID communityId, String communityName, UUID unitId, String unitCode, int unitScale) {}
    public record ParticipantEconomicView(UUID communityId, UUID unitId, UUID accountId, String balanceProjection, String creditFloor) {
        static ParticipantEconomicView of(ParticipantEconomicBinding binding, OstrisClient.AccountView account) {
            return new ParticipantEconomicView(binding.communityId, binding.unitId, binding.accountId, account.balanceProjection(), account.creditFloor());
        }
    }
    /** Only ever returned from activate() itself - the ONE moment a caller needs to know "this
     * credentialId is what THIS device's key just became" (to remember it locally, see signer.js's
     * markDeviceRegistered). me() intentionally does not expose credentialId: on a second device it
     * would misleadingly be the FIRST device's id, not this one's. */
    public record ActivationView(ParticipantEconomicView profile, UUID credentialId) {}
}
