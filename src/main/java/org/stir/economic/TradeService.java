package org.stir.economic;

import es.idynamicsax.idax.security.CurrentUser;
import es.idynamicsax.idax.tenant.TenantContext;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.stir.negotiation.Agreement;
import org.stir.negotiation.AgreementRepository;
import org.stir.negotiation.AgreementSnapshotRepository;
import org.stir.negotiation.Offer;
import org.stir.negotiation.OfferRepository;
import static org.springframework.http.HttpStatus.*;

/**
 * STIR -> osTRIS EXCHANGE lifecycle for one economic Agreement (section 14-19 of the 0.3 brief).
 * STIR never signs on a user's behalf and never evaluates policy itself: it derives entries from
 * its OWN Agreement/Offer data (never trusts a client-supplied amount at this stage), creates the
 * osTRIS proposal, relays each party's own client-produced signature, and calls commit - observing
 * whatever osTRIS actually decides. Trade.executionState only ever reflects what osTRIS itself
 * told this server directly (a commit response) or what a sync() read back (GET status) - never a
 * client's unverified claim.
 */
@Service @Transactional
public class TradeService {
    private final TradeRepository trades;
    private final AgreementRepository agreements;
    private final OfferRepository offers;
    private final AgreementSnapshotRepository snapshots;
    private final ParticipantEconomicBindingRepository participantBindings;
    private final EconomicActivationService activation;
    private final OstrisClient ostris;
    private final TradeRejectionRecorder rejectionRecorder;
    private final SecureRandom random = new SecureRandom();

    public TradeService(TradeRepository trades, AgreementRepository agreements, OfferRepository offers,
            AgreementSnapshotRepository snapshots, ParticipantEconomicBindingRepository participantBindings,
            EconomicActivationService activation, OstrisClient ostris, TradeRejectionRecorder rejectionRecorder) {
        this.trades = trades; this.agreements = agreements; this.offers = offers; this.snapshots = snapshots;
        this.participantBindings = participantBindings; this.activation = activation; this.ostris = ostris;
        this.rejectionRecorder = rejectionRecorder;
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
    private Agreement partyAgreement(UUID tenant, UUID id, UUID actor) {
        var agreement = agreements.findByIdAndTenantId(id, tenant).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Agreement not found"));
        if (!agreement.initiatorId.equals(actor) && !agreement.ownerId.equals(actor)) throw new ResponseStatusException(NOT_FOUND, "Agreement not found");
        return agreement;
    }
    private Trade requireTrade(UUID tenant, UUID agreementId) {
        return trades.findByTenantIdAndAgreementId(tenant, agreementId)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "No economic exchange has been activated for this agreement yet"));
    }
    private ParticipantEconomicBinding requireBinding(UUID tenant, UUID user, String label) {
        return participantBindings.findByTenantIdAndUserId(tenant, user)
            .orElseThrow(() -> new ResponseStatusException(CONFLICT, label + " has not activated economic exchange yet"));
    }

    public TradeView find(CurrentUser user, UUID agreementId) {
        UUID tenant = tenant();
        partyAgreement(tenant, agreementId, actor(user));
        return trades.findByTenantIdAndAgreementId(tenant, agreementId).map(TradeView::of).orElse(null);
    }

    public SigningPayload signingPayload(CurrentUser user, UUID agreementId) {
        UUID tenant = tenant();
        partyAgreement(tenant, agreementId, actor(user));
        var trade = requireTrade(tenant, agreementId);
        var status = ostris.transaction(trade.transactionId);
        return new SigningPayload(status.wireFormat(), status.authorizationPayload(), status.authorizationDigest(), status.authorizedAccountIds());
    }

    public TradeView activate(CurrentUser user, UUID agreementId) {
        UUID tenant = tenant(), actor = actor(user);
        var agreement = partyAgreement(tenant, agreementId, actor);
        var existing = trades.findByTenantIdAndAgreementId(tenant, agreementId);
        if (existing.isPresent()) return TradeView.of(existing.get());
        if (!"AWAITING_ECONOMIC_EXECUTION".equals(agreement.economicPhase))
            throw new ResponseStatusException(CONFLICT, "This agreement has no pending economic execution (" + agreement.economicPhase + ")");

        var offer = offers.findById(agreement.offerId).orElseThrow(() -> new ResponseStatusException(CONFLICT, "Accepted offer is missing"));
        var marketplace = activation.requireMarketplaceBinding(tenant);
        var unit = ostris.unit(marketplace.communityId, marketplace.unitId);
        var payerBinding = requireBinding(tenant, agreement.payerUserId, "The paying party");
        var payeeBinding = requireBinding(tenant, agreement.payeeUserId, "The receiving party");
        var snapshot = snapshots.findByAgreementId(agreementId).orElseThrow(() -> new ResponseStatusException(CONFLICT, "Agreement snapshot is missing"));

        BigInteger minorUnits = toMinorUnits(offer, unit.scale());
        UUID transactionId = generateUuidV7();
        ostris.propose(marketplace.communityId, marketplace.unitId, transactionId,
            List.of(new OstrisClient.Entry(payerBinding.accountId.toString(), "-" + minorUnits),
                    new OstrisClient.Entry(payeeBinding.accountId.toString(), minorUnits.toString())),
            snapshot.digestSha256);

        var trade = new Trade();
        trade.id = UUID.randomUUID(); trade.tenantId = tenant; trade.agreementId = agreementId;
        trade.communityId = marketplace.communityId; trade.unitId = marketplace.unitId; trade.transactionId = transactionId;
        trade.payerAccountId = payerBinding.accountId; trade.payeeAccountId = payeeBinding.accountId;
        trade.payerUserId = agreement.payerUserId; trade.payeeUserId = agreement.payeeUserId;
        trade.amount = minorUnits; trade.contractualMetadataDigest = snapshot.digestSha256;
        trade.executionState = "AWAITING_SIGNATURES";
        trade.createdAt = Instant.now(); trade.updatedAt = trade.createdAt;
        trade = trades.saveAndFlush(trade);

        agreement.economicPhase = "AWAITING_SIGNATURES";
        agreements.saveAndFlush(agreement);
        return TradeView.of(trade);
    }

    public TradeView authorize(CurrentUser user, UUID agreementId, String signatureBase64url) {
        UUID tenant = tenant(), actor = actor(user);
        var agreement = partyAgreement(tenant, agreementId, actor);
        var trade = requireTrade(tenant, agreementId);
        if (!"AWAITING_SIGNATURES".equals(trade.executionState))
            throw new ResponseStatusException(CONFLICT, "This exchange is no longer awaiting signatures (" + trade.executionState + ")");
        var binding = requireBinding(tenant, actor, "You");
        UUID myAccount = actor.equals(agreement.payerUserId) ? trade.payerAccountId : trade.payeeAccountId;
        ostris.authorize(trade.transactionId, myAccount, binding.credentialId, signatureBase64url);
        return sync(user, agreementId);
    }

    public TradeView commit(CurrentUser user, UUID agreementId) {
        UUID tenant = tenant();
        var agreement = partyAgreement(tenant, agreementId, actor(user));
        var trade = requireTrade(tenant, agreementId);
        if ("COMMITTED".equals(trade.executionState)) return TradeView.of(trade);
        if ("REJECTED".equals(trade.executionState)) throw new ResponseStatusException(CONFLICT, "This exchange was already rejected");
        try {
            var receipt = ostris.commit(trade.transactionId);
            trade.executionState = "COMMITTED"; trade.committedSequence = receipt.communitySequence();
            trade.protocolDigest = receipt.protocolDigest(); trade.committedAt = receipt.committedAt();
            agreement.economicPhase = "COMMITTED";
        } catch (StirOstrisException ex) {
            // Committed in its OWN transaction (via a separate @Transactional(REQUIRES_NEW) bean, so
            // Spring's proxy - and RlsTransactionAspect's tenant/RLS setup - actually applies): this
            // method is about to rethrow, and the surrounding @Transactional would otherwise roll
            // back this very write on the way out.
            rejectionRecorder.reject(tenant, agreementId);
            throw new ResponseStatusException(UNPROCESSABLE_ENTITY, ex.code + ": " + ex.getMessage());
        }
        trade.updatedAt = Instant.now(); trades.saveAndFlush(trade); agreements.saveAndFlush(agreement);
        return TradeView.of(trade);
    }

    /** Reconciliation: never trusts a client's claim, always re-derives from osTRIS's own status. */
    public TradeView sync(CurrentUser user, UUID agreementId) {
        UUID tenant = tenant();
        var agreement = partyAgreement(tenant, agreementId, actor(user));
        var trade = requireTrade(tenant, agreementId);
        if (!"COMMITTED".equals(trade.executionState)) {
            var status = ostris.transaction(trade.transactionId);
            if ("COMMITTED".equals(status.status())) {
                trade.executionState = "COMMITTED"; trade.committedSequence = status.committedSequence();
                trade.protocolDigest = status.protocolDigest(); trade.committedAt = status.committedAt();
                trade.updatedAt = Instant.now(); trades.saveAndFlush(trade);
                agreement.economicPhase = "COMMITTED"; agreements.saveAndFlush(agreement);
            }
        }
        return TradeView.of(trade);
    }

    private BigInteger toMinorUnits(Offer offer, int unitScale) {
        if (offer.proposedAmount == null) throw new ResponseStatusException(CONFLICT, "Accepted offer has no economic amount");
        try { return offer.proposedAmount.setScale(unitScale, RoundingMode.UNNECESSARY).unscaledValue(); }
        catch (ArithmeticException ex) {
            throw new ResponseStatusException(BAD_REQUEST, "Proposed amount has more precision than this marketplace's unit supports (scale " + unitScale + ")");
        }
    }

    /** RFC 9562 UUIDv7: osTRIS's own newly-created protocol identifiers are all UUIDv7; STIR mints the transactionId, so it mints it correctly. */
    private UUID generateUuidV7() {
        long millis = Instant.now().toEpochMilli() & 0xFFFFFFFFFFFFL;
        long randomA = random.nextInt(1 << 12);
        long msb = (millis << 16) | 0x7000L | randomA;
        long lsb = random.nextLong();
        lsb = (lsb & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }

    public record SigningPayload(String wireFormat, String authorizationPayload, String authorizationDigest, List<UUID> authorizedAccountIds) {}
    public record TradeView(UUID id, UUID agreementId, UUID communityId, UUID unitId, UUID transactionId,
        UUID payerAccountId, UUID payeeAccountId, UUID payerUserId, UUID payeeUserId, String amount,
        String executionState, Long committedSequence, String protocolDigest, Instant committedAt, long version) {
        static TradeView of(Trade trade) {
            return new TradeView(trade.id, trade.agreementId, trade.communityId, trade.unitId, trade.transactionId,
                trade.payerAccountId, trade.payeeAccountId, trade.payerUserId, trade.payeeUserId, trade.amount.toString(),
                trade.executionState, trade.committedSequence, trade.protocolDigest, trade.committedAt, trade.version);
        }
    }
}
