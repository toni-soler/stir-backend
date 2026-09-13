package org.stir.economic;

import es.idynamicsax.idax.security.CurrentUser;
import es.idynamicsax.idax.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeviceCredentialServiceTest {
    final UUID tenant = UUID.randomUUID(), user = UUID.randomUUID(), stranger = UUID.randomUUID();
    final UUID community = UUID.randomUUID(), controller = UUID.randomUUID();
    ParticipantEconomicBindingRepository bindings; ParticipantDeviceCredentialRepository devices;
    OstrisClient ostris; DeviceCredentialService service;
    CurrentUser currentUser, strangerUser;

    @BeforeEach void setup() {
        bindings = mock(ParticipantEconomicBindingRepository.class); devices = mock(ParticipantDeviceCredentialRepository.class);
        ostris = mock(OstrisClient.class);
        service = new DeviceCredentialService(bindings, devices, ostris);
        when(devices.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        var binding = new ParticipantEconomicBinding();
        binding.tenantId = tenant; binding.userId = user; binding.communityId = community; binding.controllerId = controller;
        when(bindings.findByTenantIdAndUserId(tenant, user)).thenReturn(Optional.of(binding));
        when(bindings.findByTenantIdAndUserId(tenant, stranger)).thenReturn(Optional.empty());

        currentUser = mock(CurrentUser.class); when(currentUser.getUserId()).thenReturn(user);
        strangerUser = mock(CurrentUser.class); when(strangerUser.getUserId()).thenReturn(stranger);
        TenantContext.set(new TenantContext(tenant, null, user, "test", TenantContext.DbRole.IDAX_APP));
    }
    @AfterEach void tearDown() { TenantContext.clear(); }

    @Test void addDeviceRequiresEconomicActivationFirst() {
        var error = assertThrows(ResponseStatusException.class, () -> service.addDevice(strangerUser, "Phone", "key"));
        assertEquals(409, error.getStatusCode().value());
        verifyNoInteractions(ostris);
    }

    @Test void addDeviceBindsToTheCallersOwnExistingControllerNeverANewOne() {
        var credentialId = UUID.randomUUID();
        when(ostris.addCredential(community, controller, "pk")).thenReturn(new OstrisClient.CredentialAdded(credentialId, controller, 3));
        var view = service.addDevice(currentUser, " Phone ", "pk");
        assertEquals(credentialId, view.credentialId());
        assertEquals("Phone", view.label());
        assertFalse(view.revoked());
        verify(ostris).addCredential(community, controller, "pk");
    }

    @Test void revokeDeviceRejectsACredentialThatIsNotAmongTheCallersOwnOstrisDiscoveredCredentials() {
        var someoneElsesCredential = UUID.randomUUID();
        when(ostris.credentialsForController(community, controller)).thenReturn(List.of());
        var error = assertThrows(ResponseStatusException.class, () -> service.revokeDevice(currentUser, someoneElsesCredential));
        assertEquals(404, error.getStatusCode().value());
        verify(ostris, never()).revokeCredential(any(), any());
    }

    @Test void revokeDeviceCallsOstrisForTheCallersOwnDevice() {
        var credentialId = UUID.randomUUID();
        when(ostris.credentialsForController(community, controller)).thenReturn(
            List.of(new OstrisClient.CredentialView(credentialId, "pk", "Ed25519", 0, null, controller)));
        service.revokeDevice(currentUser, credentialId);
        verify(ostris).revokeCredential(community, credentialId);
    }

    @Test void revokeDeviceWorksForTheFirstDeviceEvenThoughItHasNoLocalLabelRow() {
        // The device bound during activate() (never through addDevice()) has NO row in the local
        // label table at all - osTRIS discovery alone must be enough to authorize revoking it.
        var firstDeviceCredential = UUID.randomUUID();
        when(ostris.credentialsForController(community, controller)).thenReturn(
            List.of(new OstrisClient.CredentialView(firstDeviceCredential, "pk", "Ed25519", 0, null, controller)));
        service.revokeDevice(currentUser, firstDeviceCredential);
        verify(ostris).revokeCredential(community, firstDeviceCredential);
    }

    @Test void myDevicesTreatsOstrisDiscoveryAsTheSourceOfTruthForRevocationAndAddsOnlyTheLabel() {
        var credentialId = UUID.randomUUID();
        when(ostris.credentialsForController(community, controller)).thenReturn(
            List.of(new OstrisClient.CredentialView(credentialId, "pk", "Ed25519", 0, 5L, controller)));
        var device = new ParticipantDeviceCredential();
        device.ostrisCredentialId = credentialId; device.label = "Laptop"; device.createdAt = java.time.Instant.now();
        when(devices.findByTenantIdAndUserId(tenant, user)).thenReturn(List.of(device));

        var result = service.myDevices(currentUser);
        assertEquals(1, result.size());
        assertEquals("Laptop", result.get(0).label());
        assertTrue(result.get(0).revoked(), "a non-null revocationSequence from osTRIS must be reflected, never overridden by STIR's own label row");
    }
}
