package org.stir.economic;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.stir.notification.NotificationService;

/**
 * Records a Trade/Agreement rejection in its OWN transaction. TradeService.commit() calls this
 * through Spring's proxy (a plain internal method call would skip both the REQUIRES_NEW advice and
 * RlsTransactionAspect's tenant/RLS context setup) right before rethrowing - the surrounding
 * commit() transaction is about to roll back on the way out and would otherwise undo this write too.
 */
@Service
public class TradeRejectionRecorder {
    private final TradeRepository trades;
    private final org.stir.negotiation.AgreementRepository agreements;
    private final NotificationService notifications;

    public TradeRejectionRecorder(TradeRepository trades, org.stir.negotiation.AgreementRepository agreements, NotificationService notifications) {
        this.trades = trades; this.agreements = agreements; this.notifications = notifications;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reject(UUID tenant, UUID agreementId) {
        var trade = trades.findByTenantIdAndAgreementId(tenant, agreementId).orElseThrow();
        var agreement = agreements.findByIdAndTenantId(agreementId, tenant).orElseThrow();
        trade.executionState = "REJECTED"; trade.updatedAt = Instant.now(); trades.saveAndFlush(trade);
        agreement.economicPhase = "REJECTED"; agreements.saveAndFlush(agreement);
        notifications.create(tenant, agreement.payerUserId, "TRADE_REJECTED", "AGREEMENT", agreementId);
        notifications.create(tenant, agreement.payeeUserId, "TRADE_REJECTED", "AGREEMENT", agreementId);
    }
}
