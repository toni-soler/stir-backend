package org.stir.economic;

import es.idynamicsax.idax.security.CurrentUser;
import es.idynamicsax.idax.tenant.TenantContext;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;
import org.stir.participant.ParticipantProfile;
import org.stir.participant.ParticipantProfileRepository;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EconomicActivationServiceTest {
    final UUID tenant = UUID.randomUUID(), user = UUID.randomUUID(), community = UUID.randomUUID(), unit = UUID.randomUUID();
    MarketplaceEconomicBindingRepository marketplaceBindings;
    ParticipantEconomicBindingRepository participantBindings;
    ParticipantProfileRepository profiles;
    OstrisClient ostris;
    EconomicActivationService service;
    CurrentUser currentUser;

    @BeforeEach void setup() {
        marketplaceBindings = mock(MarketplaceEconomicBindingRepository.class);
        participantBindings = mock(ParticipantEconomicBindingRepository.class);
        profiles = mock(ParticipantProfileRepository.class);
        ostris = mock(OstrisClient.class);
        service = new EconomicActivationService(marketplaceBindings, participantBindings, profiles, ostris);
        currentUser = mock(CurrentUser.class); when(currentUser.getUserId()).thenReturn(user);
        when(marketplaceBindings.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        // saveAndFlush persists into this holder so a later findByTenantIdAndUserId (e.g. the
        // activate() -> me() read-back at the end) sees it, matching how a real repository would.
        var savedBinding = new ParticipantEconomicBinding[1];
        when(participantBindings.saveAndFlush(any())).thenAnswer(i -> { savedBinding[0] = i.getArgument(0); return savedBinding[0]; });
        when(participantBindings.findByTenantIdAndUserId(tenant, user)).thenAnswer(i -> Optional.ofNullable(savedBinding[0]));
        TenantContext.set(new TenantContext(tenant, null, user, "test", TenantContext.DbRole.IDAX_APP));
    }
    @AfterEach void clear() { TenantContext.clear(); }

    @Test void bootstrapCreatesCommunityAndUnitOnce() {
        when(marketplaceBindings.findByTenantId(tenant)).thenReturn(Optional.empty());
        when(ostris.createCommunity("Test")).thenReturn(community);
        when(ostris.createUnit(community, "TST", 2)).thenReturn(new OstrisClient.UnitCreated(unit, "TST", 2));
        var view = service.bootstrap("Test", "TST", 2);
        assertEquals(community, view.communityId());
        assertEquals(2, view.unitScale());
        verify(marketplaceBindings).saveAndFlush(any());
    }
    @Test void bootstrapRejectsWhenAlreadyBound() {
        var existing = new MarketplaceEconomicBinding(); existing.tenantId = tenant; existing.communityId = community; existing.unitId = unit;
        when(marketplaceBindings.findByTenantId(tenant)).thenReturn(Optional.of(existing));
        assertThrows(ResponseStatusException.class, () -> service.bootstrap("Again", "TST", 2));
        verify(ostris, never()).createCommunity(any());
    }
    @Test void activateRequiresAnExistingProfile() {
        var marketplace = new MarketplaceEconomicBinding(); marketplace.tenantId = tenant; marketplace.communityId = community; marketplace.unitId = unit;
        when(marketplaceBindings.findByTenantId(tenant)).thenReturn(Optional.of(marketplace));
        when(participantBindings.findByTenantIdAndUserId(tenant, user)).thenReturn(Optional.empty());
        when(profiles.findByTenantIdAndUserId(tenant, user)).thenReturn(Optional.empty());
        assertThrows(ResponseStatusException.class, () -> service.activate(currentUser, "pk"));
        verify(ostris, never()).activateParticipant(any(), any(), any(), any(), any());
    }
    @Test void activateIsIdempotentAndReusesExistingBinding() {
        var existing = new ParticipantEconomicBinding();
        existing.tenantId = tenant; existing.userId = user; existing.communityId = community; existing.unitId = unit;
        existing.participantId = UUID.randomUUID(); existing.accountId = UUID.randomUUID();
        when(participantBindings.findByTenantIdAndUserId(tenant, user)).thenReturn(Optional.of(existing));
        when(ostris.account(community, existing.accountId)).thenReturn(
            new OstrisClient.AccountView(existing.accountId, unit, existing.participantId, "INDIVIDUAL", "Ana", "-100000", "0", "ACTIVE"));
        var result = service.activate(currentUser, "some-public-key");
        assertEquals(existing.accountId, result.profile().accountId());
        assertNull(result.credentialId(), "a second device's activate() call must never claim the FIRST device's credentialId as its own");
        verify(ostris, never()).activateParticipant(any(), any(), any(), any(), any());
    }
    @Test void activateCallsOstrisWithTheProfileDisplayNameAndPersistsTheBinding() {
        var marketplace = new MarketplaceEconomicBinding(); marketplace.tenantId = tenant; marketplace.communityId = community; marketplace.unitId = unit;
        when(marketplaceBindings.findByTenantId(tenant)).thenReturn(Optional.of(marketplace));
        var profile = new ParticipantProfile(); profile.displayName = "Ana"; profile.userId = user; profile.tenantId = tenant;
        when(profiles.findByTenantIdAndUserId(tenant, user)).thenReturn(Optional.of(profile));
        UUID participantId = UUID.randomUUID(), accountId = UUID.randomUUID(), controllerId = UUID.randomUUID(), credentialId = UUID.randomUUID();
        when(ostris.activateParticipant(community, "Ana", unit, "pk-bytes", null))
            .thenReturn(new OstrisClient.ActivationResult(participantId, accountId, controllerId, credentialId, unit, "-100000", "0"));
        when(ostris.account(community, accountId)).thenReturn(new OstrisClient.AccountView(accountId, unit, participantId, "INDIVIDUAL", "Ana", "-100000", "0", "ACTIVE"));

        var result = service.activate(currentUser, "pk-bytes");
        assertEquals(accountId, result.profile().accountId());
        assertEquals(credentialId, result.credentialId(), "the FRESH activation must return the credentialId that was just created, so the client can remember this device is registered");
        var captor = org.mockito.ArgumentCaptor.forClass(ParticipantEconomicBinding.class);
        verify(participantBindings).saveAndFlush(captor.capture());
        assertEquals(accountId, captor.getValue().accountId);
        assertEquals(credentialId, captor.getValue().credentialId);
        assertEquals("pk-bytes", captor.getValue().publicKeyBase64url);
    }
    @Test void missingTenantFailsClosed() {
        TenantContext.clear();
        assertThrows(AccessDeniedException.class, () -> service.marketplace());
    }
}
