package org.stir.participant;

import es.idynamicsax.idax.security.CurrentUser;
import es.idynamicsax.idax.tenant.TenantContext;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ParticipantProfileServiceTest {
    final UUID tenant=UUID.randomUUID(), userId=UUID.randomUUID();
    ParticipantProfileRepository repository; ParticipantProfileService service; CurrentUser user;
    @BeforeEach void setup() {
        repository=mock(ParticipantProfileRepository.class);
        service=new ParticipantProfileService(repository);
        user=mock(CurrentUser.class); when(user.getUserId()).thenReturn(userId);
        when(repository.saveAndFlush(any())).thenAnswer(i->i.getArgument(0));
        TenantContext.set(new TenantContext(tenant,null,userId,"test",TenantContext.DbRole.IDAX_APP));
    }
    @AfterEach void clear() { TenantContext.clear(); }

    @Test void ownProfileNotFoundUntilCreated() {
        when(repository.findByTenantIdAndUserId(tenant,userId)).thenReturn(Optional.empty());
        assertThrows(ResponseStatusException.class,()->service.me(user));
    }
    @Test void upsertCreatesThenUpdatesInPlace() {
        when(repository.findByTenantIdAndUserId(tenant,userId)).thenReturn(Optional.empty());
        var created=service.upsertMe(user,new ParticipantProfileRequest("Ana"," Grows tomatoes ","Girona"));
        assertEquals("Ana",created.displayName());
        assertEquals("Grows tomatoes",created.bio());
        assertTrue(created.active());
        var stored=new ParticipantProfile(); stored.id=UUID.randomUUID(); stored.tenantId=tenant; stored.userId=userId;
        stored.displayName="Ana"; stored.bio="Grows tomatoes"; stored.active=true;
        when(repository.findByTenantIdAndUserId(tenant,userId)).thenReturn(Optional.of(stored));
        var updated=service.upsertMe(user,new ParticipantProfileRequest("Ana G.",null,null));
        assertEquals("Ana G.",updated.displayName());
        assertNull(updated.bio());
        assertNull(updated.location());
    }
    @Test void publicViewNeverExposesTheProfileRowId() {
        var stored=new ParticipantProfile(); stored.id=UUID.randomUUID(); stored.tenantId=tenant; stored.userId=userId;
        stored.displayName="Ana"; stored.active=true;
        when(repository.findByTenantIdAndUserId(tenant,userId)).thenReturn(Optional.of(stored));
        var view=service.view(userId);
        assertEquals(userId,view.userId());
    }
}
