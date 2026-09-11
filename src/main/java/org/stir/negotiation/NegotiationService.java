package org.stir.negotiation;

import es.idynamicsax.idax.security.CurrentUser;
import es.idynamicsax.idax.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.stir.listing.Listing;
import org.stir.listing.ListingRepository;
import static org.springframework.http.HttpStatus.*;

/**
 * Negotiation state machine: an Offer opens a Negotiation; counter-offers append to its history
 * (previous offers are marked SUPERSEDED, never overwritten); accept freezes an Agreement +
 * AgreementSnapshot. STIR never moves value here - see AgreementSnapshotService/OSTRIS_INTEGRATION.md.
 */
@Service @Transactional
public class NegotiationService {
    private final NegotiationRepository negotiations;
    private final OfferRepository offers;
    private final AgreementRepository agreements;
    private final ListingRepository listings;
    private final AgreementSnapshotService snapshotService;

    public NegotiationService(NegotiationRepository negotiations, OfferRepository offers, AgreementRepository agreements,
            ListingRepository listings, AgreementSnapshotService snapshotService) {
        this.negotiations=negotiations; this.offers=offers; this.agreements=agreements;
        this.listings=listings; this.snapshotService=snapshotService;
    }

    private UUID tenant() {
        var context=TenantContext.get();
        if(context==null || context.getTenantId()==null) throw new AccessDeniedException("Tenant required");
        return context.getTenantId();
    }
    private UUID actor(CurrentUser user) {
        if(user==null || user.isService() || user.getUserId()==null) throw new AccessDeniedException("User required");
        return user.getUserId();
    }
    private Listing activeListing(UUID tenant, UUID listingId) {
        var listing=listings.findByIdAndTenantId(listingId,tenant).orElseThrow(()->new ResponseStatusException(NOT_FOUND,"Listing not found"));
        if(!"ACTIVE".equals(listing.status)) throw new ResponseStatusException(CONFLICT,"Listing is closed");
        return listing;
    }
    private Negotiation partyNegotiation(UUID tenant, UUID id, UUID actor) {
        var negotiation=negotiations.findByIdAndTenantId(id,tenant).orElseThrow(()->new ResponseStatusException(NOT_FOUND,"Negotiation not found"));
        if(!negotiation.isParty(actor)) throw new ResponseStatusException(NOT_FOUND,"Negotiation not found");
        return negotiation;
    }
    private void checkVersion(long actual, Long expected) {
        if(expected==null || actual!=expected) throw new ResponseStatusException(CONFLICT,"Negotiation changed; reload before continuing");
    }
    private Offer head(Negotiation negotiation) {
        return offers.findByIdAndNegotiationId(negotiation.lastOfferId,negotiation.id)
            .orElseThrow(()->new ResponseStatusException(CONFLICT,"Negotiation has no current offer"));
    }
    private UUID agreementIdFor(Negotiation negotiation) {
        return "ACCEPTED".equals(negotiation.status) ? agreements.findByNegotiationId(negotiation.id).map(a->a.id).orElse(null) : null;
    }

    public NegotiationDetail open(CurrentUser user, UUID listingId, OfferRequest request) {
        UUID tenant=tenant(), initiator=actor(user);
        var listing=activeListing(tenant,listingId);
        if(listing.ownerId.equals(initiator)) throw new ResponseStatusException(BAD_REQUEST,"You cannot make an offer on your own listing");
        negotiations.findByTenantIdAndListingIdAndInitiatorIdAndStatus(tenant,listingId,initiator,"OPEN")
            .ifPresent(existing->{throw new ResponseStatusException(CONFLICT,"You already have an open negotiation on this listing");});
        var now=Instant.now();
        var negotiation=new Negotiation(); negotiation.id=UUID.randomUUID(); negotiation.tenantId=tenant;
        negotiation.listingId=listingId; negotiation.initiatorId=initiator; negotiation.ownerId=listing.ownerId;
        negotiation.status="OPEN"; negotiation.createdAt=now; negotiation.updatedAt=now;
        negotiation=negotiations.saveAndFlush(negotiation);
        var offer=newOffer(negotiation,1,initiator,null,request);
        offer=offers.saveAndFlush(offer);
        negotiation.lastOfferId=offer.id; negotiation.updatedAt=now;
        negotiation=negotiations.saveAndFlush(negotiation);
        return NegotiationDetail.of(negotiation,List.of(offer),null);
    }

    public NegotiationDetail counter(CurrentUser user, UUID negotiationId, OfferRequest request, long expectedVersion) {
        UUID tenant=tenant(), actor=actor(user);
        var negotiation=partyNegotiation(tenant,negotiationId,actor);
        if(!"OPEN".equals(negotiation.status)) throw new ResponseStatusException(CONFLICT,"Negotiation is closed");
        checkVersion(negotiation.version,expectedVersion);
        var head=head(negotiation);
        if(head.authorId.equals(actor)) throw new ResponseStatusException(CONFLICT,"Wait for the other party to respond");
        activeListing(tenant,negotiation.listingId);
        head.status="SUPERSEDED"; offers.saveAndFlush(head);
        var next=newOffer(negotiation,head.sequenceNumber+1,actor,head.id,request);
        next=offers.saveAndFlush(next);
        negotiation.lastOfferId=next.id; negotiation.updatedAt=Instant.now();
        negotiation=negotiations.saveAndFlush(negotiation);
        return NegotiationDetail.of(negotiation,offers.findByNegotiationIdOrderBySequenceNumberAsc(negotiation.id),null);
    }

    public AgreementDetail accept(CurrentUser user, UUID negotiationId, UUID offerId, long expectedVersion) {
        UUID tenant=tenant(), actor=actor(user);
        var negotiation=partyNegotiation(tenant,negotiationId,actor);
        if(!"OPEN".equals(negotiation.status)) throw new ResponseStatusException(CONFLICT,"Negotiation is closed");
        checkVersion(negotiation.version,expectedVersion);
        var head=head(negotiation);
        if(offerId==null || !head.id.equals(offerId)) throw new ResponseStatusException(CONFLICT,"Offer is no longer current; reload before accepting");
        if(head.authorId.equals(actor)) throw new ResponseStatusException(CONFLICT,"Only the other party may accept this offer");
        var listing=activeListing(tenant,negotiation.listingId);
        head.status="ACCEPTED"; offers.saveAndFlush(head);
        negotiation.status="ACCEPTED"; negotiation.updatedAt=Instant.now();
        negotiation=negotiations.saveAndFlush(negotiation);
        var agreement=new Agreement(); agreement.id=UUID.randomUUID(); agreement.tenantId=tenant;
        agreement.negotiationId=negotiation.id; agreement.listingId=negotiation.listingId; agreement.offerId=head.id;
        agreement.initiatorId=negotiation.initiatorId; agreement.ownerId=negotiation.ownerId;
        if(head.proposedAmount==null) {
            agreement.economicPhase="NOT_APPLICABLE";
        } else {
            // OFFER: the owner provides the resource, so the initiator (proposer) pays them.
            // WANTED: the owner is requesting and pays whoever provides it (the initiator).
            if("OFFER".equals(listing.direction)) { agreement.payerUserId=negotiation.initiatorId; agreement.payeeUserId=negotiation.ownerId; }
            else { agreement.payerUserId=negotiation.ownerId; agreement.payeeUserId=negotiation.initiatorId; }
            agreement.economicPhase="AWAITING_ECONOMIC_EXECUTION";
        }
        agreement.createdAt=Instant.now();
        agreement=agreements.saveAndFlush(agreement);
        var snapshot=snapshotService.freeze(agreement,negotiation,head,listing.direction);
        return AgreementDetail.of(agreement,snapshot);
    }

    public NegotiationDetail decline(CurrentUser user, UUID negotiationId, long expectedVersion) {
        UUID tenant=tenant(), actor=actor(user);
        var negotiation=partyNegotiation(tenant,negotiationId,actor);
        if(!"OPEN".equals(negotiation.status)) throw new ResponseStatusException(CONFLICT,"Negotiation is closed");
        checkVersion(negotiation.version,expectedVersion);
        negotiation.status="DECLINED"; negotiation.updatedAt=Instant.now();
        negotiation=negotiations.saveAndFlush(negotiation);
        return NegotiationDetail.of(negotiation,offers.findByNegotiationIdOrderBySequenceNumberAsc(negotiation.id),null);
    }

    public NegotiationDetail read(CurrentUser user, UUID negotiationId) {
        UUID tenant=tenant(), actor=actor(user);
        var negotiation=partyNegotiation(tenant,negotiationId,actor);
        return NegotiationDetail.of(negotiation,offers.findByNegotiationIdOrderBySequenceNumberAsc(negotiation.id),agreementIdFor(negotiation));
    }

    public Page<Negotiation> listMine(CurrentUser user, String status, int page, int size) {
        return negotiations.findForParty(tenant(),actor(user),status,PageRequest.of(page,size));
    }

    private Offer newOffer(Negotiation negotiation, int sequence, UUID author, UUID previousOfferId, OfferRequest request) {
        var offer=new Offer(); offer.id=UUID.randomUUID(); offer.tenantId=negotiation.tenantId;
        offer.negotiationId=negotiation.id; offer.listingId=negotiation.listingId; offer.sequenceNumber=sequence;
        offer.authorId=author; offer.previousOfferId=previousOfferId;
        offer.message=request.message().trim(); offer.quantity=request.quantity(); offer.unitLabel=blank(request.unitLabel());
        offer.proposedAmount=request.proposedAmount(); offer.proposedUnitRef=blank(request.proposedUnitRef());
        offer.terms=blank(request.terms()); offer.status="PROPOSED"; offer.createdAt=Instant.now();
        return offer;
    }
    private String blank(String value) { return (value==null || value.isBlank()) ? null : value.trim(); }
}
