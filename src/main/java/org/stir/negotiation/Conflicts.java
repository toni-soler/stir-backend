package org.stir.negotiation;

import java.util.Map;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.CONFLICT;

/**
 * Maps the database's own answer to a lost race into a 409. Only the named constraints that encode
 * "someone else got there first" are translated - any other integrity violation is a real defect
 * and keeps propagating as one (NEGOTIATION_EXECUTION_INTEGRITY.md).
 */
final class Conflicts {
    static final String CHANGED = "Negotiation changed; reload before continuing";
    private static final Map<String,String> EXPECTED = Map.of(
        "negotiation_one_open_thread", "You already have an open negotiation on this listing",
        "offer_negotiation_id_sequence_number_key", CHANGED,
        "agreement_negotiation_id_key", "This negotiation has already been accepted");
    private static final java.util.regex.Pattern NAMED = java.util.regex.Pattern.compile("constraint \"([^\"]+)\"");
    private Conflicts() {}

    static <T> T onLostRace(Supplier<T> write) {
        try { return write.get(); }
        catch (OptimisticLockingFailureException ex) { throw new ResponseStatusException(CONFLICT, CHANGED, ex); }
        catch (DataIntegrityViolationException ex) {
            String message = EXPECTED.get(constraint(ex));
            if (message == null) throw ex;
            throw new ResponseStatusException(CONFLICT, message, ex);
        }
    }

    static String constraint(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException violation && violation.getConstraintName() != null)
                return unqualified(violation.getConstraintName());
            // The driver is a runtime-only dependency: read the unique-violation message generically.
            if (cause instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState()) && sql.getMessage() != null) {
                var match = NAMED.matcher(sql.getMessage());
                if (match.find()) return unqualified(match.group(1));
            }
        }
        return null;
    }
    private static String unqualified(String name) { return name.substring(name.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT); }
}
