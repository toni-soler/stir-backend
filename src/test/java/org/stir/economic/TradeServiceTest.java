package org.stir.economic;

import es.idynamicsax.idax.security.CurrentUser;
import es.idynamicsax.idax.tenant.TenantContext;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
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
    OstrisClient ostris; TradeService service; EconomicActivationService activation; TradeRejectionRecorder rejectionRecorder;
    Agreement agreement; Offer offer; AgreementSnapshot snapshot;
    CurrentUser payerUser, payeeUser, strangerUser;

    @BeforeEach void setup() {
        trades = mock(TradeRepository.class); agreements = mock(AgreementRepository.class);
        offers = mock(OfferRepository.class); snapshots = mock(AgreementSnapshotRepository.class);
        participantBindings = mock(ParticipantEconomicBindingRepository.class);
        marketplaceBindings = mock(MarketplaceEconomicBindingRepository.class);
        ostris = mock(OstrisClient.class);
        activation = new EconomicActivationService(marketplaceBindings, participantBindings, mock(ParticipantProfileRepository.class), ostris);
        rejectionRecorder = mock(TradeRejectionRecorder.class);
        service = new TradeService(trades, agreements, offers, snapshots, participantBindings, activation, ostris, rejectionRecorder, mock(NotificationService.class));

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
        return trade;
    }

    @Test void authorizeRelaysToOstrisUsingTheCallersOwnAccountAndTheCredentialTheCallerActuallySignedWith() {
        // The caller's own SECOND device (a credential never stored on ParticipantEconomicBinding,
        // which only ever remembers the FIRST device's id) must still be relayed to osTRIS exactly
        // as the caller claims - never silently replaced by payerBinding's stored credentialId,
        // which would make a later device's perfectly valid signature always fail osTRIS's check.
        var secondDeviceCredential = UUID.randomUUID();
        var trade = tradeAwaitingSignatures();
        when(ostris.transaction(trade.transactionId)).thenReturn(new OstrisClient.TransactionStatus(
            trade.transactionId, community, unit, "EXCHANGE", "OSTRIS-CORE-JCS-1", "0.1", "{}", "digest", "PROPOSED", null, null, null, List.of(payerAccount)));
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
    @Test void commitFailureRecordsTheRejectionInItsOwnTransactionAndSurfacesTheOstrisReason() {
        var trade = tradeAwaitingSignatures();
        when(ostris.commit(trade.transactionId)).thenThrow(new StirOstrisException(422, "CREDIT_FLOOR_EXCEEDED", "Credit floor exceeded"));
        var error = assertThrows(ResponseStatusException.class, () -> service.commit(payerUser, agreement.id));
        assertTrue(error.getReason().contains("CREDIT_FLOOR_EXCEEDED"));
        // Recorded via a separate @Transactional(REQUIRES_NEW) bean (not a direct field mutation
        // here) so the write survives this method's own transaction rolling back on the way out.
        verify(rejectionRecorder).reject(tenant, agreement.id);
    }
    @Test void commitIsIdempotentOnceAlreadyCommitted() {
        var trade = tradeAwaitingSignatures(); trade.executionState = "COMMITTED"; trade.committedSequence = 3L;
        service.commit(payerUser, agreement.id);
        verify(ostris, never()).commit(any());
    }
    @Test void syncNeverTrustsClientAndOnlyAdvancesWhenOstrisReportsCommitted() {
        var trade = tradeAwaitingSignatures();
        when(ostris.transaction(trade.transactionId)).thenReturn(new OstrisClient.TransactionStatus(
            trade.transactionId, community, unit, "EXCHANGE", "OSTRIS-CORE-JCS-1", "0.1", "{}", "digest", "PROPOSED", null, null, null, List.of(payerAccount, payeeAccount)));
        var stillPending = service.sync(payerUser, agreement.id);
        assertEquals("AWAITING_SIGNATURES", stillPending.executionState());

        when(ostris.transaction(trade.transactionId)).thenReturn(new OstrisClient.TransactionStatus(
            trade.transactionId, community, unit, "EXCHANGE", "OSTRIS-CORE-JCS-1", "0.1", "{}", "digest", "COMMITTED", 5L, "proto-digest", Instant.now(), List.of(payerAccount, payeeAccount)));
        var reconciled = service.sync(payerUser, agreement.id);
        assertEquals("COMMITTED", reconciled.executionState());
        assertEquals(5L, reconciled.committedSequence());
        assertEquals("COMMITTED", agreement.economicPhase);
    }
}
