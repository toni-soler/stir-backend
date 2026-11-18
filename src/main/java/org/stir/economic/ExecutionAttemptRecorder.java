package org.stir.economic;

import es.idynamicsax.idax.tenant.DbSessionContextService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists every economic execution attempt (stir.economic_execution_attempt, append-only).
 * A failed attempt is recorded in its own transaction: the exception the caller then receives
 * rolls back the caller's transaction, and must not take the record of what happened with it.
 */
@Service
public class ExecutionAttemptRecorder {
    private final JdbcTemplate jdbc;
    private final DbSessionContextService session;

    public ExecutionAttemptRecorder(JdbcTemplate jdbc, DbSessionContextService session) { this.jdbc = jdbc; this.session = session; }

    /** Inside the caller's transaction: a COMMITTED attempt is stored together with the Trade it committed. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void committed(UUID tenant, UUID agreementId, UUID tradeId, UUID actor, long sequence) {
        insert(tenant, agreementId, tradeId, actor, ExecutionOutcome.COMMITTED, null, null, null, sequence);
    }

    /** In a new transaction, so it survives the failure it records. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failed(UUID tenant, UUID agreementId, UUID tradeId, UUID actor, ExecutionOutcome outcome,
                       String ostrisCode, Integer httpStatus, String detail) {
        // The new connection needs the tenant's RLS session settings of its own.
        session.applyFromTenantContext();
        insert(tenant, agreementId, tradeId, actor, outcome, ostrisCode, httpStatus, detail, null);
    }

    private void insert(UUID tenant, UUID agreementId, UUID tradeId, UUID actor, ExecutionOutcome outcome,
                        String ostrisCode, Integer httpStatus, String detail, Long sequence) {
        jdbc.update("insert into stir.economic_execution_attempt(id,tenant_id,agreement_id,trade_id,actor_user_id,outcome,"
                + "ostris_code,http_status,detail,committed_sequence,created_at) values (?,?,?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID(), tenant, agreementId, tradeId, actor, outcome.name(), truncate(ostrisCode, 80), httpStatus,
            truncate(detail, 500), sequence, Timestamp.from(Instant.now()));
    }

    @Transactional(readOnly = true)
    public List<AttemptView> list(UUID tenant, UUID agreementId) {
        return jdbc.query("select id,outcome,ostris_code,http_status,detail,committed_sequence,created_at,actor_user_id "
                + "from stir.economic_execution_attempt where tenant_id=? and agreement_id=? order by created_at, id",
            (rs, i) -> new AttemptView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                (Integer) rs.getObject(4), rs.getString(5), (Long) rs.getObject(6), rs.getTimestamp(7).toInstant(),
                rs.getObject(8, UUID.class)),
            tenant, agreementId);
    }

    private static String truncate(String value, int max) { return value == null || value.length() <= max ? value : value.substring(0, max); }

    public record AttemptView(UUID id, String outcome, String ostrisCode, Integer httpStatus, String detail,
                              Long committedSequence, Instant createdAt, UUID actorUserId) {}
}
