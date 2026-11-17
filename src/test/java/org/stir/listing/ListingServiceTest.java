package org.stir.listing;

import es.idynamicsax.idax.security.CurrentUser;
import es.idynamicsax.idax.tenant.TenantContext;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;
import org.stir.attachment.AttachmentRepository;
import org.stir.participant.ParticipantProfileRepository;
import org.stir.reference.ListingEvidenceAdapter;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ListingServiceTest {
    final UUID tenant=UUID.randomUUID(), owner=UUID.randomUUID();
    ListingRepository repository; ListingService service; CurrentUser user; Listing listing; JdbcTemplate jdbc;
    @BeforeEach void setup() {
        repository=mock(ListingRepository.class); jdbc=mock(JdbcTemplate.class);
        var profiles=mock(ParticipantProfileRepository.class);
        when(profiles.findByTenantIdAndUserIdIn(any(),any())).thenReturn(List.of());
        var attachments=mock(AttachmentRepository.class);
        when(attachments.findByTenantIdAndListingIdInAndPositionAndStatus(any(),any(),anyShort(),any())).thenReturn(List.of());
        var revisions=mock(ListingRevisionService.class); var evidence=mock(ListingEvidenceAdapter.class);
        service=new ListingService(repository,jdbc,profiles,attachments,revisions,evidence);
        user=mock(CurrentUser.class); when(user.getUserId()).thenReturn(owner);
        TenantContext.set(new TenantContext(tenant,null,owner,"test",TenantContext.DbRole.IDAX_APP));
        listing=new Listing();listing.id=UUID.randomUUID();listing.tenantId=tenant;listing.ownerId=owner;listing.status="ACTIVE";listing.version=2;
        listing.direction="OFFER";
        when(repository.findByIdAndTenantId(listing.id,tenant)).thenReturn(Optional.of(listing));
        when(repository.lockForUpdate(listing.id,tenant)).thenReturn(Optional.of(listing));
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
    ListingRequest wanted(Long version){return new ListingRequest("WANTED","Chair","Wood","general","physical",null,version);}
    void openNegotiations(boolean open){when(jdbc.queryForObject(contains("from stir.negotiation"),eq(Boolean.class),eq(tenant),eq(listing.id))).thenReturn(open);}
    @Test void directionCannotFlipWhileANegotiationIsOpen(){
        openNegotiations(true);
        var error=assertThrows(ResponseStatusException.class,()->service.update(listing.id,user,wanted(2L)));
        assertEquals(409,error.getStatusCode().value());
        assertEquals("OFFER",listing.direction);
        verify(repository,never()).saveAndFlush(any());
    }
    @Test void directionCanChangeWithNoOpenNegotiationAndOtherEditsNeverAsk(){
        openNegotiations(false);
        assertEquals("WANTED",service.update(listing.id,user,wanted(2L)).direction);
        listing.version=2; listing.direction="WANTED"; openNegotiations(true);
        assertEquals("Desk",service.update(listing.id,user,new ListingRequest("WANTED","Desk","Wood","general","physical",null,2L)).title);
    }
    @Test void editsTakeTheRowLock(){openNegotiations(false);service.update(listing.id,user,request(2L));verify(repository).lockForUpdate(listing.id,tenant);}
    @Test void missingTenantFailsClosed(){TenantContext.clear();assertThrows(AccessDeniedException.class,()->service.read(listing.id));}

    // STRUCTURED_TERMS_AND_NEEDS.md
    ListingRequest detailed(String direction, ListingDetails details, Long version) {
        return new ListingRequest(direction,"Blind repair","Stuck blind","general","physical",null,version,null,null,null,null,null,false,details);
    }
    @Test void detailsAreStoredWithDimensionsInCanonicalOrder() {
        var details=new ListingDetails("GOOD","PICKUP","Charger and case",java.time.LocalDate.of(2026,12,31),"IN_CONVERSATION",
            java.time.LocalDate.of(2026,12,1),List.of("AMOUNT","TIMING","TIMING"),List.of("PLACE"),"  Third floor, no lift  ");
        var result=service.create(user,detailed("WANTED",details,null));
        assertEquals("GOOD",result.itemCondition); assertEquals("PICKUP",result.fulfilmentMode);
        assertEquals("IN_CONVERSATION",result.availability);
        assertEquals("TIMING,AMOUNT",result.flexibleOn); assertEquals("PLACE",result.firmOn);
        assertEquals("Third floor, no lift",result.counterpartNote);
    }
    @Test void withoutDetailsAListingIsAvailableAndHasNoneOfThem() {
        var result=service.create(user,request(null));
        assertEquals("AVAILABLE",result.availability); assertNull(result.itemCondition); assertNull(result.counterpartNote);
    }
    @Test void anUpdateWithoutDetailsKeepsThemAndOneWithDetailsReplacesThem() {
        openNegotiations(false);
        listing.counterpartNote="Third floor"; listing.itemCondition="FAIR";
        service.update(listing.id,user,request(2L));
        assertEquals("Third floor",listing.counterpartNote); assertEquals("FAIR",listing.itemCondition);
        listing.version=2;
        service.update(listing.id,user,detailed("OFFER",ListingDetails.NONE,2L));
        assertNull(listing.counterpartNote); assertNull(listing.itemCondition); assertEquals("AVAILABLE",listing.availability);
    }
    @Test void onlyANeedHasANeededByDate() {
        var details=new ListingDetails(null,null,null,null,null,java.time.LocalDate.of(2026,12,1),null,null,null);
        assertEquals(400,assertThrows(ResponseStatusException.class,()->service.create(user,detailed("OFFER",details,null))).getStatusCode().value());
    }
    @Test void aDimensionCannotBeBothFlexibleAndFirm() {
        var details=new ListingDetails(null,null,null,null,null,null,List.of("PLACE"),List.of("PLACE"),null);
        assertEquals(400,assertThrows(ResponseStatusException.class,()->service.create(user,detailed("WANTED",details,null))).getStatusCode().value());
    }
    @Test void theCounterpartNoteIsOnlyReadByTheOwnerAndThePeopleNegotiating() {
        listing.counterpartNote="Third floor, no lift";
        var negotiator=UUID.randomUUID(); var stranger=UUID.randomUUID();
        when(jdbc.queryForObject(contains("initiator_id"),eq(Boolean.class),eq(tenant),eq(listing.id),eq(negotiator))).thenReturn(true);
        when(jdbc.queryForObject(contains("initiator_id"),eq(Boolean.class),eq(tenant),eq(listing.id),eq(stranger))).thenReturn(false);
        assertEquals("Third floor, no lift",service.read(listing.id,user).counterpartNote());
        var asNegotiator=mock(CurrentUser.class); when(asNegotiator.getUserId()).thenReturn(negotiator);
        assertEquals("Third floor, no lift",service.read(listing.id,asNegotiator).counterpartNote());
        var asStranger=mock(CurrentUser.class); when(asStranger.getUserId()).thenReturn(stranger);
        var view=service.read(listing.id,asStranger);
        assertNull(view.counterpartNote()); assertTrue(view.hasCounterpartNote());
        assertNull(service.read(listing.id).counterpartNote());
    }
    @Test void aRevisionFreezesComparablePropertiesButNeverTheCounterpartNote() {
        var revisions=mock(ListingRevisionRepository.class);
        when(revisions.saveAndFlush(any())).thenAnswer(i->i.getArgument(0));
        listing.title="Blind"; listing.itemCondition="FAIR"; listing.fulfilmentMode="ON_SITE"; listing.availability="AVAILABLE";
        listing.counterpartNote="Third floor, no lift";
        var revision=new ListingRevisionService(revisions).freeze(listing);
        assertTrue(revision.canonicalJson.contains("\"itemCondition\":\"FAIR\""));
        assertTrue(revision.canonicalJson.contains("STIR-LISTING-REVISION-JCS-2"));
        assertFalse(revision.canonicalJson.contains("Third floor"));
    }
}
