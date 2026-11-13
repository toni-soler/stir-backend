package org.stir.economic;

import es.idynamicsax.idax.security.CurrentUser;
import es.idynamicsax.idax.tenant.TenantContext;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.client.ResourceAccessException;
import org.stir.negotiation.*;
import org.stir.notification.NotificationService;
import org.stir.participant.ParticipantProfileRepository;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TradeServiceTest {
    final UUID tenant = UUID.randomUUID(), payer = UUID.randomUUID(), payee = UUID.randomUUID(), stranger = UUID.randomUUID();
    final UUID community = UUID.randomUUID(), unit = UUID.randomUUID();
    final UUID payerAccount = UUID.randomUUID(), payeeAccount = UUID.randomUUID();
    final UUID payerCredential = UUID.randomUUID(), payeeCredential = UUID.randomUUID();

    TradeRepository trades; AgreementRepository agreements; OfferRepository offers; AgreementSnapshotRepository snapshots;
    ParticipantEconomicBindingRepository participantBindings; MarketplaceEconomicBindingRepository marketplaceBindings;
    OstrisClient ostris; TradeService service; EconomicActivationService activation;
    Agreement agreement; Offer offer; AgreementSnapshot snapshot;
    CurrentUser payerUser, payeeUser, strangerUser;

    @BeforeEach void setup() {
        trades = mock(TradeRepository.class); agreements = mock(AgreementRepository.class);
        offers = mock(OfferRepository.class); snapshots = mock(AgreementSnapshotRepository.class);
        participantBindings = mock(ParticipantEconomicBindingRepository.class);
        marketplaceBindings = mock(MarketplaceEconomicBindingRepository.class);
        ostris = mock(OstrisClient.class);
        activation = new EconomicActivationService(marketplaceBindings, participantBindings, mock(ParticipantProfileRepository.class), ostris);
        service = new TradeService(trades, agreements, offers, snapshots, participantBindings, activation, ostris, mock(NotificationService.class));

        payerUser = mock(CurrentUser.class); when(payerUser.getUserId()).thenReturn(payer);
        payeeUser = mock(CurrentUser.class); when(payeeUser.getUserId()).thenReturn(payee);
        strangerUser = mock(CurrentUser.class); when(strangerUser.getUserId()).thenReturn(stranger);

        agreement = new Agreement(); agreement.id = UUID.randomUUID(); agreement.tenantId = tenant;
        agreement.negotiationId = UUID.randomUUID(); agreement.listingId = UUID.randomUUID();
        agreement.offerId = UUID.randomUUID(); agreement.initiatorId = payer; agreement.ownerId = payee;
        agreement.payerUserId = payer; agreement.payeeUserId = payee; agreement.economicPhase = "AWAITING_ECONOMIC_EXECUTION";
        agreement.createdAt = Instant.now();
        when(agreements.findByIdAndTenantId(agreement.id, tenant)).thenReturn(Optional.of(agreement));
        when(agreements.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        offer = new Offer(); offer.id = agreement.offerId; offer.proposedAmount = new BigDecimal("9.00");
        when(offers.findById(agreement.offerId)).thenReturn(Optional.of(offer));

        snapshot = new AgreementSnapshot(); snapshot.digestSha256 = "d".repeat(64);
        when(snapshots.findByAgreementId(agreement.id)).thenReturn(Optional.of(snapshot));

        var marketplace = new MarketplaceEconomicBinding(); marketplace.tenantId = tenant; marketplace.communityId = community; marketplace.unitId = unit;
        when(marketplaceBindings.findByTenantId(tenant)).thenReturn(Optional.of(marketplace));
        when(ostris.unit(community, unit)).thenReturn(new OstrisClient.UnitView(unit, "TST", 2));

        var payerBinding = new ParticipantEconomicBinding(); payerBinding.accountId = payerAccount; payerBinding.credentialId = payerCredential; payerBinding.communityId = community; payerBinding.unitId = unit;
        var payeeBinding = new ParticipantEconomicBinding(); payeeBinding.accountId = payeeAccount; payeeBinding.credentialId = payeeCredential; payeeBinding.communityId = community; payeeBinding.unitId = unit;
        when(participantBindings.findByTenantIdAndUserId(tenant, payer)).thenReturn(Optional.of(payerBinding));
        when(participantBindings.findByTenantIdAndUserId(tenant, payee)).thenReturn(Optional.of(payeeBinding));

        when(trades.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        TenantContext.set(new TenantContext(tenant, null, payer, "test", TenantContext.DbRole.IDAX_APP));
    }
    @AfterEach void clear() { TenantContext.clear(); }

    @Test void activateConvertsDecimalAmountToMinorUnitsAndProposesPayerNegativePayeePositive() {
        when(trades.findByTenantIdAndAgreementId(tenant, agreement.id)).thenReturn(Optional.empty());
        var view = service.activate(payerUser, agreement.id);
        assertEquals("900", view.amount());
        assertEquals("AWAITING_SIGNATURES", view.executionState());
        assertEquals("AWAITING_SIGNATURES", agreement.economicPhase);
        verify(ostris).propose(eq(community), eq(unit), any(),
            eq(List.of(new OstrisClient.Entry(payerAccount.toString(), "-900"), new OstrisClient.Entry(payeeAccount.toString(), "900"))),
            eq(snapshot.digestSha256));
    }
    @Test void activateIsIdempotentWhenATradeAlreadyExists() {
        var existing = new Trade(); existing.id = UUID.randomUUID(); existing.agreementId = agreement.id; existing.tenantId = tenant;
        existing.executionState = "AWAITING_SIGNATURES"; existing.amount = new BigInteger("900");
        when(trades.findByTenantIdAndAgreementId(tenant, agreement.id)).thenReturn(Optional.of(existing));
        service.activate(payerUser, agreement.id);
        verify(ostris, never()).propose(any(), any(), any(), any(), any());
    }
    @Test void activateRejectsWhenAgreementHasNoEconomicExecution() {
        agreement.economicPhase = "NOT_APPLICABLE";
        when(trades.findByTenantIdAndAgreementId(tenant, agreement.id)).thenReturn(Optional.empty());
        assertThrows(ResponseStatusException.class, () -> service.activate(payerUser, agreement.id));
    }
    @Test void activateRejectsWhenACounterpartyHasNotActivatedEconomicExchange() {
        when(trades.findByTenantIdAndAgreementId(tenant, agreement.id)).thenReturn(Optional.empty());
        when(participantBindings.findByTenantIdAndUserId(tenant, payee)).thenReturn(Optional.empty());
        assertThrows(ResponseStatusException.class, () -> service.activate(payerUser, agreement.id));
        verify(ostris, never()).propose(any(), any(), any(), any(), any());
    }
    @Test void strangerCannotSeeOrActOnTheTrade() {
        when(trades.findByTenantIdAndAgreementId(tenant, agreement.id)).thenReturn(Optional.empty());
        assertThrows(ResponseStatusException.class, () -> service.activate(strangerUser, agreement.id));
        assertThrows(ResponseStatusException.class, () -> service.find(strangerUser, agreement.id));
    }

    private Trade tradeAwaitingSignatures() {
        var trade = new Trade(); trade.id = UUID.randomUUID(); trade.tenantId = tenant; trade.agreementId = agreement.id;
        trade.transactionId = UUID.randomUUID(); trade.communityId = community; trade.unitId = unit;
        trade.payerAccountId = payerAccount; trade.payeeAccountId = payeeAccount; trade.payerUserId = payer; trade.payeeUserId = payee;
        trade.amount = new BigInteger("900"); trade.executionState = "AWAITING_SIGNATURES"; trade.contractualMetadataDigest = snapshot.digestSha256;
        when(trades.findByTenantIdAndAgreementId(tenant, agreement.id)).thenReturn(Optional.of(trade));
        when(ostris.transaction(trade.transactionId)).thenReturn(status(trade, "PROPOSED", null, null, null));
        return trade;
    }
    /** osTRIS's own view of this Agreement's transaction: payer -900, payee +900, this Agreement's digest. */
    OstrisClient.TransactionStatus status(Trade trade, String state, Long sequence, String protocolDigest, Instant committedAt) {
        return status(trade.transactionId, state, sequence, protocolDigest, committedAt, snapshot.digestSha256, unit,
            payerAccount, "-900", payeeAccount, "900");
    }
    OstrisClient.TransactionStatus status(UUID transaction, String state, Long sequence, String protocolDigest, Instant committedAt,
            String digest, UUID unitId, UUID debit, String debitAmount, UUID credit, String creditAmount) {
        String payload = "{\"communityId\":\"" + community + "\",\"contractualMetadataDigest\":\"" + digest + "\",\"entries\":["
            + "{\"accountId\":\"" + debit + "\",\"amount\":\"" + debitAmount + "\"},{\"accountId\":\"" + credit + "\",\"amount\":\"" + creditAmount + "\"}],"
            + "\"purpose\":\"EXCHANGE\",\"transactionId\":\"" + transaction + "\",\"unitId\":\"" + unitId + "\"}";
        return new OstrisClient.TransactionStatus(transaction, community, unitId, "EXCHANGE", "OSTRIS-CORE-JCS-1", "0.1", payload, "auth-digest",
            state, sequence, protocolDigest, committedAt, List.of(debit, credit));
    }

    @Test void authorizeRelaysToOstrisUsingTheCallersOwnAccountAndTheCredentialTheCallerActuallySignedWith() {
        // The caller's own SECOND device (a credential never stored on ParticipantEconomicBinding,
        // which only ever remembers the FIRST device's id) must still be relayed to osTRIS exactly
        // as the caller claims - never silently replaced by payerBinding's stored credentialId,
        // which would make a later device's perfectly valid signature always fail osTRIS's check.
        var secondDeviceCredential = UUID.randomUUID();
        var trade = tradeAwaitingSignatures();
        when(ostris.transaction(trade.transactionId)).thenReturn(status(trade, "PROPOSED", null, null, null));
        service.authorize(payerUser, agreement.id, secondDeviceCredential, "sig-bytes");
        verify(ostris).authorize(trade.transactionId, payerAccount, secondDeviceCredential, "sig-bytes");
    }
    @Test void commitSuccessMarksTradeAndAgreementCommittedWithReceiptData() {
        var trade = tradeAwaitingSignatures();
        var receipt = new OstrisClient.CommitReceipt(trade.transactionId, 7L, "protocol-digest", Instant.now());
        when(ostris.commit(trade.transactionId)).thenReturn(receipt);
        var view = service.commit(payeeUser, agreement.id);
        assertEquals("COMMITTED", view.executionState());
        assertEquals(7L, view.committedSequence());
        assertEquals("COMMITTED", agreement.economicPhase);
    }
    @Test void deterministicCreditFailureDoesNotInventTerminalRejection() {
        var trade = tradeAwaitingSignatures();
        when(ostris.commit(trade.transactionId)).thenThrow(new StirOstrisException(422, "CREDIT_FLOOR_EXCEEDED", "Credit floor exceeded"));
        var error = assertThrows(ResponseStatusException.class, () -> service.commit(payerUser, agreement.id));
        assertTrue(error.getReason().contains("CREDIT_FLOOR_EXCEEDED"));
        assertEquals("AWAITING_SIGNATURES", trade.executionState);
        verify(trades, never()).saveAndFlush(trade);
    }
    @Test void transientOstrisFailurePreservesRetryAndDoesNotRecordRejection() {
        var trade = tradeAwaitingSignatures();
        agreement.economicPhase = "AWAITING_SIGNATURES";
        var receipt = new OstrisClient.CommitReceipt(trade.transactionId, 7L, "protocol-digest", Instant.now());
        when(ostris.commit(trade.transactionId))
            .thenThrow(new StirOstrisException(503, "UPSTREAM_UNAVAILABLE", "Temporary outage"))
            .thenReturn(receipt);

        var error = assertThrows(ResponseStatusException.class, () -> service.commit(payerUser, agreement.id));
        assertEquals(503, error.getStatusCode().value());
        assertEquals("AWAITING_SIGNATURES", trade.executionState);
        assertEquals("AWAITING_SIGNATURES", agreement.economicPhase);

        assertEquals("COMMITTED", service.commit(payerUser, agreement.id).executionState());
        verify(ostris, times(2)).commit(trade.transactionId);
    }
    @Test void commitBeforeBothSignaturesDoesNotRejectAgreement() {
        var trade = tradeAwaitingSignatures();
        agreement.economicPhase = "AWAITING_SIGNATURES";
        when(ostris.commit(trade.transactionId)).thenThrow(new StirOstrisException(422,
            "CONTROL_POLICY_NOT_SATISFIED", "Insufficient distinct authorized signers"));

        var error = assertThrows(ResponseStatusException.class, () -> service.commit(payerUser, agreement.id));
        assertEquals(409, error.getStatusCode().value());
        assertEquals("AWAITING_SIGNATURES", trade.executionState);
        assertEquals("AWAITING_SIGNATURES", agreement.economicPhase);
    }
    @Test void timeoutWithUnknownOutcomeCanReconcileThenDuplicateRetryDoesNotCommitAgain() {
        var trade = tradeAwaitingSignatures();
        agreement.economicPhase = "AWAITING_SIGNATURES";
        when(ostris.commit(trade.transactionId)).thenThrow(new ResourceAccessException("read timed out"));
        var error = assertThrows(ResponseStatusException.class, () -> service.commit(payerUser, agreement.id));
        assertEquals(503, error.getStatusCode().value());
        assertEquals("AWAITING_SIGNATURES", trade.executionState);
        var committedAt = Instant.now();
        when(ostris.transaction(trade.transactionId)).thenReturn(status(trade, "COMMITTED", 8L, "protocol-digest", committedAt));
        assertEquals("COMMITTED", service.sync(payerUser, agreement.id).executionState());
        assertEquals("COMMITTED", service.commit(payerUser, agreement.id).executionState());
        verify(ostris, times(1)).commit(trade.transactionId);
    }
    @Test void deterministicRejectionCanBeRetriedAfterConditionsChange() {
        var trade = tradeAwaitingSignatures();
        agreement.economicPhase = "AWAITING_SIGNATURES";
        var receipt = new OstrisClient.CommitReceipt(trade.transactionId, 9L, "protocol-digest", Instant.now());
        when(ostris.commit(trade.transactionId))
            .thenThrow(new StirOstrisException(422, "CREDIT_FLOOR_EXCEEDED", "Credit floor exceeded"))
            .thenReturn(receipt);
        assertEquals(422, assertThrows(ResponseStatusException.class, () -> service.commit(payerUser, agreement.id)).getStatusCode().value());
        assertEquals("AWAITING_SIGNATURES", trade.executionState);
        assertEquals("COMMITTED", service.commit(payerUser, agreement.id).executionState());
        verify(ostris, times(2)).commit(trade.transactionId);
    }
    @Test void legacyFalseRejectionIsReconciledFromOstrisBeforeRetry() {
        var trade = tradeAwaitingSignatures(); trade.executionState = "REJECTED";
        agreement.economicPhase = "REJECTED";
        when(ostris.transaction(trade.transactionId)).thenReturn(status(trade, "PROPOSED", null, null, null));
        when(ostris.commit(trade.transactionId)).thenReturn(new OstrisClient.CommitReceipt(trade.transactionId, 10L, "protocol-digest", Instant.now()));
        assertEquals("COMMITTED", service.commit(payerUser, agreement.id).executionState());
        verify(ostris, times(1)).transaction(trade.transactionId);
        verify(ostris, times(1)).commit(trade.transactionId);
    }
    @Test void upstreamStatusMatrixNeverBecomesEconomicRejection() {
        var trade = tradeAwaitingSignatures();
        agreement.economicPhase = "AWAITING_SIGNATURES";
        Map<Integer,Integer> expected = Map.of(400,502,401,401,403,403,404,502,408,503,
            409,409,422,422,429,503,503,503);
        for (var entry : expected.entrySet()) {
            doThrow(new StirOstrisException(entry.getKey(), "AUDIT_UPSTREAM_" + entry.getKey(), "Controlled error"))
                .when(ostris).commit(trade.transactionId);
            var error = assertThrows(ResponseStatusException.class, () -> service.commit(payerUser, agreement.id));
            assertEquals(entry.getValue(), error.getStatusCode().value(), "upstream status " + entry.getKey());
            assertEquals("AWAITING_SIGNATURES", trade.executionState);
            assertEquals("AWAITING_SIGNATURES", agreement.economicPhase);
        }
        verify(trades, never()).saveAndFlush(trade);
    }
    @Test void commitIsIdempotentOnceAlreadyCommitted() {
        var trade = tradeAwaitingSignatures(); trade.executionState = "COMMITTED"; trade.committedSequence = 3L;
        service.commit(payerUser, agreement.id);
        verify(ostris, never()).commit(any());
    }
    @Test void syncNeverTrustsClientAndOnlyAdvancesWhenOstrisReportsCommitted() {
        var trade = tradeAwaitingSignatures();
        when(ostris.transaction(trade.transactionId)).thenReturn(status(trade, "PROPOSED", null, null, null));
        var stillPending = service.sync(payerUser, agreement.id);
        assertEquals("AWAITING_SIGNATURES", stillPending.executionState());

        when(ostris.transaction(trade.transactionId)).thenReturn(status(trade, "COMMITTED", 5L, "proto-digest", Instant.now()));
        var reconciled = service.sync(payerUser, agreement.id);
        assertEquals("COMMITTED", reconciled.executionState());
        assertEquals(5L, reconciled.committedSequence());
        assertEquals("COMMITTED", agreement.economicPhase);
    }

    // --- Correlation: the osTRIS transaction must be this Agreement's exchange (NEGOTIATION_EXECUTION_INTEGRITY.md) ---
    private void assertRefusedEverywhere(Trade trade) {
        String state = trade.executionState, phase = agreement.economicPhase;
        for (Runnable call : List.<Runnable>of(() -> service.signingPayload(payerUser, agreement.id), () -> service.commit(payeeUser, agreement.id),
                () -> service.sync(payerUser, agreement.id), () -> service.authorize(payerUser, agreement.id, payerCredential, "sig"))) {
            var error = assertThrows(ResponseStatusException.class, call::run);
            assertEquals(409, error.getStatusCode().value());
            assertTrue(error.getReason().startsWith("EXECUTION_INTENT_MISMATCH"));
        }
        verify(ostris, never()).commit(any());
        verify(ostris, never()).authorize(any(), any(), any(), any());
        assertEquals(state, trade.executionState); assertEquals(phase, agreement.economicPhase);
        verify(trades, never()).saveAndFlush(trade);
    }
    @Test void anotherAgreementsCommittedTransactionIsNeverAttributedToThisOne() {
        // stir.trade altered to point at Agreement B's transaction (same parties, same amount).
        var trade = tradeAwaitingSignatures();
        when(ostris.transaction(trade.transactionId)).thenReturn(status(trade.transactionId, "COMMITTED", 4L, "p", Instant.now(),
            "b".repeat(64), unit, payerAccount, "-900", payeeAccount, "900"));
        assertRefusedEverywhere(trade);
    }
    @Test void theSameSubstitutionWithTheTradeRowDigestRewrittenToo() {
        var trade = tradeAwaitingSignatures(); trade.contractualMetadataDigest = "b".repeat(64);
        when(ostris.transaction(trade.transactionId)).thenReturn(status(trade.transactionId, "COMMITTED", 4L, "p", Instant.now(),
            "b".repeat(64), unit, payerAccount, "-900", payeeAccount, "900"));
        assertRefusedEverywhere(trade);
    }
    @Test void aTransactionMovingAnotherAmountDirectionUnitOrAccountIsRefused() {
        var other = UUID.randomUUID();
        var trade = tradeAwaitingSignatures();
        for (var wrong : List.of(
                status(trade.transactionId, "PROPOSED", null, null, null, snapshot.digestSha256, unit, payerAccount, "-9000", payeeAccount, "9000"),
                status(trade.transactionId, "PROPOSED", null, null, null, snapshot.digestSha256, unit, payeeAccount, "-900", payerAccount, "900"),
                status(trade.transactionId, "PROPOSED", null, null, null, snapshot.digestSha256, UUID.randomUUID(), payerAccount, "-900", payeeAccount, "900"),
                status(trade.transactionId, "PROPOSED", null, null, null, snapshot.digestSha256, unit, payerAccount, "-900", other, "900"),
                status(UUID.randomUUID(), "PROPOSED", null, null, null, snapshot.digestSha256, unit, payerAccount, "-900", payeeAccount, "900"))) {
            when(ostris.transaction(trade.transactionId)).thenReturn(wrong);
            assertRefusedEverywhere(trade);
        }
    }
    @Test void aTradeRowThatNoLongerMatchesTheAgreementIsRefusedEvenIfOsTrisAgreesWithIt() {
        var trade = tradeAwaitingSignatures(); trade.amount = new BigInteger("9000");
        assertRefusedEverywhere(trade);
    }
    @Test void theMatchingTransactionStillSignsCommitsAndReconcilesNormally() {
        var trade = tradeAwaitingSignatures();
        assertNotNull(service.signingPayload(payerUser, agreement.id).authorizationPayload());
        when(ostris.commit(trade.transactionId)).thenReturn(new OstrisClient.CommitReceipt(trade.transactionId, 11L, "p", Instant.now()));
        assertEquals("COMMITTED", service.commit(payeeUser, agreement.id).executionState());
    }
}
