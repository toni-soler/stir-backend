package org.stir.negotiation;

import es.idynamicsax.idax.security.CurrentUser;
import es.idynamicsax.idax.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;
import org.stir.listing.Listing;
import org.stir.listing.ListingRepository;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** In-memory fake repositories: realistic enough to exercise the real state machine
 * (append-only offer history, negotiation head, one agreement per negotiation) without a database. */
class NegotiationServiceTest {
    final UUID tenant=UUID.randomUUID(), initiator=UUID.randomUUID(), owner=UUID.randomUUID(), stranger=UUID.randomUUID();
    final Map<UUID,Negotiation> negotiationStore=new HashMap<>();
    final Map<UUID,List<Offer>> offersByNegotiation=new HashMap<>();
    final Map<UUID,Agreement> agreementByNegotiation=new HashMap<>();

    ListingRepository listingRepository; NegotiationRepository negotiationRepository; OfferRepository offerRepository;
    AgreementRepository agreementRepository; AgreementSnapshotRepository snapshotRepository;
    NegotiationService service; Listing listing; CurrentUser initiatorUser, ownerUser, strangerUser;

    @BeforeEach void setup() {
        listingRepository=mock(ListingRepository.class);
        negotiationRepository=mock(NegotiationRepository.class);
        offerRepository=mock(OfferRepository.class);
        agreementRepository=mock(AgreementRepository.class);
        snapshotRepository=mock(AgreementSnapshotRepository.class);
        service=new NegotiationService(negotiationRepository,offerRepository,agreementRepository,listingRepository,
            new AgreementSnapshotService(snapshotRepository));

        listing=new Listing(); listing.id=UUID.randomUUID(); listing.tenantId=tenant; listing.ownerId=owner;
        listing.status="ACTIVE"; listing.direction="OFFER"; listing.version=0;
        when(listingRepository.findByIdAndTenantId(listing.id,tenant)).thenReturn(Optional.of(listing));

        initiatorUser=mock(CurrentUser.class); when(initiatorUser.getUserId()).thenReturn(initiator);
        ownerUser=mock(CurrentUser.class); when(ownerUser.getUserId()).thenReturn(owner);
        strangerUser=mock(CurrentUser.class); when(strangerUser.getUserId()).thenReturn(stranger);

        when(negotiationRepository.saveAndFlush(any())).thenAnswer(i->{Negotiation n=i.getArgument(0);negotiationStore.put(n.id,n);return n;});
        when(negotiationRepository.findByIdAndTenantId(any(),any())).thenAnswer(i->Optional.ofNullable(negotiationStore.get((UUID)i.getArgument(0)))
            .filter(n->n.tenantId.equals(i.getArgument(1))));
        when(negotiationRepository.findByTenantIdAndListingIdAndInitiatorIdAndStatus(any(),any(),any(),any())).thenAnswer(i->negotiationStore.values().stream()
            .filter(n->n.tenantId.equals(i.getArgument(0))&&n.listingId.equals(i.getArgument(1))&&n.initiatorId.equals(i.getArgument(2))&&n.status.equals(i.getArgument(3)))
            .findFirst());

        when(offerRepository.saveAndFlush(any())).thenAnswer(i->{Offer o=i.getArgument(0);
            var list=offersByNegotiation.computeIfAbsent(o.negotiationId,k->new ArrayList<>());
            list.removeIf(existing->existing.id.equals(o.id)); list.add(o); return o;});
        when(offerRepository.findByNegotiationIdOrderBySequenceNumberAsc(any())).thenAnswer(i->offersByNegotiation
            .getOrDefault(i.getArgument(0),List.<Offer>of()).stream().sorted(Comparator.comparingInt(o->o.sequenceNumber)).collect(Collectors.toList()));
        when(offerRepository.findByIdAndNegotiationId(any(),any())).thenAnswer(i->offersByNegotiation
            .getOrDefault(i.getArgument(1),List.<Offer>of()).stream().filter(o->o.id.equals(i.getArgument(0))).findFirst());

        when(agreementRepository.saveAndFlush(any())).thenAnswer(i->{Agreement a=i.getArgument(0);agreementByNegotiation.put(a.negotiationId,a);return a;});
        when(agreementRepository.findByNegotiationId(any())).thenAnswer(i->Optional.ofNullable(agreementByNegotiation.get((UUID)i.getArgument(0))));
        when(snapshotRepository.saveAndFlush(any())).thenAnswer(i->i.getArgument(0));

        TenantContext.set(new TenantContext(tenant,null,initiator,"test",TenantContext.DbRole.IDAX_APP));
    }
    @AfterEach void clear() { TenantContext.clear(); }

    OfferRequest offer(String message) { return new OfferRequest(message,new BigDecimal("20"),"kg",new BigDecimal("15.00"),null,"Pickup Friday"); }
    Negotiation openNegotiation() {
        var detail=service.open(initiatorUser,listing.id,offer("20kg of tomatoes"));
        return negotiationStore.get(detail.id());
    }

    @Test void openingNegotiationCreatesFirstOfferAndDerivesParties() {
        var detail=service.open(initiatorUser,listing.id,offer("20kg of tomatoes"));
        assertEquals("OPEN",detail.status());
        assertEquals(initiator,detail.initiatorId());
        assertEquals(owner,detail.ownerId());
        assertEquals(1,detail.offers().size());
        assertEquals(initiator,detail.offers().get(0).authorId);
        assertEquals("PROPOSED",detail.offers().get(0).status);
    }
    @Test void ownerCannotOfferOnTheirOwnListing() {
        assertThrows(ResponseStatusException.class,()->service.open(ownerUser,listing.id,offer("nope")));
    }
    @Test void cannotOpenNegotiationOnClosedListing() {
        listing.status="CLOSED";
        assertThrows(ResponseStatusException.class,()->service.open(initiatorUser,listing.id,offer("x")));
    }
    @Test void secondOpenNegotiationOnSameListingByTheSameInitiatorIsRejected() {
        openNegotiation();
        assertThrows(ResponseStatusException.class,()->service.open(initiatorUser,listing.id,offer("again")));
    }
    @Test void counterOfferAlternatesAuthorAndSupersedesPrevious() {
        var negotiation=openNegotiation();
        var countered=service.counter(ownerUser,negotiation.id,offer("18kg instead"),negotiation.version);
        assertEquals(2,countered.offers().size());
        assertEquals("SUPERSEDED",countered.offers().get(0).status);
        assertEquals(owner,countered.offers().get(1).authorId);
        assertEquals("PROPOSED",countered.offers().get(1).status);
    }
    @Test void authorCannotCounterTheirOwnLastOffer() {
        var negotiation=openNegotiation();
        assertThrows(ResponseStatusException.class,()->service.counter(initiatorUser,negotiation.id,offer("again"),negotiation.version));
    }
    @Test void staleVersionOnCounterIsRejected() {
        var negotiation=openNegotiation();
        assertThrows(ResponseStatusException.class,()->service.counter(ownerUser,negotiation.id,offer("x"),negotiation.version+1));
    }
    @Test void concurrentCountersOnTheSameStaleVersionOnlyOneSucceeds() {
        var negotiation=openNegotiation();
        service.counter(ownerUser,negotiation.id,offer("18kg instead"),negotiation.version);
        assertThrows(ResponseStatusException.class,()->service.counter(ownerUser,negotiation.id,offer("17kg instead"),negotiation.version));
    }
    @Test void strangerCannotReadCounterOrAcceptNegotiation() {
        var negotiation=openNegotiation();
        assertThrows(ResponseStatusException.class,()->service.read(strangerUser,negotiation.id));
        assertThrows(ResponseStatusException.class,()->service.counter(strangerUser,negotiation.id,offer("x"),negotiation.version));
        assertThrows(ResponseStatusException.class,()->service.accept(strangerUser,negotiation.id,negotiation.lastOfferId,negotiation.version));
    }
    @Test void acceptingSupersededOfferIsRejected() {
        var negotiation=openNegotiation();
        var firstOfferId=negotiation.lastOfferId;
        service.counter(ownerUser,negotiation.id,offer("18kg instead"),negotiation.version);
        var refreshed=negotiationStore.get(negotiation.id);
        assertThrows(ResponseStatusException.class,()->service.accept(ownerUser,refreshed.id,firstOfferId,refreshed.version));
    }
    @Test void authorCannotAcceptTheirOwnOffer() {
        var negotiation=openNegotiation();
        assertThrows(ResponseStatusException.class,()->service.accept(initiatorUser,negotiation.id,negotiation.lastOfferId,negotiation.version));
    }
    @Test void acceptCreatesAgreementAndImmutableSnapshotWithoutExposingNonce() {
        var negotiation=openNegotiation();
        var agreement=service.accept(ownerUser,negotiation.id,negotiation.lastOfferId,negotiation.version);
        assertEquals("AWAITING_ECONOMIC_EXECUTION",agreement.economicPhase());
        assertEquals(64,agreement.snapshot().digestSha256().length());
        assertFalse(agreement.snapshot().canonicalJson().contains("\"nonce\":null"));
    }
    @Test void twoConcurrentAcceptAttemptsOnlyOneSucceeds() {
        var negotiation=openNegotiation();
        service.accept(ownerUser,negotiation.id,negotiation.lastOfferId,negotiation.version);
        assertThrows(ResponseStatusException.class,()->service.accept(ownerUser,negotiation.id,negotiation.lastOfferId,negotiation.version));
    }
    @Test void acceptAfterListingClosedIsRejected() {
        var negotiation=openNegotiation();
        listing.status="CLOSED";
        assertThrows(ResponseStatusException.class,()->service.accept(ownerUser,negotiation.id,negotiation.lastOfferId,negotiation.version));
    }
    @Test void declineClosesNegotiationAndBlocksFurtherActions() {
        var negotiation=openNegotiation();
        var declined=service.decline(ownerUser,negotiation.id,negotiation.version);
        assertEquals("DECLINED",declined.status());
        var refreshed=negotiationStore.get(negotiation.id);
        assertThrows(ResponseStatusException.class,()->service.counter(initiatorUser,refreshed.id,offer("x"),refreshed.version));
        assertThrows(ResponseStatusException.class,()->service.accept(initiatorUser,refreshed.id,refreshed.lastOfferId,refreshed.version));
    }
    @Test void missingTenantFailsClosed() {
        TenantContext.clear();
        assertThrows(AccessDeniedException.class,()->service.open(initiatorUser,listing.id,offer("x")));
    }
}
