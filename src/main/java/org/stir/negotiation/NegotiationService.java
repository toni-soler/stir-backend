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
import org.stir.listing.ListingRevisionRepository;
import org.stir.notification.NotificationService;
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
    private final NotificationService notifications;
    private final org.stir.reference.ReferenceAcceptanceAdapter references;
    private final ListingRevisionRepository revisions;

    public NegotiationService(NegotiationRepository negotiations, OfferRepository offers, AgreementRepository agreements,
            ListingRepository listings, AgreementSnapshotService snapshotService, NotificationService notifications,
            org.stir.reference.ReferenceAcceptanceAdapter references, ListingRevisionRepository revisions) {
        this.negotiations=negotiations; this.offers=offers; this.agreements=agreements;
        this.listings=listings; this.snapshotService=snapshotService; this.notifications=notifications; this.references=references;
        this.revisions=revisions;
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
        return active(listings.findByIdAndTenantId(listingId,tenant));
    }
    /** Same, holding a shared row lock until commit: ListingService.update takes the exclusive one,
     * so an owner edit can neither interleave with opening nor with accepting a negotiation. */
    private Listing lockedActiveListing(UUID tenant, UUID listingId) {
        return active(listings.lockForShare(listingId,tenant));
    }
    private Listing active(java.util.Optional<Listing> found) {
        var listing=found.orElseThrow(()->new ResponseStatusException(NOT_FOUND,"Listing not found"));
        if(!"ACTIVE".equals(listing.status)) throw new ResponseStatusException(CONFLICT,"Listing is closed");
        return listing;
    }
    /** Payer/payee for this negotiation. They follow the listing direction the negotiation was opened
     * under; a listing whose direction changed afterwards (only possible before ListingService refused
     * it) no longer tells who agreed to pay whom, so it is refused rather than reinterpreted. */
    private EconomicParties parties(Negotiation negotiation, Listing listing) {
        var opened=revisions.findFirstByTenantIdAndListingIdAndCreatedAtLessThanEqualOrderByRevisionNumberDesc(
            negotiation.tenantId,negotiation.listingId,negotiation.createdAt);
        if(opened.isPresent() && !opened.get().direction.equals(listing.direction))
            throw new ResponseStatusException(CONFLICT,"The listing changed between offering and requesting after this negotiation started; it cannot be accepted");
        return EconomicParties.of(listing.direction,negotiation);
    }
    private EconomicParties partiesOrNull(Negotiation negotiation) {
        var listing=listings.findByIdAndTenantId(negotiation.listingId,negotiation.tenantId).orElse(null);
        return listing==null ? null : EconomicParties.of(listing.direction,negotiation);
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
        var listing=lockedActiveListing(tenant,listingId);
        if(listing.ownerId.equals(initiator)) throw new ResponseStatusException(BAD_REQUEST,"You cannot make an offer on your own listing");
        negotiations.findByTenantIdAndListingIdAndInitiatorIdAndStatus(tenant,listingId,initiator,"OPEN")
            .ifPresent(existing->{throw new ResponseStatusException(CONFLICT,"You already have an open negotiation on this listing");});
        var now=Instant.now();
        var negotiation=new Negotiation(); negotiation.id=UUID.randomUUID(); negotiation.tenantId=tenant;
        negotiation.referenceDefinitionId=listing.referenceDefinitionId;
        negotiation.listingId=listingId; negotiation.initiatorId=initiator; negotiation.ownerId=listing.ownerId;
        negotiation.status="OPEN"; negotiation.createdAt=now; negotiation.updatedAt=now;
        var created=negotiation;
        // A simultaneous open by the same initiator that won the race holds negotiation_one_open_thread.
        negotiation=Conflicts.onLostRace(()->negotiations.saveAndFlush(created));
        var offer=newOffer(negotiation,1,initiator,null,request);
        offer=offers.saveAndFlush(offer);
        references.proposed(negotiation,offer);
        negotiation.lastOfferId=offer.id; negotiation.updatedAt=now;
        negotiation=negotiations.saveAndFlush(negotiation);
        notifications.create(tenant,listing.ownerId,"OFFER_RECEIVED","NEGOTIATION",negotiation.id);
        return NegotiationDetail.of(negotiation,List.of(offer),null,EconomicParties.of(listing.direction,negotiation));
    }

    public NegotiationDetail counter(CurrentUser user, UUID negotiationId, OfferRequest request, long expectedVersion) {
        UUID tenant=tenant(), actor=actor(user);
        var negotiation=partyNegotiation(tenant,negotiationId,actor);
        if(!"OPEN".equals(negotiation.status)) throw new ResponseStatusException(CONFLICT,"Negotiation is closed");
        checkVersion(negotiation.version,expectedVersion);
        var head=head(negotiation);
        if(head.authorId.equals(actor)) throw new ResponseStatusException(CONFLICT,"Wait for the other party to respond");
        var listing=activeListing(tenant,negotiation.listingId);
        head.status="SUPERSEDED"; offers.saveAndFlush(head);
        var proposed=newOffer(negotiation,head.sequenceNumber+1,actor,head.id,request);
        // A simultaneous counteroffer that won the race already holds this sequence number.
        var next=Conflicts.onLostRace(()->offers.saveAndFlush(proposed));
        references.proposed(negotiation,next);
        negotiation.lastOfferId=next.id; negotiation.updatedAt=Instant.now();
        var advanced=negotiation;
        negotiation=Conflicts.onLostRace(()->negotiations.saveAndFlush(advanced));
        UUID otherParty=negotiation.initiatorId.equals(actor)?negotiation.ownerId:negotiation.initiatorId;
        notifications.create(tenant,otherParty,"COUNTEROFFER_RECEIVED","NEGOTIATION",negotiation.id);
        return NegotiationDetail.of(negotiation,offers.findByNegotiationIdOrderBySequenceNumberAsc(negotiation.id),null,EconomicParties.of(listing.direction,negotiation));
    }

    public AgreementDetail accept(CurrentUser user, UUID negotiationId, UUID offerId, long expectedVersion) {
        return accept(user,negotiationId,offerId,expectedVersion,false);
    }
    public AgreementDetail accept(CurrentUser user, UUID negotiationId, UUID offerId, long expectedVersion, boolean shareReferenceObservation) {
        return accept(user,negotiationId,offerId,expectedVersion,shareReferenceObservation,null,null);
    }
    /** expectedPayer/expectedPayee: the economic parties the caller reviewed (optional). Consent is to
     * an offer AND to who pays whom; an unchanged offerId/version alone does not pin the latter. */
    public AgreementDetail accept(CurrentUser user, UUID negotiationId, UUID offerId, long expectedVersion, boolean shareReferenceObservation,
            UUID expectedPayer, UUID expectedPayee) {
        UUID tenant=tenant(), actor=actor(user);
        var negotiation=partyNegotiation(tenant,negotiationId,actor);
        if(!"OPEN".equals(negotiation.status)) throw new ResponseStatusException(CONFLICT,"Negotiation is closed");
        checkVersion(negotiation.version,expectedVersion);
        var head=head(negotiation);
        if(offerId==null || !head.id.equals(offerId)) throw new ResponseStatusException(CONFLICT,"Offer is no longer current; reload before accepting");
        if(head.authorId.equals(actor)) throw new ResponseStatusException(CONFLICT,"Only the other party may accept this offer");
        var listing=lockedActiveListing(tenant,negotiation.listingId);
        var parties=parties(negotiation,listing);
        boolean economic=head.proposedAmount!=null;
        // The roles are those NegotiationDetail shows; the Agreement freezes them as payer/payee only
        // when the accepted offer carries an amount.
        if((expectedPayer!=null || expectedPayee!=null)
                && (!parties.payer().equals(expectedPayer) || !parties.payee().equals(expectedPayee)))
            throw new ResponseStatusException(CONFLICT,"Who pays and who receives is not what you reviewed; reload before accepting");
        head.status="ACCEPTED"; offers.saveAndFlush(head);
        negotiation.status="ACCEPTED"; negotiation.updatedAt=Instant.now();
        var closing=negotiation;
        negotiation=Conflicts.onLostRace(()->negotiations.saveAndFlush(closing));
        var agreement=new Agreement(); agreement.id=UUID.randomUUID(); agreement.tenantId=tenant;
        agreement.negotiationId=negotiation.id; agreement.listingId=negotiation.listingId; agreement.offerId=head.id;
        agreement.initiatorId=negotiation.initiatorId; agreement.ownerId=negotiation.ownerId;
        if(!economic) {
            agreement.economicPhase="NOT_APPLICABLE";
        } else {
            agreement.payerUserId=parties.payer(); agreement.payeeUserId=parties.payee();
            agreement.economicPhase="AWAITING_ECONOMIC_EXECUTION";
        }
        agreement.createdAt=Instant.now();
        var accepted=agreement;
        agreement=Conflicts.onLostRace(()->agreements.saveAndFlush(accepted));
        var snapshot=snapshotService.freeze(agreement,negotiation,head,listing.direction);
        references.accepted(negotiation,head,agreement,snapshot,shareReferenceObservation);
        notifications.create(tenant,head.authorId,"OFFER_ACCEPTED","AGREEMENT",agreement.id);
        return AgreementDetail.of(agreement,snapshot);
    }

    public NegotiationDetail decline(CurrentUser user, UUID negotiationId, long expectedVersion) {
        UUID tenant=tenant(), actor=actor(user);
        var negotiation=partyNegotiation(tenant,negotiationId,actor);
        if(!"OPEN".equals(negotiation.status)) throw new ResponseStatusException(CONFLICT,"Negotiation is closed");
        checkVersion(negotiation.version,expectedVersion);
        negotiation.status="DECLINED"; negotiation.updatedAt=Instant.now();
        var declined=negotiation;
        negotiation=Conflicts.onLostRace(()->negotiations.saveAndFlush(declined));
        UUID otherParty=negotiation.initiatorId.equals(actor)?negotiation.ownerId:negotiation.initiatorId;
        notifications.create(tenant,otherParty,"OFFER_DECLINED","NEGOTIATION",negotiation.id);
        return NegotiationDetail.of(negotiation,offers.findByNegotiationIdOrderBySequenceNumberAsc(negotiation.id),null,partiesOrNull(negotiation));
    }

    public NegotiationDetail read(CurrentUser user, UUID negotiationId) {
        UUID tenant=tenant(), actor=actor(user);
        var negotiation=partyNegotiation(tenant,negotiationId,actor);
        return NegotiationDetail.of(negotiation,offers.findByNegotiationIdOrderBySequenceNumberAsc(negotiation.id),agreementIdFor(negotiation),partiesOrNull(negotiation));
    }

    public Page<Negotiation> listMine(CurrentUser user, String status, int page, int size) {
        return negotiations.findForParty(tenant(),actor(user),status,PageRequest.of(page,size));
    }

    private Offer newOffer(Negotiation negotiation, int sequence, UUID author, UUID previousOfferId, OfferRequest request) {
        if ((request.externalContractNamespace()==null) != (request.externalContractDigest()==null))
            throw new ResponseStatusException(BAD_REQUEST,"External contract namespace and digest must be supplied together");
        var offer=new Offer(); offer.id=UUID.randomUUID(); offer.tenantId=negotiation.tenantId;
        offer.negotiationId=negotiation.id; offer.listingId=negotiation.listingId; offer.sequenceNumber=sequence;
        offer.authorId=author; offer.previousOfferId=previousOfferId;
        offer.message=request.message().trim(); offer.quantity=request.quantity(); offer.unitLabel=blank(request.unitLabel());
        offer.proposedAmount=request.proposedAmount(); offer.proposedUnitRef=blank(request.proposedUnitRef());
        offer.shareReferenceObservation=request.shareReferenceObservation();
        offer.terms=blank(request.terms()); offer.status="PROPOSED"; offer.createdAt=Instant.now();
        offer.externalContractNamespace=request.externalContractNamespace();
        offer.externalContractDigest=request.externalContractDigest();
        return offer;
    }
    private String blank(String value) { return (value==null || value.isBlank()) ? null : value.trim(); }
}
