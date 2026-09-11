package org.stir.listing;

import es.idynamicsax.idax.security.CurrentUser;
import es.idynamicsax.idax.tenant.TenantContext;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;
import org.stir.participant.ParticipantProfileRepository;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ListingServiceTest {
    final UUID tenant=UUID.randomUUID(), owner=UUID.randomUUID();
    ListingRepository repository; ListingService service; CurrentUser user; Listing listing;
    @BeforeEach void setup() {
        repository=mock(ListingRepository.class); var jdbc=mock(JdbcTemplate.class);
        var profiles=mock(ParticipantProfileRepository.class);
        when(profiles.findByTenantIdAndUserIdIn(any(),any())).thenReturn(List.of());
        service=new ListingService(repository,jdbc,profiles);
        user=mock(CurrentUser.class); when(user.getUserId()).thenReturn(owner);
        TenantContext.set(new TenantContext(tenant,null,owner,"test",TenantContext.DbRole.IDAX_APP));
        listing=new Listing();listing.id=UUID.randomUUID();listing.tenantId=tenant;listing.ownerId=owner;listing.status="ACTIVE";listing.version=2;
        when(repository.findByIdAndTenantId(listing.id,tenant)).thenReturn(Optional.of(listing));
        when(repository.saveAndFlush(any())).thenAnswer(i->i.getArgument(0));
        when(jdbc.queryForList("select code from stir.category order by code",String.class)).thenReturn(List.of("general"));
        when(jdbc.queryForList("select code from stir.resource_kind order by code",String.class)).thenReturn(List.of("physical"));
    }
    @AfterEach void clear(){TenantContext.clear();}
    ListingRequest request(Long version){return new ListingRequest("OFFER"," Chair ","Wood","general","physical",null,version);}
    @Test void createDerivesOwnerTenantAndActiveState(){var result=service.create(user,request(null));assertEquals(tenant,result.tenantId);assertEquals(owner,result.ownerId);assertEquals("ACTIVE",result.status);assertEquals("Chair",result.title);}
    @Test void staleUpdateIsRejected(){assertThrows(ResponseStatusException.class,()->service.update(listing.id,user,request(1L)));verify(repository,never()).saveAndFlush(any());}
    @Test void otherOwnerCannotChangeListing(){when(user.getUserId()).thenReturn(UUID.randomUUID());assertThrows(AccessDeniedException.class,()->service.close(listing.id,user,2));}
    @Test void closedListingCannotBeEdited(){listing.status="CLOSED";assertThrows(ResponseStatusException.class,()->service.update(listing.id,user,request(2L)));}
    @Test void closeIsIdempotent(){assertEquals("CLOSED",service.close(listing.id,user,2).status);assertEquals("CLOSED",service.close(listing.id,user,2).status);verify(repository,times(1)).saveAndFlush(any());}
    @Test void tenantQualifiedReadDoesNotFallbackToId(){UUID other=UUID.randomUUID();TenantContext.set(new TenantContext(other,null,owner,"test",TenantContext.DbRole.IDAX_APP));assertThrows(ResponseStatusException.class,()->service.read(listing.id));verify(repository).findByIdAndTenantId(listing.id,other);verify(repository,never()).findById(any());}
    @Test void missingTenantFailsClosed(){TenantContext.clear();assertThrows(AccessDeniedException.class,()->service.read(listing.id));}
}
